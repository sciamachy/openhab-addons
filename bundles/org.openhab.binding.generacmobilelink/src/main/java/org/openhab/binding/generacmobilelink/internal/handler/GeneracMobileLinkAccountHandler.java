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
import java.util.LinkedHashMap;
import java.util.List;
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
import org.openhab.core.storage.Storage;
import org.openhab.core.storage.StorageService;
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
    private static final String STORAGE_KEY_SESSION_COOKIE = "sessionCookie";
    /**
     * How many polls in a row have to fail before the bridge is taken offline. The MobileLink cloud sits behind a
     * WAF that intermittently throttles or resets requests, so a single failed poll is the normal condition rather
     * than an outage and almost always heals on the next one.
     */
    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 3;

    private static final String API_BASE = "https://app.mobilelinkgen.com/api";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(ZonedDateTime.class, (JsonDeserializer<ZonedDateTime>) (json, type,
                    jsonDeserializationContext) -> ZonedDateTime.parse(json.getAsJsonPrimitive().getAsString()))
            .create();
    private final HttpClientFactory httpClientFactory;
    private volatile HttpClient httpClient;
    private GeneracMobileLinkDiscoveryService discoveryService;
    private Storage<String> storage;
    private Map<String, Apparatus> apparatusesCache = new HashMap<>();
    private int refreshIntervalSeconds = 60;
    private boolean cookieConfigured;
    private String sessionCookie = "";
    private int consecutivePollFailures;

    private @Nullable Future<?> pollFuture;

    public GeneracMobileLinkAccountHandler(Bridge bridge, HttpClientFactory httpClientFactory,
            GeneracMobileLinkDiscoveryService discoveryService, StorageService storageService) {
        super(bridge);
        this.discoveryService = discoveryService;
        // Create storage unique to this thing instance (so multiple accounts work)
        this.storage = storageService.getStorage(
                GeneracMobileLinkBindingConstants.BINDING_ID + "." + bridge.getUID().getAsString().replace(":", "_"));
        this.httpClientFactory = httpClientFactory;
        httpClient = createHttpClient();
    }

    /**
     * Creates, configures and starts a new HTTP client. All client level settings live here so that a client
     * created outside of the constructor is configured identically.
     *
     * @throws IllegalStateException if the client could not be started
     */
    private HttpClient createHttpClient() {
        HttpClient client = httpClientFactory.createHttpClient(GeneracMobileLinkBindingConstants.BINDING_ID);
        client.setFollowRedirects(true);
        // We have to send a very large amount of cookies which exceeds the default buffer size
        client.setRequestBufferSize(32768);
        try {
            client.start();
        } catch (Exception e) {
            throw new IllegalStateException("Error starting custom HttpClient", e);
        }
        return client;
    }

    @Override
    public void initialize() {
        updateStatus(ThingStatus.UNKNOWN);
        consecutivePollFailures = 0;
        // dispose() stops the HTTP client, but openHAB reuses the same handler instance when a DSL model is
        // reloaded, which is exactly what happens when the thing file is edited to enter a fresh session
        // cookie. Without this the handler would come back with a stopped client and every request would fail.
        if (!httpClient.isRunning()) {
            try {
                httpClient = createHttpClient();
            } catch (IllegalStateException e) {
                logger.warn("Could not start HTTP client", e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "@text/thing.generacmobilelink.account.offline.configuration-error.http-client");
                return;
            }
        }
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
            consecutivePollFailures = 0;
        } catch (IOException e) {
            handleTransientPollFailure(e);
        } catch (SessionExpiredException e) {
            logger.debug("Session expired", e);
            // Clear the stored cookie so the binding falls back to thing config on next init
            storage.remove(STORAGE_KEY_SESSION_COOKIE);
            logger.info("Cleared stored session cookie - update thing config with a fresh cookie");
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
     * Handles a communication failure that may well be transient. The bridge is only taken offline once
     * {@link #MAX_CONSECUTIVE_POLL_FAILURES} polls in a row have failed, because going offline on the first one
     * drags every child generator thing to BRIDGE_OFFLINE for a single missed poll and makes the bridge status
     * useless as a health signal. Deterministic failures (expired cookie, missing cookie) are not routed here and
     * still go offline immediately.
     */
    private void handleTransientPollFailure(Exception e) {
        consecutivePollFailures++;
        if (consecutivePollFailures < MAX_CONSECUTIVE_POLL_FAILURES) {
            logger.warn("Poll failed ({} of {} tolerated before going offline): {}", consecutivePollFailures,
                    MAX_CONSECUTIVE_POLL_FAILURES, e.getMessage());
            logger.debug("Poll failure details", e);
            return;
        }
        logger.debug("Could not update devices", e);
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                "@text/thing.generacmobilelink.account.offline.communication-error.io-exception");
    }

    /**
     * Initializes the session cookie. First checks persistent storage for a previously
     * saved cookie (from auto-renewal), then falls back to thing configuration.
     *
     * @throws MissingCookieException if no session cookie is available
     */
    private synchronized void initializeCookie() throws MissingCookieException {
        logger.debug("Initializing session cookie");
        GeneracMobileLinkAccountConfiguration config = getConfigAs(GeneracMobileLinkAccountConfiguration.class);
        refreshIntervalSeconds = config.refreshInterval;

        // First, check storage for a previously saved cookie (from auto-renewal)
        String storedCookie = storage.get(STORAGE_KEY_SESSION_COOKIE);
        if (storedCookie != null && !storedCookie.isBlank()) {
            sessionCookie = storedCookie;
            cookieConfigured = true;
            logger.info("Session cookie loaded from persistent storage");
            return;
        }

        // Fall back to thing configuration (initial setup)
        if (config.sessionCookie.isBlank()) {
            throw new MissingCookieException("Session cookie is not configured");
        }

        sessionCookie = config.sessionCookie;
        cookieConfigured = true;
        logger.info("Session cookie loaded from thing configuration");
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
                    .header("Accept-Language", "en-US,en;q=0.9").followRedirects(false);

            ContentResponse response = request.send();

            // Capture any Set-Cookie headers to keep session alive
            captureResponseCookies(response);

            int status = response.getStatus();
            if (status == 204) {
                // no data
                return null;
            }
            if (status == 401 || status == 403 || (status >= 300 && status < 400)) {
                throw new SessionExpiredException("Session cookie expired or invalid (HTTP " + status + ")");
            }
            if (status != 200) {
                // Anything else (429 throttling, 5xx, WAF error pages) says nothing about the session, so treat it
                // as a communication failure. Routing it through SessionExpiredException would wipe the stored
                // cookie and stop the poll loop over a blip that heals on its own.
                throw new IOException("API returned status code: " + status);
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

    /**
     * Captures Set-Cookie headers from an API response and merges them into the session cookie.
     * This mimics browser behavior where cookies are automatically updated on each response.
     */
    private void captureResponseCookies(ContentResponse response) {
        List<String> setCookieHeaders = response.getHeaders().getValuesList("Set-Cookie");
        if (setCookieHeaders.isEmpty()) {
            return;
        }

        logger.info("Received {} Set-Cookie header(s) from server", setCookieHeaders.size());

        // Parse current cookies into a map
        Map<String, String> cookieMap = parseCookieString(sessionCookie);

        // Parse and merge each Set-Cookie header
        boolean updated = false;
        for (String setCookie : setCookieHeaders) {
            String[] parts = setCookie.split(";", 2);
            if (parts.length > 0 && parts[0].contains("=")) {
                String[] nameValue = parts[0].split("=", 2);
                if (nameValue.length == 2) {
                    String name = nameValue[0].trim();
                    String value = nameValue[1].trim();
                    String oldValue = cookieMap.get(name);
                    if (oldValue == null || !oldValue.equals(value)) {
                        logger.info("Updating cookie: {}", name);
                        cookieMap.put(name, value);
                        updated = true;
                    }
                }
            }
        }

        if (updated) {
            // Rebuild cookie string
            String newCookieString = buildCookieString(cookieMap);
            sessionCookie = newCookieString;
            logger.info("Session cookie updated with {} cookies from server response", cookieMap.size());

            // Persist to storage so it survives restarts
            persistCookie(newCookieString);

            // Fire trigger channel so rules can react
            triggerChannel(GeneracMobileLinkBindingConstants.CHANNEL_COOKIE_UPDATED);
        }
    }

    /**
     * Parses a cookie header string (name1=value1; name2=value2) into a map.
     */
    private Map<String, String> parseCookieString(String cookieString) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (cookieString.isBlank()) {
            return cookies;
        }
        for (String part : cookieString.split(";")) {
            String trimmed = part.trim();
            int eqIdx = trimmed.indexOf('=');
            if (eqIdx > 0) {
                String name = trimmed.substring(0, eqIdx).trim();
                String value = trimmed.substring(eqIdx + 1).trim();
                cookies.put(name, value);
            }
        }
        return cookies;
    }

    /**
     * Builds a cookie header string from a map of cookies.
     */
    private String buildCookieString(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * Persists the updated cookie to storage so it survives restarts.
     * This is separate from the thing configuration file, which remains unchanged.
     */
    private void persistCookie(String newCookieString) {
        try {
            storage.put(STORAGE_KEY_SESSION_COOKIE, newCookieString);
            logger.info("Persisted updated session cookie to storage");
        } catch (Exception e) {
            logger.debug("Could not persist cookie to storage: {}", e.getMessage());
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
