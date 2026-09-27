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
import static org.openhab.binding.generacmobilelink.internal.handler.AuthSession.*;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.generacmobilelink.internal.api.Auth0Client;
import org.openhab.binding.generacmobilelink.internal.api.AuthException;
import org.openhab.binding.generacmobilelink.internal.api.AuthException.Reason;
import org.openhab.binding.generacmobilelink.internal.api.DPoPKey;
import org.openhab.binding.generacmobilelink.internal.api.MfaRequiredException;
import org.openhab.binding.generacmobilelink.internal.api.PendingLogin;
import org.openhab.binding.generacmobilelink.internal.api.TokenSet;
import org.openhab.binding.generacmobilelink.internal.config.GeneracMobileLinkAccountConfiguration;
import org.openhab.core.test.storage.VolatileStorage;

/**
 * Tests for {@link AuthSession}.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public class AuthSessionTest {
    private static final Instant START = Instant.parse("2026-09-27T12:00:00Z");
    private static final DPoPKey KEY = DPoPKey.generate();

    private final MutableClock clock = new MutableClock();
    private VolatileStorage<String> storage = new VolatileStorage<>();
    private AuthSession session = new AuthSession(storage, clock);
    private Auth0Client client = mock(Auth0Client.class);
    private GeneracMobileLinkAccountConfiguration config = new GeneracMobileLinkAccountConfiguration();

    @BeforeEach
    public void setUp() {
        storage = new VolatileStorage<>();
        session = new AuthSession(storage, clock);
        client = mock(Auth0Client.class);
        config = new GeneracMobileLinkAccountConfiguration();
        config.username = "user@example.com";
        config.password = "secret";
    }

    private TokenSet tokens(String accessToken) {
        return new TokenSet(accessToken, clock.instant().plus(Duration.ofHours(2)), "refresh-1", KEY);
    }

    private PendingLogin pending(String mfaType) {
        PendingLogin pending = mock(PendingLogin.class);
        when(pending.getMfaType()).thenReturn(mfaType);
        when(pending.getCreatedAt()).thenReturn(clock.instant());
        return pending;
    }

    @Test
    public void logsInOnceAndPersistsTheRefreshToken() throws Exception {
        when(client.login("user@example.com", "secret")).thenReturn(tokens("access-1"));

        assertEquals("access-1", session.getAccessToken(client, config));
        assertEquals("access-1", session.getAccessToken(client, config));

        verify(client, times(1)).login(any(), any());
        assertEquals("refresh-1", storage.get(KEY_REFRESH_TOKEN));
        assertEquals("user@example.com", storage.get(KEY_USERNAME));
        String storedKey = storage.get(KEY_DPOP_KEY);
        assertNotNull(storedKey);
        assertEquals(KEY.thumbprint(), DPoPKey.decode(storedKey).thumbprint());
    }

    @Test
    public void refreshesBeforeExpiry() throws Exception {
        when(client.login(any(), any())).thenReturn(tokens("access-1"));
        session.getAccessToken(client, config);
        clock.advance(Duration.ofHours(2).minus(EXPIRY_MARGIN));
        when(client.refresh("refresh-1", KEY)).thenReturn(tokens("access-2"));

        assertEquals("access-2", session.getAccessToken(client, config));
        verify(client, times(1)).login(any(), any());
    }

    @Test
    public void restartUsesTheStoredRefreshToken() throws Exception {
        when(client.login(any(), any())).thenReturn(tokens("access-1"));
        session.getAccessToken(client, config);
        when(client.refresh(eq("refresh-1"), any())).thenReturn(tokens("access-2"));

        AuthSession restarted = new AuthSession(storage, clock);

        assertEquals("access-2", restarted.getAccessToken(client, config));
        verify(client, times(1)).login(any(), any());
    }

    @Test
    public void invalidGrantLogsInAgain() throws Exception {
        when(client.login(any(), any())).thenReturn(tokens("access-1"), tokens("access-3"));
        session.getAccessToken(client, config);
        session.invalidateAccessToken();
        when(client.refresh(any(), any())).thenThrow(new AuthException(Reason.INVALID_GRANT, "expired"));

        assertEquals("access-3", session.getAccessToken(client, config));
        verify(client, times(2)).login(any(), any());
    }

    @Test
    public void refreshCommunicationErrorKeepsTheRefreshToken() throws Exception {
        when(client.login(any(), any())).thenReturn(tokens("access-1"));
        session.getAccessToken(client, config);
        session.invalidateAccessToken();
        when(client.refresh(any(), any())).thenThrow(new IOException("down"));

        assertThrows(IOException.class, () -> session.getAccessToken(client, config));
        assertEquals("refresh-1", storage.get(KEY_REFRESH_TOKEN));
        verify(client, times(1)).login(any(), any());
    }

    @Test
    public void anotherUsernameDiscardsTheStoredTokens() throws Exception {
        when(client.login(any(), any())).thenReturn(tokens("access-1"));
        session.getAccessToken(client, config);

        config.username = "other@example.com";
        AuthSession restarted = new AuthSession(storage, clock);
        restarted.getAccessToken(client, config);

        verify(client, never()).refresh(any(), any());
        verify(client).login("other@example.com", "secret");
        assertEquals("other@example.com", storage.get(KEY_USERNAME));
    }

    @Test
    public void mfaWaitsForACodeWithoutLoggingInAgain() throws Exception {
        PendingLogin pending = pending("sms");
        MfaRequiredException pendingMfa = new MfaRequiredException(pending);
        when(client.login(any(), any())).thenThrow(pendingMfa);

        MfaCodeNeededException e = assertThrows(MfaCodeNeededException.class,
                () -> session.getAccessToken(client, config));
        assertEquals("sms", e.getMfaType());
        assertEquals(MfaCodeNeededException.State.WAITING, e.getState());
        // Polls while waiting must not start new logins, which would send a new SMS each time
        assertThrows(MfaCodeNeededException.class, () -> session.getAccessToken(client, config));
        verify(client, times(1)).login(any(), any());

        config.mfaCode = " 123 456 ";
        when(client.submitMfaCode(pending, "123456")).thenReturn(tokens("access-1"));
        assertEquals("access-1", session.getAccessToken(client, config));
        verify(client, times(1)).login(any(), any());
    }

    @Test
    public void codeEnteredWithThePasswordIsSubmittedRightAway() throws Exception {
        PendingLogin pending = pending("otp");
        MfaRequiredException pendingMfa = new MfaRequiredException(pending);
        when(client.login(any(), any())).thenThrow(pendingMfa);
        when(client.submitMfaCode(pending, "123456")).thenReturn(tokens("access-1"));
        config.mfaCode = "123456";

        assertEquals("access-1", session.getAccessToken(client, config));
    }

    @Test
    public void rejectedCodeIsNotSubmittedTwice() throws Exception {
        PendingLogin pending = pending("otp");
        MfaRequiredException pendingMfa = new MfaRequiredException(pending);
        when(client.login(any(), any())).thenThrow(pendingMfa);
        when(client.submitMfaCode(pending, "111111"))
                .thenThrow(new AuthException(Reason.INVALID_MFA_CODE, "invalid-code"));
        config.mfaCode = "111111";

        assertEquals(MfaCodeNeededException.State.REJECTED,
                assertThrows(MfaCodeNeededException.class, () -> session.getAccessToken(client, config)).getState());
        assertEquals(MfaCodeNeededException.State.WAITING,
                assertThrows(MfaCodeNeededException.class, () -> session.getAccessToken(client, config)).getState());
        verify(client, times(1)).submitMfaCode(any(), any());

        config.mfaCode = "222222";
        when(client.submitMfaCode(pending, "222222")).thenReturn(tokens("access-1"));
        assertEquals("access-1", session.getAccessToken(client, config));
        verify(client, times(1)).login(any(), any());
    }

    @Test
    public void usedCodeIsNotReusedForALaterLogin() throws Exception {
        PendingLogin first = pending("otp");
        MfaRequiredException firstMfa = new MfaRequiredException(first);
        when(client.login(any(), any())).thenThrow(firstMfa);
        when(client.submitMfaCode(first, "123456")).thenReturn(tokens("access-1"));
        config.mfaCode = "123456";
        session.getAccessToken(client, config);

        // The refresh token dies; the stale code stays in the configuration
        session.invalidateAccessToken();
        when(client.refresh(any(), any())).thenThrow(new AuthException(Reason.INVALID_GRANT, "revoked"));
        PendingLogin second = pending("otp");
        MfaRequiredException secondMfa = new MfaRequiredException(second);
        doThrow(secondMfa).when(client).login(any(), any());

        assertThrows(MfaCodeNeededException.class, () -> session.getAccessToken(client, config));
        verify(client, never()).submitMfaCode(same(second), any());
    }

    @Test
    public void expiredPendingLoginWaitsForTheConfigurationToBeSaved() throws Exception {
        PendingLogin first = pending("sms");
        PendingLogin second = pending("sms");
        MfaRequiredException firstMfa = new MfaRequiredException(first);
        MfaRequiredException secondMfa = new MfaRequiredException(second);
        when(client.login(any(), any())).thenThrow(firstMfa, secondMfa);
        assertThrows(MfaCodeNeededException.class, () -> session.getAccessToken(client, config));

        // Nobody entered the code: no new login (and no new SMS) by itself, however long it waits
        for (int i = 0; i < 5; i++) {
            clock.advance(PENDING_LOGIN_LIFETIME.plusSeconds(1));
            assertEquals(MfaCodeNeededException.State.EXPIRED,
                    assertThrows(MfaCodeNeededException.class, () -> session.getAccessToken(client, config))
                            .getState());
        }
        verify(client, times(1)).login(any(), any());

        // Saving the configuration with a code starts over and submits it to the new login
        config.mfaCode = "123456";
        session.configurationApplied();
        when(client.submitMfaCode(second, "123456")).thenReturn(tokens("access-1"));

        assertEquals("access-1", session.getAccessToken(client, config));
        verify(client, never()).submitMfaCode(same(first), any());
    }

    @Test
    public void failedLoginsBackOff() throws Exception {
        when(client.login(any(), any())).thenThrow(new IOException("down"));
        assertThrows(IOException.class, () -> session.getAccessToken(client, config));
        verify(client, times(1)).login(any(), any());

        // Polls within the backoff do not log in
        clock.advance(MIN_LOGIN_BACKOFF.minusSeconds(1));
        assertThrows(IOException.class, () -> session.getAccessToken(client, config));
        verify(client, times(1)).login(any(), any());

        clock.advance(Duration.ofSeconds(1));
        assertThrows(IOException.class, () -> session.getAccessToken(client, config));
        verify(client, times(2)).login(any(), any());

        // The wait doubles
        clock.advance(MIN_LOGIN_BACKOFF);
        assertThrows(IOException.class, () -> session.getAccessToken(client, config));
        verify(client, times(2)).login(any(), any());
        clock.advance(MIN_LOGIN_BACKOFF);
        assertThrows(IOException.class, () -> session.getAccessToken(client, config));
        verify(client, times(3)).login(any(), any());

        // ...up to the maximum
        for (int i = 0; i < 10; i++) {
            clock.advance(MAX_LOGIN_BACKOFF);
            assertThrows(IOException.class, () -> session.getAccessToken(client, config));
        }
        verify(client, times(13)).login(any(), any());

        // Saving the configuration allows a login at once, and success resets the wait
        session.configurationApplied();
        doReturn(tokens("access-1")).when(client).login(any(), any());
        assertEquals("access-1", session.getAccessToken(client, config));
        verify(client, times(14)).login(any(), any());
    }

    @Test
    public void communicationErrorAfterTheCodeDropsTheSpentLogin() throws Exception {
        PendingLogin first = pending("otp");
        PendingLogin second = pending("otp");
        MfaRequiredException firstMfa = new MfaRequiredException(first);
        MfaRequiredException secondMfa = new MfaRequiredException(second);
        when(client.login(any(), any())).thenThrow(firstMfa, secondMfa);
        when(client.submitMfaCode(first, "123456")).thenThrow(new IOException("reset"));
        config.mfaCode = "123456";

        assertThrows(IOException.class, () -> session.getAccessToken(client, config));
        clock.advance(MIN_LOGIN_BACKOFF);

        // A new login; the code already sent is not resubmitted to it
        assertEquals(MfaCodeNeededException.State.WAITING,
                assertThrows(MfaCodeNeededException.class, () -> session.getAccessToken(client, config)).getState());
        verify(client, times(2)).login(any(), any());
        verify(client, never()).submitMfaCode(same(second), any());
    }

    @Test
    public void changedPasswordStartsOver() throws Exception {
        PendingLogin first = pending("otp");
        MfaRequiredException firstMfa = new MfaRequiredException(first);
        when(client.login(any(), any())).thenThrow(firstMfa);
        assertThrows(MfaCodeNeededException.class, () -> session.getAccessToken(client, config));

        config.password = "new-secret";
        assertThrows(MfaCodeNeededException.class, () -> session.getAccessToken(client, config));
        verify(client).login("user@example.com", "new-secret");
    }

    @Test
    public void refusedLoginIsPassedOn() throws Exception {
        when(client.login(any(), any())).thenThrow(new AuthException(Reason.INVALID_CREDENTIALS, "wrong"));
        assertEquals(Reason.INVALID_CREDENTIALS,
                assertThrows(AuthException.class, () -> session.getAccessToken(client, config)).getReason());
    }

    @Test
    public void resetForgetsEverything() throws Exception {
        when(client.login(any(), any())).thenReturn(tokens("access-1"));
        session.getAccessToken(client, config);

        session.reset();

        assertNull(storage.get(KEY_REFRESH_TOKEN));
        assertNull(storage.get(KEY_DPOP_KEY));
        assertNull(storage.get(KEY_USERNAME));
        assertNull(session.getCachedAccessToken());
    }

    @Test
    public void cachedAccessTokenOnlyWhileValid() throws Exception {
        assertNull(session.getCachedAccessToken());
        when(client.login(any(), any())).thenReturn(tokens("access-1"));
        session.getAccessToken(client, config);
        assertEquals("access-1", session.getCachedAccessToken());
        clock.advance(Duration.ofHours(2));
        assertNull(session.getCachedAccessToken());
    }

    private static class MutableClock extends Clock {
        private Instant now = START;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(@NonNullByDefault({}) ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
