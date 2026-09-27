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
 * The password was accepted and Auth0 now asks for a one-time code. The login is paused in {@link #getPendingLogin()}
 * and is finished with {@link Auth0Client#submitMfaCode(PendingLogin, String)}.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public class MfaRequiredException extends Exception {
    private static final long serialVersionUID = 1L;

    private final transient PendingLogin pendingLogin;

    public MfaRequiredException(PendingLogin pendingLogin) {
        super("One-time code required (" + pendingLogin.getMfaType() + ")");
        this.pendingLogin = pendingLogin;
    }

    public PendingLogin getPendingLogin() {
        return pendingLogin;
    }
}
