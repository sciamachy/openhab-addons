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
package org.openhab.binding.generacmobilelink.internal.api;

import java.net.CookieManager;
import java.net.URI;
import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * A login that is waiting for a one-time code. It holds the Auth0 session cookies and PKCE verifier of the login
 * transaction, which only lives on Auth0's side for a limited time.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public class PendingLogin {
    private final CookieManager cookies;
    private final String codeVerifier;
    private final DPoPKey key;
    private final String oauthState;
    private final String mfaType;
    private final URI challengeUrl;
    private final String challengeState;
    private final Instant createdAt;

    PendingLogin(CookieManager cookies, String codeVerifier, DPoPKey key, String oauthState, String mfaType,
            URI challengeUrl, String challengeState, Instant createdAt) {
        this.cookies = cookies;
        this.codeVerifier = codeVerifier;
        this.key = key;
        this.oauthState = oauthState;
        this.mfaType = mfaType;
        this.challengeUrl = challengeUrl;
        this.challengeState = challengeState;
        this.createdAt = createdAt;
    }

    /**
     * @return how the code is delivered: {@code otp} (authenticator app), {@code sms} or {@code email}
     */
    public String getMfaType() {
        return mfaType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    CookieManager cookies() {
        return cookies;
    }

    String codeVerifier() {
        return codeVerifier;
    }

    DPoPKey key() {
        return key;
    }

    String oauthState() {
        return oauthState;
    }

    URI challengeUrl() {
        return challengeUrl;
    }

    String challengeState() {
        return challengeState;
    }
}
