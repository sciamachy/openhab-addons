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
import static org.openhab.binding.generacmobilelink.internal.GeneracMobileLinkBindingConstants.*;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.generacmobilelink.internal.dto.Apparatus;
import org.openhab.binding.generacmobilelink.internal.dto.ApparatusDetail;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.State;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;

/**
 * Tests {@link GeneracMobileLinkGeneratorHandler} against the shape of today's API responses.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public class GeneracMobileLinkGeneratorHandlerTest {
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(ZonedDateTime.class, (JsonDeserializer<ZonedDateTime>) (json, type,
                    context) -> ZonedDateTime.parse(json.getAsJsonPrimitive().getAsString()))
            .create();

    /** A /api/v5/Apparatus/details response of 2026-09-27, cut down to the fields the handler reads. */
    private static final String DETAIL = """
            {
              "apparatusId": 1,
              "activationDate": "2018-03-28T00:00:00Z",
              "deviceSsid": "MLG00000",
              "apparatusStatus": 1,
              "heroImageUrl": "https://example.com/image.jpg",
              "statusLabel": "Ready to run",
              "statusText": "Your generator is ready to run.",
              "isConnected": true,
              "isConnecting": false,
              "showWarning": false,
              "hasMaintenanceAlert": false,
              "lastSeen": "2026-09-27T05:02:29.122+00:00",
              "connectionTimestamp": "2026-01-27T20:51:31.599+00:00",
              "properties": [
                { "name": "Battery Voltage", "value": "14.0", "type": 70 },
                { "name": "Engine Hours", "value": 259, "type": 71 },
                { "name": "Hours of Protection", "value": 74520.0, "type": 32 }
              ]
            }
            """;

    private static final String APPARATUS = """
            {
              "apparatusId": 1,
              "name": "Generator",
              "type": 0,
              "properties": [
                { "name": "Device", "type": 3, "value": { "deviceType": "eth", "signalStrength": "46%" } }
              ]
            }
            """;

    @Test
    public void propertiesAreReadFromTheirCurrentTypeIds() {
        GeneracMobileLinkGeneratorHandler handler = new GeneracMobileLinkGeneratorHandler(
                ThingBuilder.create(THING_TYPE_GENERATOR, "1").build());
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        Map<String, State> states = new HashMap<>();
        doAnswer(invocation -> {
            states.put(((ChannelUID) invocation.getArgument(0)).getId(), invocation.getArgument(1));
            return null;
        }).when(callback).stateUpdated(any(), any());
        handler.setCallback(callback);

        Apparatus apparatus = GSON.fromJson(APPARATUS, Apparatus.class);
        ApparatusDetail detail = GSON.fromJson(DETAIL, ApparatusDetail.class);
        assertNotNull(apparatus);
        assertNotNull(detail);
        handler.updateGeneratorStatus(apparatus, detail);

        assertEquals(new QuantityType<>(259, Units.HOUR), states.get(CHANNEL_RUN_HOURS));
        assertEquals(new QuantityType<>(14.0, Units.VOLT), states.get(CHANNEL_BATTERY_VOLTAGE));
        assertEquals(new QuantityType<>(74520, Units.HOUR), states.get(CHANNEL_HOURS_OF_PROTECTION));
        assertEquals(new QuantityType<>(46, Units.PERCENT), states.get(CHANNEL_SIGNAL_STRENGH));
    }
}
