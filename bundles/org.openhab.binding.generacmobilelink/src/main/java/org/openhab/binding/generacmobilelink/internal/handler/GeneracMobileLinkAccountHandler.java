/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.openhab.binding.generacmobilelink.internal.GeneracMobileLinkBindingConstants;
import org.openhab.binding.generacmobilelink.internal.config.GeneracMobileLinkAccountConfiguration;
import org.openhab.binding.generacmobilelink.internal.config.GeneracMobileLinkGeneratorConfiguration;
import org.openhab.binding.generacmobilelink.internal.discovery.GeneracMobileLinkDiscoveryService;
import org.openhab.binding.generacmobilelink.internal.dto.Apparatus;
import org.openhab.binding.generacmobilelink.internal.dto.ApparatusDetail;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link GeneracMobileLinkAccountHandler} is responsible for connecting to the MobileLink cloud service and
 * discovering generator things.
 *
 * Authentication is done via a session cookie that the user obtains by manually logging in via a web browser.
 * This approach is necessary because Generac added CAPTCHA protection to their login flow.
 *
 * @author Dan Cunningham - Initial contribution
 */
@NonNullByDefault
public class GeneracMobileLinkAccountHandler extends BaseBridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(GeneracMobileLinkAccountHandler.class);
    private static final int REQUEST_TIMEOUT_MS = 10_000;

    private static final String API_BASE = "https://app.mobilelinkgen.com/api";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(ZonedDateTime.class, (JsonDeserializer<ZonedDateTime>) (json, type,
                    jsonDeserializationContext) -> ZonedDateTime.parse(json.getAsJsonPrimitive().getAsString()))
            .create();
    private HttpClient httpClient;
    private GeneracMobileLinkDiscoveryService discoveryService;
    private Map<String, Apparatus> apparatusesCache = new HashMap<>();
    private int refreshIntervalSeconds = 60;
    private boolean cookieConfigured;
    private String sessionCookie = "";

    private @Nullable Future<?> pollFuture;

    public GeneracMobileLinkAccountHandler(Bridge bridge, HttpClientFactory httpClientFactory,
            GeneracMobileLinkDiscoveryService discoveryService) {
        super(bridge);
        this.discoveryService = discoveryService;
        httpClient = httpClientFactory.createHttpClient(GeneracMobileLinkBindingConstants.BINDING_ID);
        httpClient.setFollowRedirects(true);
        // We have to send a very large amount of cookies which exceeds the default buffer size
        httpClient.setRequestBufferSize(32768);
        try {
            httpClient.start();
        } catch (Exception e) {
            throw new IllegalStateException("Error starting custom HttpClient", e);
        }
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);
        stopOrRestartPoll(true);
    }

    @Override
    public void dispose() {
        stopOrRestartPoll(false);
        cookieConfigured = false;
        sessionCookie = "";
        try {
            httpClient.stop();
        } catch (Exception e) {
            logger.debug("Could not stop HttpClient", e);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            try {
                updateGeneratorThings();
            } catch (IOException | SessionExpiredException e) {
                logger.debug("Could refresh things", e);
            }
        }
    }

    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        logger.debug("childHandlerInitialized {}", childThing.getUID());
        String id = childThing.getConfiguration().as(GeneracMobileLinkGeneratorConfiguration.class).generatorId;
        Apparatus apparatus = apparatusesCache.get(id);
        if (apparatus == null) {
            logger.debug("No device for id {}", id);
            return;
        }
        try {
            updateGeneratorThing(childHandler, apparatus);
        } catch (IOException | SessionExpiredException e) {
            logger.debug("Could not initialize child", e);
        }
    }

    private synchronized void stopOrRestartPoll(boolean restart) {
        Future<?> pollFuture = this.pollFuture;
        if (pollFuture != null) {
            pollFuture.cancel(true);
            this.pollFuture = null;
        }
        if (restart) {
            this.pollFuture = scheduler.scheduleWithFixedDelay(this::poll, 1, refreshIntervalSeconds, TimeUnit.SECONDS);
        }
    }

    private void poll() {
        try {
            if (!cookieConfigured) {
                initializeCookie();
            }
            updateGeneratorThings();
        } catch (IOException e) {
            logger.debug("Could not update devices", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/thing.generacmobilelink.account.offline.communication-error.io-exception");
        } catch (SessionExpiredException e) {
            logger.debug("Session expired", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/thing.generacmobilelink.account.offline.communication-error.session-expired");
            cookieConfigured = false;
            // Stop polling since the user needs to manually refresh the cookie
            stopOrRestartPoll(false);
        } catch (MissingCookieException e) {
            logger.debug("Cookie not configured", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/thing.generacmobilelink.account.offline.configuration-error.missing-cookie");
            cookieConfigured = false;
            // Stop polling since the configuration is invalid
            stopOrRestartPoll(false);
        }
    }

    /**
     * Initializes the session cookie from configuration.
     *
     * @throws MissingCookieException if the session cookie is not configured or empty
     */
    private synchronized void initializeCookie() throws MissingCookieException {
        logger.debug("Initializing session cookie from configuration");
        GeneracMobileLinkAccountConfiguration config = getConfigAs(GeneracMobileLinkAccountConfiguration.class);
        refreshIntervalSeconds = config.refreshInterval;

        if (config.sessionCookie.isBlank()) {
            throw new MissingCookieException("Session cookie is not configured");
        }

        sessionCookie = config.sessionCookie;
        cookieConfigured = true;
        logger.debug("Session cookie configured successfully");
    }

    private void updateGeneratorThings() throws IOException, SessionExpiredException {
        Apparatus[] apparatuses = getEndpoint(Apparatus[].class, "/v2/Apparatus/list");
        if (apparatuses == null) {
            logger.debug("Could not decode apparatuses response");
            return;
        }
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
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
                    updateGeneratorThing(handler, apparatus);
                }
            }
        }
    }

    private void updateGeneratorThing(ThingHandler handler, Apparatus apparatus)
            throws IOException, SessionExpiredException {
        ApparatusDetail detail = getEndpoint(ApparatusDetail.class, "/v1/Apparatus/details/" + apparatus.apparatusId);
        if (detail != null) {
            ((GeneracMobileLinkGeneratorHandler) handler).updateGeneratorStatus(apparatus, detail);
        } else {
            logger.debug("Could not decode apparatuses detail response");
        }
    }

    private @Nullable <T> T getEndpoint(Class<T> clazz, String endpoint) throws IOException, SessionExpiredException {
        try {
            Request request = httpClient.newRequest(API_BASE + endpoint)
                    .timeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS).header("Cookie", sessionCookie)
                    .header("User-Agent", USER_AGENT).header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "en-US,en;q=0.9");

            ContentResponse response = request.send();
            if (response.getStatus() == 204) {
                // no data
                return null;
            }
            if (response.getStatus() == 401 || response.getStatus() == 403) {
                throw new SessionExpiredException(
                        "Session cookie expired or invalid (HTTP " + response.getStatus() + ")");
            }
            if (response.getStatus() != 200) {
                throw new SessionExpiredException("API returned status code: " + response.getStatus());
            }
            String data = response.getContentAsString();
            logger.debug("getEndpoint {}", data);
            return GSON.fromJson(data, clazz);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (TimeoutException | ExecutionException | JsonSyntaxException e) {
            throw new IOException(e);
        }
    }

    private class MissingCookieException extends Exception {
        private static final long serialVersionUID = 1L;

        public MissingCookieException(String message) {
            super(message);
        }
    }

    private class SessionExpiredException extends Exception {
        private static final long serialVersionUID = 1L;

        public SessionExpiredException(String message) {
            super(message);
        }
    }
}
