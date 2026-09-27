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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * A login waits for a one-time code.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
class MfaCodeNeededException extends Exception {
    private static final long serialVersionUID = 1L;

    enum State {
        /** No code configured yet. */
        WAITING,
        /** The configured code was not accepted. */
        REJECTED,
        /** No code arrived in time; saving the configuration starts a new login. */
        EXPIRED
    }

    private final String mfaType;
    private final State state;

    MfaCodeNeededException(String mfaType, State state) {
        super("One-time code (" + mfaType + ") needed: " + state);
        this.mfaType = mfaType;
        this.state = state;
    }

    /**
     * @return {@code otp}, {@code sms} or {@code email}
     */
    String getMfaType() {
        return mfaType;
    }

    State getState() {
        return state;
    }
}
