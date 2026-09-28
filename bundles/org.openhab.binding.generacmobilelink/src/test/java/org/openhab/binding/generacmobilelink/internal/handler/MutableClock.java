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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * A clock that tests move forward by hand.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
        now = start;
    }

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
