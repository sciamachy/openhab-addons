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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.generacmobilelink.internal.api.Auth0Client;
import org.openhab.binding.generacmobilelink.internal.api.AuthException;
import org.openhab.binding.generacmobilelink.internal.api.AuthException.Reason;
import org.openhab.binding.generacmobilelink.internal.api.DPoPKey;
import org.openhab.binding.generacmobilelink.internal.api.MfaRequiredException;
import org.openhab.binding.generacmobilelink.internal.api.PendingLogin;
import org.openhab.binding.generacmobilelink.internal.api.TokenSet;
import org.openhab.binding.generacmobilelink.internal.config.GeneracMobileLinkAccountConfiguration;
import org.openhab.core.storage.Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps an account logged in: hands out the current access token, refreshes it before it expires, logs in again
 * when the refresh token stops working, and holds a login that waits for a one-time code.
 *
 * Logins are rationed, because each one costs several requests to Auth0 and, on accounts with SMS or email codes,
 * sends the user a message. After a failed login the next one waits, starting at {@link #MIN_LOGIN_BACKOFF} and
 * doubling up to {@link #MAX_LOGIN_BACKOFF}. A login that waited for a code in vain is only started again after
 * the configuration was applied again, see {@link #configurationApplied()}.
 *
 * The refresh token and its DPoP key are persisted so that a restart does not need a new login, which would ask for
 * a new one-time code on accounts with MFA. They belong to the account they were issued for and are dropped when the
 * configured username changes.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
class AuthSession {
    static final String KEY_USERNAME = "username";
    static final String KEY_REFRESH_TOKEN = "refreshToken";
    static final String KEY_DPOP_KEY = "dpopKey";
    static final String KEY_USED_MFA_CODE = "usedMfaCode";
    /** How long a login waiting for a one-time code is kept. Auth0's login transaction does not live much longer. */
    static final Duration PENDING_LOGIN_LIFETIME = Duration.ofMinutes(10);
    static final Duration MIN_LOGIN_BACKOFF = Duration.ofMinutes(5);
    static final Duration MAX_LOGIN_BACKOFF = Duration.ofHours(1);
    /** Access tokens are refreshed this long before they expire. */
    static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    private final Logger logger = LoggerFactory.getLogger(AuthSession.class);
    private final Storage<String> storage;
    private final Clock clock;

    // volatile so that getCachedAccessToken() can read it without waiting for a login in progress
    private volatile @Nullable TokenSet tokens;
    private String tokensUsername = "";
    private volatile @Nullable PendingLogin pendingLogin;
    private String pendingCredentials = "";
    private Duration loginBackoff = Duration.ZERO;
    private Instant nextLoginAllowed = Instant.MIN;
    private volatile boolean configurationApplied = true;

    AuthSession(Storage<String> storage) {
        this(storage, Clock.systemUTC());
    }

    AuthSession(Storage<String> storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    /**
     * Returns a valid access token, refreshing or logging in as needed.
     *
     * @throws MfaCodeNeededException if a login waits for a one-time code that is not configured yet
     * @throws AuthException if the login was refused
     * @throws IOException on communication errors
     */
    synchronized String getAccessToken(Auth0Client client, GeneracMobileLinkAccountConfiguration config)
            throws IOException, AuthException, MfaCodeNeededException {
        TokenSet current = tokens;
        if (current == null || !tokensUsername.equals(config.username)) {
            current = loadStoredTokens(config.username);
            tokens = current;
            tokensUsername = config.username;
        }
        if (current != null && clock.instant().isBefore(current.expiresAt().minus(EXPIRY_MARGIN))) {
            return current.accessToken();
        }
        if (current != null) {
            try {
                TokenSet refreshed = client.refresh(current.refreshToken(), current.key());
                saveTokens(config.username, refreshed);
                return refreshed.accessToken();
            } catch (AuthException e) {
                if (e.getReason() != Reason.INVALID_GRANT) {
                    throw e;
                }
                logger.debug("Refresh token no longer valid, logging in again: {}", e.getMessage());
                clearTokens();
            }
        }
        TokenSet loggedIn = login(client, config);
        saveTokens(config.username, loggedIn);
        return loggedIn.accessToken();
    }

    /**
     * @return the current access token if it is still valid, without any network call or waiting for a login
     */
    @Nullable
    String getCachedAccessToken() {
        TokenSet current = tokens;
        return current != null && clock.instant().isBefore(current.expiresAt()) ? current.accessToken() : null;
    }

    /**
     * Forgets the access token after the API rejected it, so the next call refreshes it.
     */
    synchronized void invalidateAccessToken() {
        TokenSet current = tokens;
        if (current != null) {
            tokens = new TokenSet("", Instant.EPOCH, current.refreshToken(), current.key());
        }
    }

    /**
     * Tells the session that the user saved the configuration (or openHAB started): the next login may start at
     * once, even after a login that waited for a code in vain.
     */
    void configurationApplied() {
        configurationApplied = true;
    }

    /**
     * Forgets everything, including the persisted tokens. Does not wait for a login in progress, since the caller
     * (thing removal) runs after dispose() has interrupted it.
     */
    void reset() {
        clearTokens();
        pendingLogin = null;
        storage.remove(KEY_USED_MFA_CODE);
    }

    private TokenSet login(Auth0Client client, GeneracMobileLinkAccountConfiguration config)
            throws IOException, AuthException, MfaCodeNeededException {
        String credentials = config.username + "\n" + config.password;
        String code = unusedMfaCode(config.mfaCode);
        PendingLogin pending = pendingLogin;
        if (pending != null && !pendingCredentials.equals(credentials)) {
            logger.debug("Credentials changed, discarding the login that waited for a one-time code");
            pending = null;
            pendingLogin = null;
        } else if (pending != null && clock.instant().isAfter(pending.getCreatedAt().plus(PENDING_LOGIN_LIFETIME))) {
            if (!configurationApplied) {
                // Starting over by itself would send a new SMS or email every ten minutes until someone reacts
                throw new MfaCodeNeededException(pending.getMfaType(), MfaCodeNeededException.State.EXPIRED);
            }
            logger.debug("The login that waited for a one-time code has expired, starting over");
            pending = null;
            pendingLogin = null;
        }
        if (pending == null) {
            if (!configurationApplied && clock.instant().isBefore(nextLoginAllowed)) {
                throw new IOException("Last login failed, next attempt after " + nextLoginAllowed);
            }
            configurationApplied = false;
            try {
                logger.debug("Logging in to MobileLink");
                TokenSet result = client.login(config.username, config.password);
                loginSucceeded();
                return result;
            } catch (MfaRequiredException e) {
                pending = e.getPendingLogin();
                pendingLogin = pending;
                pendingCredentials = credentials;
            } catch (IOException e) {
                loginFailed();
                throw e;
            }
        }
        if (code == null) {
            throw new MfaCodeNeededException(pending.getMfaType(), MfaCodeNeededException.State.WAITING);
        }
        return submitMfaCode(client, pending, code);
    }

    private void loginSucceeded() {
        loginBackoff = Duration.ZERO;
        nextLoginAllowed = Instant.MIN;
    }

    private void loginFailed() {
        loginBackoff = loginBackoff.isZero() ? MIN_LOGIN_BACKOFF
                : loginBackoff.multipliedBy(2).compareTo(MAX_LOGIN_BACKOFF) > 0 ? MAX_LOGIN_BACKOFF
                        : loginBackoff.multipliedBy(2);
        nextLoginAllowed = clock.instant().plus(loginBackoff);
    }

    private TokenSet submitMfaCode(Auth0Client client, PendingLogin pending, String code)
            throws IOException, AuthException, MfaCodeNeededException {
        storage.put(KEY_USED_MFA_CODE, fingerprint(code));
        try {
            TokenSet result = client.submitMfaCode(pending, code);
            pendingLogin = null;
            loginSucceeded();
            return result;
        } catch (AuthException e) {
            if (e.getReason() == Reason.INVALID_MFA_CODE) {
                logger.debug("{}", e.getMessage());
                throw new MfaCodeNeededException(pending.getMfaType(), MfaCodeNeededException.State.REJECTED);
            }
            pendingLogin = null;
            throw e;
        } catch (MfaRequiredException e) {
            // A further factor after the first one; wait for its code
            PendingLogin next = e.getPendingLogin();
            pendingLogin = next;
            throw new MfaCodeNeededException(next.getMfaType(), MfaCodeNeededException.State.WAITING);
        } catch (IOException e) {
            // Whether or not Auth0 took the code, this login transaction cannot be resumed reliably
            pendingLogin = null;
            loginFailed();
            throw e;
        }
    }

    /**
     * A one-time code works once, but stays in the configuration after it was used. Remember the last code sent to
     * Auth0 so that a later login does not submit a stale code, which would count as a failed attempt.
     */
    private @Nullable String unusedMfaCode(String configured) {
        String code = configured.replaceAll("\\s", "");
        return code.isEmpty() || fingerprint(code).equals(storage.get(KEY_USED_MFA_CODE)) ? null : code;
    }

    private @Nullable TokenSet loadStoredTokens(String username) {
        String refreshToken = storage.get(KEY_REFRESH_TOKEN);
        String key = storage.get(KEY_DPOP_KEY);
        if (refreshToken == null || key == null) {
            return null;
        }
        if (!username.equals(storage.get(KEY_USERNAME))) {
            logger.debug("Stored tokens belong to another account, discarding them");
            clearTokens();
            return null;
        }
        try {
            return new TokenSet("", Instant.EPOCH, refreshToken, DPoPKey.decode(key));
        } catch (IllegalArgumentException e) {
            logger.debug("Stored DPoP key is unreadable, discarding the stored tokens", e);
            clearTokens();
            return null;
        }
    }

    private void saveTokens(String username, TokenSet newTokens) {
        tokens = newTokens;
        tokensUsername = username;
        storage.put(KEY_USERNAME, username);
        storage.put(KEY_REFRESH_TOKEN, newTokens.refreshToken());
        storage.put(KEY_DPOP_KEY, newTokens.key().encode());
    }

    private void clearTokens() {
        tokens = null;
        storage.remove(KEY_USERNAME);
        storage.remove(KEY_REFRESH_TOKEN);
        storage.remove(KEY_DPOP_KEY);
    }

    static String fingerprint(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
