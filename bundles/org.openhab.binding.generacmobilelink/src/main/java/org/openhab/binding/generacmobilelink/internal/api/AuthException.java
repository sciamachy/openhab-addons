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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Auth0 refused a login or a token refresh for a reason that retrying will not fix.
 *
 * Failures that may heal on their own (network errors, 5xx, throttling) are reported as
 * {@link java.io.IOException} instead.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public class AuthException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** Unknown email address or wrong password. */
        INVALID_CREDENTIALS,
        /** Auth0 rejected the login form for another reason, for example a blocked account. */
        LOGIN_REJECTED,
        /** The one-time code was wrong or has expired. The pending login can take another code. */
        INVALID_MFA_CODE,
        /** The account uses a second factor that cannot be completed without a browser or phone. */
        MFA_UNSUPPORTED,
        /** Auth0 shows a page that needs a person, for example new terms to accept. */
        INTERACTION_REQUIRED,
        /** The refresh token is no longer valid. A new login is needed. */
        INVALID_GRANT
    }

    private final Reason reason;

    public AuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
