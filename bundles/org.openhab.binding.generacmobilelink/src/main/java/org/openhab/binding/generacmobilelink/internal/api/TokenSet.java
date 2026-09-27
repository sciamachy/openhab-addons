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

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The tokens of a MobileLink login.
 *
 * @param accessToken the bearer token for the MobileLink API
 * @param expiresAt when the access token expires
 * @param refreshToken the refresh token, which Auth0 does not rotate for this client
 * @param key the DPoP key the login was bound to
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public record TokenSet(String accessToken, Instant expiresAt, String refreshToken, DPoPKey key) {
    @Override
    public String toString() {
        return "TokenSet[expiresAt=" + expiresAt + "]";
    }
}
