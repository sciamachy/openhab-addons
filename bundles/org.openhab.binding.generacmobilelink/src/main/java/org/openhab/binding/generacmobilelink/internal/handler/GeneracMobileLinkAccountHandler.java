/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.generacmobilelink.internal.handler;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.WWWAuthenticationProtocolHandler;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.HttpCookieStore;
import org.openhab.binding.generacmobilelink.internal.GeneracMobileLinkBindingConstants;
import org.openhab.binding.generacmobilelink.internal.api.Auth0Client;
import org.openhab.binding.generacmobilelink.internal.api.AuthException;
import org.openhab.binding.generacmobilelink.internal.api.OAuthErrorException;
import org.openhab.binding.generacmobilelink.internal.config.GeneracMobileLinkAccountConfiguration;
import org.openhab.binding.generacmobilelink.internal.config.GeneracMobileLinkGeneratorConfiguration;
import org.openhab.binding.generacmobilelink.internal.discovery.GeneracMobileLinkDiscoveryService;
import org.openhab.binding.generacmobilelink.internal.dto.Apparatus;
import org.openhab.binding.generacmobilelink.internal.dto.ApparatusDetail;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link GeneracMobileLinkAccountHandler} is responsible for connecting to the MobileLink cloud service and
 * discovering generator things
 *
 * @author Dan Cunningham - Initial contribution
 * @author Chris Harris - Auth0 login
 */
