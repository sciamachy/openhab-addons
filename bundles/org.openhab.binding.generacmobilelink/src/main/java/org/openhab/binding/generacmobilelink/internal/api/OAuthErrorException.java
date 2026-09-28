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

import java.io.IOException;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The token endpoint answered with an OAuth error other than {@code invalid_grant}, for example
 * {@code invalid_dpop_proof} after a clock jump. The refresh token is kept, since such errors can be temporary, but
 * the error code is worth showing to the user, unlike a plain communication error.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public class OAuthErrorException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String error;

    public OAuthErrorException(String error, String message) {
        super(message);
        this.error = error;
    }

    /**
     * @return the OAuth error code, such as {@code invalid_dpop_proof}
     */
    public String getError() {
        return error;
    }
}
