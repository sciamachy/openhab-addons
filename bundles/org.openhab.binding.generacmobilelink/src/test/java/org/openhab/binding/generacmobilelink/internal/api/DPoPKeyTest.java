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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link DPoPKey}.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public class DPoPKeyTest {
    @Test
    public void proofIsSignedByTheKeyAndCarriesTheClaims() throws Exception {
        DPoPKey key = DPoPKey.generate();
        String[] parts = key.proof("POST", "https://auth.example/oauth/token", "n-1").split("\\.");

        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initVerify(key.publicKey());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])));
        assertEquals(64, Base64.getUrlDecoder().decode(parts[2]).length);

        JsonObject claims = JsonParser
                .parseString(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("POST", claims.get("htm").getAsString());
        assertEquals("https://auth.example/oauth/token", claims.get("htu").getAsString());
        assertEquals("n-1", claims.get("nonce").getAsString());
        assertFalse(claims.get("jti").getAsString().isEmpty());
    }

    @Test
    public void proofWithoutNonceHasNoNonceClaim() {
        String payload = DPoPKey.generate().proof("POST", "https://auth.example/oauth/token", null).split("\\.")[1];
        assertFalse(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8).contains("nonce"));
    }

    @Test
    public void encodedKeyRestoresTheSameKey() {
        DPoPKey key = DPoPKey.generate();
        DPoPKey restored = DPoPKey.decode(key.encode());
        assertEquals(key.thumbprint(), restored.thumbprint());
        assertEquals(43, key.thumbprint().length());
    }

    @Test
    public void garbageIsNotAKey() {
        assertThrows(IllegalArgumentException.class, () -> DPoPKey.decode("not a key"));
        assertThrows(IllegalArgumentException.class,
                () -> DPoPKey.decode("{\"private\":\"AAAA\",\"public\":\"AAAA\"}"));
    }
}