@NonNullByDefault
public class GeneracMobileLinkAccountHandler extends BaseBridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(GeneracMobileLinkAccountHandler.class);
    private static final int REQUEST_TIMEOUT_MS = 10_000;
    /**
     * How many polls in a row have to fail before the bridge goes offline. A single failed poll usually heals on the
     * next one, and going offline drags every generator to BRIDGE_OFFLINE with it.
     */
    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 3;

    private static final String API_BASE = "https://app.mobilelinkgen.com/api";
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(ZonedDateTime.class, (JsonDeserializer<ZonedDateTime>) (json, type,
                    jsonDeserializationContext) -> ZonedDateTime.parse(json.getAsJsonPrimitive().getAsString()))
            .create();
    private final HttpClientFactory httpClientFactory;
    private final GeneracMobileLinkDiscoveryService discoveryService;
    private final AuthSession authSession;
    private final Function<HttpClient, Auth0Client> auth0ClientFactory;
    private final String apiBase;
    private final @Nullable ScheduledExecutorService executor;
    private final Map<String, Apparatus> apparatusesCache = new ConcurrentHashMap<>();
    private volatile @Nullable HttpClient httpClient;
    private volatile @Nullable Auth0Client auth0Client;
    private int consecutivePollFailures;

    private @Nullable Future<?> pollFuture;
    /**
     * Counts poll schedules, so that a poll that is still running after dispose() or a restart can tell it is stale
     * and does not overwrite the status or cancel the new schedule.
     */
    private volatile int pollGeneration;

    public GeneracMobileLinkAccountHandler(Bridge bridge, HttpClientFactory httpClientFactory,
            GeneracMobileLinkDiscoveryService discoveryService, StorageService storageService) {
        this(bridge, httpClientFactory, discoveryService, new AuthSession(storageService.getStorage(
                GeneracMobileLinkBindingConstants.BINDING_ID + "." + bridge.getUID().getAsString().replace(':', '_'),
                String.class.getClassLoader())), Auth0Client::new, API_BASE, null);
    }

    /**
     * For tests: replaces the login, the API location and, if given, the executor used for polling.
     */
    GeneracMobileLinkAccountHandler(Bridge bridge, HttpClientFactory httpClientFactory,
            GeneracMobileLinkDiscoveryService discoveryService, AuthSession authSession,
            Function<HttpClient, Auth0Client> auth0ClientFactory, String apiBase,
            @Nullable ScheduledExecutorService executor) {
        super(bridge);
        this.httpClientFactory = httpClientFactory;
        this.discoveryService = discoveryService;
        this.authSession = authSession;
        this.auth0ClientFactory = auth0ClientFactory;
        this.apiBase = apiBase;
        this.executor = executor;
    }

    private ScheduledExecutorService executor() {
        ScheduledExecutorService executor = this.executor;
        return executor != null ? executor : scheduler;
    }

    @Override
    public void initialize() {
        GeneracMobileLinkAccountConfiguration config = getConfigAs(GeneracMobileLinkAccountConfiguration.class);
        if (config.username.isBlank() || config.password.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/thing.generacmobilelink.account.offline.configuration-error.missing-credentials");
            return;
        }
        // Created here rather than in the constructor: openHAB reuses the handler instance when the thing is
        // updated, calling dispose() and initialize() on it, and dispose() stops the client.
        HttpClient client = httpClientFactory.createHttpClient(GeneracMobileLinkBindingConstants.BINDING_ID);
        client.setFollowRedirects(false);
        // Auth0Client keeps a cookie jar per login; the API needs no cookies at all
        client.setCookieStore(new HttpCookieStore.Empty());
        try {
            client.start();
        } catch (Exception e) {
            logger.debug("Could not start HTTP client", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/thing.generacmobilelink.account.offline.communication-error.http-client");
            return;
        }
        // Jetty turns a 401 without a WWW-Authenticate header into an exception, which would hide that the API
        // rejected the access token; the binding handles 401 itself. start() installs the handler, so remove it after.
        client.getProtocolHandlers().remove(WWWAuthenticationProtocolHandler.NAME);
        httpClient = client;
        auth0Client = auth0ClientFactory.apply(client);
        consecutivePollFailures = 0;
        authSession.configurationApplied();
        updateStatus(ThingStatus.UNKNOWN);
        startPoll(config.refreshInterval);
    }

    @Override
    public void dispose() {
        stopPoll(true);
        auth0Client = null;
        HttpClient client = httpClient;
        httpClient = null;
        if (client != null) {
            try {
                client.stop();
            } catch (Exception e) {
                logger.debug("Could not stop HttpClient", e);
            }
        }
    }

    @Override
    public void handleRemoval() {
        // openHAB calls this before dispose(), so stop a poll that may be logging in before deleting the tokens
        stopPoll(true);
        authSession.reset();
        super.handleRemoval();
    }

    /**
     * While the bridge waits for the user (a refused login, a code that was not entered in time), saving the
     * configuration unchanged has to retry, as the status text says. openHAB would otherwise ignore such a save.
     */
    @Override
    protected boolean isModifyingCurrentConfig(Map<String, Object> configurationParameters) {
        ThingStatusInfo status = getThing().getStatusInfo();
        boolean waitingForUser = status.getStatus() == ThingStatus.OFFLINE
                && (status.getStatusDetail() == ThingStatusDetail.CONFIGURATION_ERROR
                        || status.getStatusDetail() == ThingStatusDetail.CONFIGURATION_PENDING);
        return waitingForUser || super.isModifyingCurrentConfig(configurationParameters);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // The bridge has no channels
    }

    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        logger.debug("childHandlerInitialized {}", childThing.getUID());
        String id = childThing.getConfiguration().as(GeneracMobileLinkGeneratorConfiguration.class).generatorId;
        Apparatus apparatus = apparatusesCache.get(id);
        HttpClient client = httpClient;
        String accessToken = authSession.getCachedAccessToken();
        if (apparatus == null || client == null || accessToken == null) {
            // The next poll updates it
            return;
        }
        executor().execute(() -> {
            try {
                updateGeneratorThing(client, accessToken, childHandler, apparatus);
            } catch (IOException e) {
                logger.debug("Could not initialize child", e);
            }
        });
    }

    private synchronized void startPoll(int refreshIntervalSeconds) {
        stopPoll(true);
        int generation = pollGeneration;
        pollFuture = executor().scheduleWithFixedDelay(() -> poll(generation), 1, refreshIntervalSeconds,
                TimeUnit.SECONDS);
    }

    private synchronized void stopPoll(boolean interrupt) {
        pollGeneration++;
        Future<?> pollFuture = this.pollFuture;
        if (pollFuture != null) {
            pollFuture.cancel(interrupt);
            this.pollFuture = null;
        }
    }

    /**
     * Stops polling from within a poll, unless that poll has already been superseded.
     */
    private synchronized void stopPollFrom(int generation) {
        if (generation == pollGeneration) {
            stopPoll(false);
        }
    }

    private void poll(int generation) {
        HttpClient client = httpClient;
        Auth0Client auth = auth0Client;
        if (client == null || auth == null) {
            return;
        }
        try {
            String accessToken = authSession.getAccessToken(auth,
                    getConfigAs(GeneracMobileLinkAccountConfiguration.class));
            updateGeneratorThings(client, accessToken, generation);
            consecutivePollFailures = 0;
        } catch (MfaCodeNeededException e) {
            logger.debug("{}", e.getMessage());
            String description = switch (e.getState()) {
                case WAITING -> "mfa-" + e.getMfaType();
                case REJECTED -> "mfa-code-rejected";
                case EXPIRED -> "mfa-expired";
            };
            updateStatus(generation, ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                    "@text/thing.generacmobilelink.account.offline.configuration-pending." + description);
        } catch (AuthException e) {
            handleAuthFailure(e, generation);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                // dispose() cancelled this poll
                logger.debug("Poll interrupted", e);
                return;
            }
            handlePollFailure(e, generation);
        } catch (RuntimeException e) {
            // Unexpected, but it must not end the schedule, which would freeze the bridge in its last status
            logger.warn("Unexpected error while polling MobileLink", e);
            handlePollFailure(new IOException(e), generation);
        }
    }

    private void updateStatus(int generation, ThingStatus status, ThingStatusDetail detail,
            @Nullable String description) {
        if (generation == pollGeneration) {
            updateStatus(status, detail, description);
        }
    }

    /**
     * A refused login does not heal by itself, and repeating it can get the account locked, so polling stops until
     * the configuration is saved again.
     */
    private void handleAuthFailure(AuthException e, int generation) {
        logger.debug("Login failed: {}", e.getMessage());
        String description = switch (e.getReason()) {
            case INVALID_CREDENTIALS ->
                "@text/thing.generacmobilelink.account.offline.configuration-error.invalid-credentials";
            case MFA_UNSUPPORTED -> "@text/thing.generacmobilelink.account.offline.configuration-error.mfa-unsupported";
            case INTERACTION_REQUIRED ->
                "@text/thing.generacmobilelink.account.offline.configuration-error.interaction-required";
            default -> "@text/thing.generacmobilelink.account.offline.configuration-error.login-rejected [\""
                    + String.valueOf(e.getMessage()).replace('"', '\'') + "\"]";
        };
        updateStatus(generation, ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, description);
        stopPollFrom(generation);
    }

    /**
     * Takes the bridge offline only after {@link #MAX_CONSECUTIVE_POLL_FAILURES} polls in a row have failed.
     */
    private void handlePollFailure(IOException e, int generation) {
        consecutivePollFailures++;
        if (consecutivePollFailures < MAX_CONSECUTIVE_POLL_FAILURES) {
            logger.debug("Poll failed ({} of {} tolerated before going offline): {}", consecutivePollFailures,
                    MAX_CONSECUTIVE_POLL_FAILURES, e.getMessage());
            return;
        }
        logger.debug("Could not update devices", e);
        String description = "@text/thing.generacmobilelink.account.offline.communication-error.io-exception";
        if (e instanceof OAuthErrorException oauthError) {
            // Unlike a network error this may not heal by itself (a wrong system clock, a client Generac
            // retired), so say what Auth0 answered
            if (consecutivePollFailures == MAX_CONSECUTIVE_POLL_FAILURES) {
                logger.warn("MobileLink login keeps failing: {}", e.getMessage());
            }
            description = "@text/thing.generacmobilelink.account.offline.communication-error.oauth-error [\""
                    + oauthError.getError().replace('"', '\'') + "\"]";
        }
        updateStatus(generation, ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, description);
    }

    private void updateGeneratorThings(HttpClient client, String accessToken, int generation) throws IOException {
        Apparatus[] apparatuses = getEndpoint(client, accessToken, Apparatus[].class, "/v5/Apparatus/list");
        if (apparatuses == null) {
            logger.debug("Could not decode apparatuses response");
            return;
        }
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(generation, ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
        }
        for (Apparatus apparatus : apparatuses) {
            if (apparatus.type != 0) {
                logger.debug("Unknown apparatus type {} {}", apparatus.type, apparatus.name);
                continue;
            }

            String id = String.valueOf(apparatus.apparatusId);
            apparatusesCache.put(id, apparatus);

            Optional<Thing> thing = getThing().getThings().stream().filter(
                    t -> t.getConfiguration().as(GeneracMobileLinkGeneratorConfiguration.class).generatorId.equals(id))
                    .findFirst();
            if (thing.isEmpty()) {
                discoveryService.generatorDiscovered(apparatus, getThing().getUID());
            } else {
                ThingHandler handler = thing.get().getHandler();
                if (handler != null) {
                    updateGeneratorThing(client, accessToken, handler, apparatus);
                }
            }
        }
    }

    private void updateGeneratorThing(HttpClient client, String accessToken, ThingHandler handler, Apparatus apparatus)
            throws IOException {
        ApparatusDetail detail = getEndpoint(client, accessToken, ApparatusDetail.class,
                "/v5/Apparatus/details/" + apparatus.apparatusId);
        if (detail != null) {
            ((GeneracMobileLinkGeneratorHandler) handler).updateGeneratorStatus(apparatus, detail);
        } else {
            logger.debug("Could not decode apparatuses detail response");
        }
    }

    private @Nullable <T> T getEndpoint(HttpClient client, String accessToken, Class<T> clazz, String endpoint)
            throws IOException {
        try {
            ContentResponse response = client.newRequest(apiBase + endpoint)
                    .timeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .header(HttpHeader.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeader.ACCEPT, "application/json").agent(Auth0Client.USER_AGENT_APP).send();
            int status = response.getStatus();
            if (status == 204) {
                // no data
                return null;
            }
            if (status == 401) {
                // The next poll refreshes the token
                authSession.invalidateAccessToken();
            }
            if (status != 200) {
                throw new IOException("API returned status code: " + status);
            }
            String data = response.getContentAsString();
            logger.trace("getEndpoint {}", data);
            return GSON.fromJson(data, clazz);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (TimeoutException | ExecutionException | JsonSyntaxException e) {
            throw new IOException(e);
        }
    }
}
