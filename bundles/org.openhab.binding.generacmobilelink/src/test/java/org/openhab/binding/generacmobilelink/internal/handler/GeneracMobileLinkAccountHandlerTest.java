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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.openhab.binding.generacmobilelink.internal.GeneracMobileLinkBindingConstants.THING_TYPE_ACCOUNT;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openhab.binding.generacmobilelink.internal.api.Auth0Client;
import org.openhab.binding.generacmobilelink.internal.api.AuthException;
import org.openhab.binding.generacmobilelink.internal.api.AuthException.Reason;
import org.openhab.binding.generacmobilelink.internal.api.DPoPKey;
import org.openhab.binding.generacmobilelink.internal.api.MfaRequiredException;
import org.openhab.binding.generacmobilelink.internal.api.OAuthErrorException;
import org.openhab.binding.generacmobilelink.internal.api.PendingLogin;
import org.openhab.binding.generacmobilelink.internal.api.TokenSet;
import org.openhab.binding.generacmobilelink.internal.discovery.GeneracMobileLinkDiscoveryService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.test.storage.VolatileStorage;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.BridgeBuilder;

import com.sun.net.httpserver.HttpServer;

/**
 * Tests {@link GeneracMobileLinkAccountHandler} with a real {@link AuthSession}, a mocked {@link Auth0Client}, a fake
 * MobileLink API and an executor that runs a poll only when the test says so.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public class GeneracMobileLinkAccountHandlerTest {
    private static final String TEXT = "@text/thing.generacmobilelink.account.offline.";
    private static final DPoPKey KEY = DPoPKey.generate();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-28T12:00:00Z"));
    private final List<ThingStatusInfo> statuses = new ArrayList<>();
    private final List<Runnable> polls = new ArrayList<>();
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final List<HttpClient> httpClients = new ArrayList<>();
    private final List<String> apiAuthorizations = new ArrayList<>();
    private volatile int apiStatus = 200;

    private Auth0Client auth0 = mock(Auth0Client.class);
    private AuthSession session = new AuthSession(new VolatileStorage<>(), clock);
    private @Nullable HttpServer api;
    private @Nullable GeneracMobileLinkAccountHandler handler;
    private ScheduledExecutorService executor = mock(ScheduledExecutorService.class);

    @BeforeEach
    public void setUp() throws Exception {
        auth0 = mock(Auth0Client.class);
        session = new AuthSession(new VolatileStorage<>(), clock);
        HttpServer api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        api.createContext("/api/v5/Apparatus/list", exchange -> {
            apiAuthorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "[{\"apparatusId\":1,\"name\":\"Generator\",\"type\":0}]".getBytes(StandardCharsets.UTF_8);
            int status = apiStatus;
            exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        api.start();
        this.api = api;
    }

    @AfterEach
    public void tearDown() throws Exception {
        GeneracMobileLinkAccountHandler handler = this.handler;
        if (handler != null) {
            handler.dispose();
        }
        for (HttpClient client : httpClients) {
            client.stop();
        }
        HttpServer api = this.api;
        if (api != null) {
            api.stop(0);
        }
    }

    private Bridge bridge(String mfaCode) {
        Map<String, Object> config = new HashMap<>();
        config.put("username", "user@example.com");
        config.put("password", "secret");
        config.put("mfaCode", mfaCode);
        return BridgeBuilder.create(THING_TYPE_ACCOUNT, "test").withConfiguration(new Configuration(config)).build();
    }

    private GeneracMobileLinkAccountHandler start(Bridge bridge) {
        HttpServer api = this.api;
        assertNotNull(api);
        HttpClientFactory httpClientFactory = mock(HttpClientFactory.class);
        when(httpClientFactory.createHttpClient(anyString())).thenAnswer(invocation -> {
            HttpClient client = new HttpClient();
            httpClients.add(client);
            return client;
        });
        executor = mock(ScheduledExecutorService.class);
        when(executor.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            polls.add(invocation.getArgument(0));
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            futures.add(future);
            return future;
        });
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        doAnswer(invocation -> {
            ThingStatusInfo info = invocation.getArgument(1);
            ((Thing) invocation.getArgument(0)).setStatusInfo(info);
            statuses.add(info);
            return null;
        }).when(callback).statusUpdated(any(), any());
        GeneracMobileLinkAccountHandler handler = new GeneracMobileLinkAccountHandler(bridge, httpClientFactory,
                mock(GeneracMobileLinkDiscoveryService.class), session, client -> auth0,
                "http://127.0.0.1:" + api.getAddress().getPort() + "/api", executor);
        handler.setCallback(callback);
        handler.initialize();
        this.handler = handler;
        return handler;
    }

    private void poll() {
        polls.get(polls.size() - 1).run();
    }

    private ThingStatusInfo lastStatus() {
        return statuses.get(statuses.size() - 1);
    }

    private void assertStatus(ThingStatus status, ThingStatusDetail detail, @Nullable String description) {
        ThingStatusInfo last = lastStatus();
        assertEquals(status, last.getStatus());
        assertEquals(detail, last.getStatusDetail());
        assertEquals(description, last.getDescription());
    }

    private long offlineCount() {
        return statuses.stream().filter(s -> s.getStatus() == ThingStatus.OFFLINE).count();
    }

    private TokenSet tokens() {
        return new TokenSet("access-1", clock.instant().plus(Duration.ofHours(2)), "refresh-1", KEY);
    }

    private PendingLogin pending(String mfaType) {
        PendingLogin pending = mock(PendingLogin.class);
        when(pending.getMfaType()).thenReturn(mfaType);
        when(pending.getCreatedAt()).thenReturn(clock.instant());
        return pending;
    }

    // ---- configuration and first poll ----------------------------------------------------------------------------

    @Test
    public void missingCredentialsIsAConfigurationError() {
        Map<String, Object> config = new HashMap<>();
        config.put("username", "user@example.com");
        start(BridgeBuilder.create(THING_TYPE_ACCOUNT, "test").withConfiguration(new Configuration(config)).build());

        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                TEXT + "configuration-error.missing-credentials");
        assertTrue(polls.isEmpty());
        assertTrue(httpClients.isEmpty());
    }

    @Test
    public void pollLogsInAndGoesOnline() throws Exception {
        when(auth0.login("user@example.com", "secret")).thenReturn(tokens());
        start(bridge(""));
        assertStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, null);

        poll();

        assertStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
        assertEquals(List.of("Bearer access-1"), apiAuthorizations);
    }

    // ---- poll-failure tolerance ----------------------------------------------------------------------------------

    @Test
    public void thirdFailedPollInARowGoesOffline() throws Exception {
        when(auth0.login(any(), any())).thenReturn(tokens());
        start(bridge(""));
        poll();
        assertStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);

        apiStatus = 500;
        poll();
        poll();
        assertEquals(0, offlineCount());
        assertEquals(ThingStatus.ONLINE, lastStatus().getStatus());

        poll();
        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                TEXT + "communication-error.io-exception");
        verify(futures.get(0), never()).cancel(anyBoolean());

        // A good poll resets the count
        apiStatus = 200;
        poll();
        assertStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
        apiStatus = 500;
        poll();
        poll();
        assertEquals(1, offlineCount());
        assertEquals(ThingStatus.ONLINE, lastStatus().getStatus());
    }

    @Test
    public void unexpectedExceptionIsCountedAndDoesNotEndTheSchedule() throws Exception {
        when(auth0.login(any(), any())).thenThrow(new IllegalStateException("bug"));
        start(bridge(""));

        // The runnable must not throw, or the executor would never run it again
        assertDoesNotThrow(this::poll);
        assertDoesNotThrow(this::poll);
        assertEquals(0, offlineCount());
        assertDoesNotThrow(this::poll);
        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                TEXT + "communication-error.io-exception");
        verify(futures.get(0), never()).cancel(anyBoolean());
    }

    @Test
    public void rejectedAccessTokenIsRefreshedOnTheNextPoll() throws Exception {
        when(auth0.login(any(), any())).thenReturn(tokens());
        when(auth0.refresh("refresh-1", KEY))
                .thenReturn(new TokenSet("access-2", clock.instant().plus(Duration.ofHours(2)), "refresh-1", KEY));
        start(bridge(""));
        poll();

        apiStatus = 401;
        poll();
        verify(auth0, never()).refresh(any(), any());

        apiStatus = 200;
        poll();
        verify(auth0).refresh("refresh-1", KEY);
        assertEquals("Bearer access-2", apiAuthorizations.get(apiAuthorizations.size() - 1));
    }

    @Test
    public void reinitializingStartsTheFailureCountAfresh() throws Exception {
        when(auth0.login(any(), any())).thenReturn(tokens());
        GeneracMobileLinkAccountHandler handler = start(bridge(""));
        poll();
        apiStatus = 500;
        poll();
        poll();

        handler.thingUpdated(bridge(""));
        poll();

        assertEquals(0, offlineCount());
    }

    @Test
    public void oauthErrorsNameTheErrorCode() throws Exception {
        when(auth0.login(any(), any())).thenReturn(tokens());
        when(auth0.refresh(any(), any())).thenThrow(new OAuthErrorException("invalid_dpop_proof", "refresh failed"));
        start(bridge(""));
        poll();
        clock.advance(Duration.ofHours(2));

        poll();
        poll();
        assertEquals(0, offlineCount());
        poll();

        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                TEXT + "communication-error.oauth-error [\"invalid_dpop_proof\"]");
        verify(futures.get(0), never()).cancel(anyBoolean());
    }

    // ---- status texts ----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = { "otp", "sms", "email" })
    public void mfaPendingNamesWhereTheCodeComesFrom(String mfaType) throws Exception {
        MfaRequiredException mfa = new MfaRequiredException(pending(mfaType));
        when(auth0.login(any(), any())).thenThrow(mfa);
        start(bridge(""));

        poll();

        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                TEXT + "configuration-pending.mfa-" + mfaType);
        verify(futures.get(0), never()).cancel(anyBoolean());
    }

    @Test
    public void mfaExpiredAsksForAConfigurationSave() throws Exception {
        MfaRequiredException mfa = new MfaRequiredException(pending("sms"));
        when(auth0.login(any(), any())).thenThrow(mfa);
        start(bridge(""));
        poll();

        clock.advance(AuthSession.PENDING_LOGIN_LIFETIME.plusSeconds(1));
        poll();

        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                TEXT + "configuration-pending.mfa-expired");
        verify(auth0, times(1)).login(any(), any());
    }

    @Test
    public void refusedLoginsStopPollingWithAReason() throws Exception {
        Map<Reason, String> expected = Map.of(Reason.INVALID_CREDENTIALS, "invalid-credentials", Reason.MFA_UNSUPPORTED,
                "mfa-unsupported", Reason.INTERACTION_REQUIRED, "interaction-required");
        for (Map.Entry<Reason, String> entry : expected.entrySet()) {
            statuses.clear();
            polls.clear();
            futures.clear();
            auth0 = mock(Auth0Client.class);
            when(auth0.login(any(), any())).thenThrow(new AuthException(entry.getKey(), "refused"));
            GeneracMobileLinkAccountHandler handler = start(bridge(""));

            poll();

            assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    TEXT + "configuration-error." + entry.getValue());
            verify(futures.get(0)).cancel(false);
            handler.dispose();
        }
    }

    @Test
    public void loginRejectedCarriesTheReasonWithoutQuotes() throws Exception {
        when(auth0.login(any(), any())).thenThrow(new AuthException(Reason.LOGIN_REJECTED, "Auth0 \"blocked\""));
        start(bridge(""));

        poll();

        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                TEXT + "configuration-error.login-rejected [\"Auth0 'blocked'\"]");
        verify(futures.get(0)).cancel(false);
    }

    // ---- login backoff -------------------------------------------------------------------------------------------

    @Test
    public void failedLoginsBackOffAndAConfigurationSaveResetsThat() throws Exception {
        when(auth0.login(any(), any())).thenThrow(new IOException("down"));
        GeneracMobileLinkAccountHandler handler = start(bridge(""));

        poll();
        poll();
        poll();
        // Three failed polls, but only the first one tried to log in
        verify(auth0, times(1)).login(any(), any());
        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                TEXT + "communication-error.io-exception");

        clock.advance(AuthSession.MIN_LOGIN_BACKOFF);
        poll();
        verify(auth0, times(2)).login(any(), any());
        // Doubled: another MIN_LOGIN_BACKOFF is not enough
        clock.advance(AuthSession.MIN_LOGIN_BACKOFF);
        poll();
        verify(auth0, times(2)).login(any(), any());

        // Saving the configuration logs in at once
        doReturn(tokens()).when(auth0).login(any(), any());
        handler.thingUpdated(bridge(""));
        poll();
        verify(auth0, times(3)).login(any(), any());
        assertStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
    }

    @Test
    public void unchangedSaveRetriesWhileTheBridgeWaitsForTheUser() throws Exception {
        MfaRequiredException first = new MfaRequiredException(pending("sms"));
        MfaRequiredException second = new MfaRequiredException(pending("sms"));
        when(auth0.login(any(), any())).thenThrow(first, second);
        GeneracMobileLinkAccountHandler handler = start(bridge(""));
        poll();
        clock.advance(AuthSession.PENDING_LOGIN_LIFETIME.plusSeconds(1));
        poll();
        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                TEXT + "configuration-pending.mfa-expired");

        // MainUI's Save without changes
        handler.handleConfigurationUpdate(Map.of("mfaCode", ""));
        poll();

        verify(auth0, times(2)).login(any(), any());
        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                TEXT + "configuration-pending.mfa-sms");
    }

    @Test
    public void unchangedSaveOfAWorkingBridgeDoesNothing() throws Exception {
        when(auth0.login(any(), any())).thenReturn(tokens());
        GeneracMobileLinkAccountHandler handler = start(bridge(""));
        poll();

        handler.handleConfigurationUpdate(Map.of("mfaCode", ""));

        assertEquals(1, polls.size());
        verify(futures.get(0), never()).cancel(anyBoolean());
    }

    @Test
    public void removalStopsPollingBeforeDeletingTheTokens() throws Exception {
        VolatileStorage<String> storage = new VolatileStorage<>();
        session = new AuthSession(storage, clock);
        when(auth0.login(any(), any())).thenReturn(tokens());
        GeneracMobileLinkAccountHandler handler = start(bridge(""));
        poll();
        assertNotNull(storage.get(AuthSession.KEY_REFRESH_TOKEN));

        handler.handleRemoval();

        verify(futures.get(0)).cancel(true);
        assertNull(storage.get(AuthSession.KEY_REFRESH_TOKEN));
        assertNull(storage.get(AuthSession.KEY_DPOP_KEY));
    }

    // ---- generation guard and lifecycle ----------------------------------------------------------------------------

    @Test
    public void stalePollNeitherUpdatesStatusNorCancelsTheNewSchedule() throws Exception {
        when(auth0.login(any(), any())).thenThrow(new AuthException(Reason.INVALID_CREDENTIALS, "wrong"));
        GeneracMobileLinkAccountHandler handler = start(bridge(""));
        Runnable stale = polls.get(0);

        handler.thingUpdated(bridge(""));
        assertEquals(2, polls.size());
        verify(futures.get(0)).cancel(true);
        int before = statuses.size();

        stale.run();

        assertEquals(before, statuses.size(), "stale poll updated the status");
        verify(futures.get(1), never()).cancel(anyBoolean());

        poll();
        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                TEXT + "configuration-error.invalid-credentials");
        verify(futures.get(1)).cancel(false);
    }

    @Test
    public void disposeCancelsPollingAndStopsTheHttpClient() {
        GeneracMobileLinkAccountHandler handler = start(bridge(""));
        HttpClient client = httpClients.get(0);
        assertTrue(client.isRunning());

        handler.dispose();

        verify(futures.get(0)).cancel(true);
        assertTrue(client.isStopped());
    }

    @Test
    public void reinitializingTheSameInstanceStartsAFreshHttpClient() throws Exception {
        when(auth0.login(any(), any())).thenReturn(tokens());
        GeneracMobileLinkAccountHandler handler = start(bridge(""));

        handler.thingUpdated(bridge(""));

        assertEquals(2, httpClients.size());
        assertTrue(httpClients.get(0).isStopped());
        assertTrue(httpClients.get(1).isRunning());
        poll();
        assertStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
    }

    // ---- saving the MFA code ---------------------------------------------------------------------------------------

    /**
     * {@code thingUpdated} is what openHAB calls on the same handler instance both for a UI edit of the whole thing
     * and for a reload of a .things file (ThingManagerImpl.thingUpdated, provider independent).
     */
    @Test
    public void savedCodeResumesThePendingLogin() throws Exception {
        PendingLogin pending = pending("sms");
        MfaRequiredException mfa = new MfaRequiredException(pending);
        when(auth0.login(any(), any())).thenThrow(mfa);
        when(auth0.submitMfaCode(pending, "123456")).thenReturn(tokens());
        GeneracMobileLinkAccountHandler handler = start(bridge(""));
        poll();
        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                TEXT + "configuration-pending.mfa-sms");

        handler.thingUpdated(bridge("123456"));
        poll();

        assertStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
        verify(auth0, times(1)).login(any(), any());
        verify(auth0).submitMfaCode(pending, "123456");
    }

    /**
     * MainUI's configuration dialog goes through {@code handleConfigurationUpdate} instead.
     */
    @Test
    public void codeSavedThroughTheConfigurationDialogResumesThePendingLogin() throws Exception {
        PendingLogin pending = pending("otp");
        MfaRequiredException mfa = new MfaRequiredException(pending);
        when(auth0.login(any(), any())).thenThrow(mfa);
        when(auth0.submitMfaCode(pending, "654321")).thenReturn(tokens());
        GeneracMobileLinkAccountHandler handler = start(bridge(""));
        poll();

        handler.handleConfigurationUpdate(Map.of("mfaCode", "654321"));
        poll();

        assertStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
        verify(auth0, times(1)).login(any(), any());
    }

    @Test
    public void savingAnAlreadyUsedCodeSubmitsNothing() throws Exception {
        PendingLogin pending = pending("otp");
        MfaRequiredException mfa = new MfaRequiredException(pending);
        when(auth0.login(any(), any())).thenThrow(mfa);
        when(auth0.submitMfaCode(pending, "111111"))
                .thenThrow(new AuthException(Reason.INVALID_MFA_CODE, "invalid-code"));
        GeneracMobileLinkAccountHandler handler = start(bridge("111111"));
        poll();
        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                TEXT + "configuration-pending.mfa-code-rejected");

        handler.thingUpdated(bridge("111111"));
        poll();

        verify(auth0, times(1)).submitMfaCode(any(), any());
        verify(auth0, times(1)).login(any(), any());
        assertStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                TEXT + "configuration-pending.mfa-otp");
    }

    @Test
    public void pollUsesTheConfiguredInterval() {
        Bridge bridge = bridge("");
        bridge.getConfiguration().put("refreshInterval", 900);
        start(bridge);
        verify(executor).scheduleWithFixedDelay(any(), eq(1L), eq(900L), eq(TimeUnit.SECONDS));
    }

    @Test
    public void defaultIntervalIsFiveMinutes() {
        start(bridge(""));
        verify(executor).scheduleWithFixedDelay(any(), eq(1L), eq(300L), eq(TimeUnit.SECONDS));
    }
}
