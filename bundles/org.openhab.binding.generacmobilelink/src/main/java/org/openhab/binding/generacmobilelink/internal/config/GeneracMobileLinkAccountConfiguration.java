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
package org.openhab.binding.generacmobilelink.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link GeneracMobileLinkAccountConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Dan Cunningham - Initial contribution
 * @author Chris Harris - One-time code for Auth0 login
 */
@NonNullByDefault
public class GeneracMobileLinkAccountConfiguration {
    public String username = "";
    public String password = "";
    public String mfaCode = "";
    public Integer refreshInterval = 300;
}
