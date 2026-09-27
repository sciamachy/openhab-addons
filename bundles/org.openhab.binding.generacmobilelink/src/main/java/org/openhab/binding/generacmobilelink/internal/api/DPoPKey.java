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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * An EC P-256 key used to sign DPoP proofs (RFC 9449) for the Auth0 token endpoint.
 *
 * The key is part of the login: the authorization request commits to its thumbprint, so it has to be kept together
 * with the refresh token.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public final class DPoPKey {
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PrivateKey privateKey;
    private final ECPublicKey publicKey;
    private final String jwk;
    private final String thumbprint;

    private DPoPKey(PrivateKey privateKey, ECPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        String x = B64URL.encodeToString(unsigned32(publicKey.getW().getAffineX().toByteArray()));
        String y = B64URL.encodeToString(unsigned32(publicKey.getW().getAffineY().toByteArray()));
        // RFC 7638: required members only, in lexicographic order, without whitespace
        this.jwk = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + x + "\",\"y\":\"" + y + "\"}";
        this.thumbprint = B64URL.encodeToString(sha256(jwk.getBytes(StandardCharsets.US_ASCII)));
    }

    public static DPoPKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            KeyPair pair = generator.generateKeyPair();
            return new DPoPKey(pair.getPrivate(), (ECPublicKey) pair.getPublic());
        } catch (GeneralSecurityException e) {
            // P-256 is a mandatory JCA algorithm
            throw new IllegalStateException("EC P-256 is not available", e);
        }
    }

    /**
     * Restores a key saved with {@link #encode()}.
     *
     * @throws IllegalArgumentException if the value is not a key saved by this class
     */
    public static DPoPKey decode(String encoded) {
        try {
            JsonObject json = JsonParser.parseString(encoded).getAsJsonObject();
            KeyFactory factory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(json.get("private").getAsString())));
            ECPublicKey publicKey = (ECPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(json.get("public").getAsString())));
            return new DPoPKey(privateKey, publicKey);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("Not a saved DPoP key", e);
        }
    }

    /**
     * @return the key pair as a string for storage. It contains the private key.
     */
    public String encode() {
        JsonObject json = new JsonObject();
        json.addProperty("private", Base64.getEncoder().encodeToString(privateKey.getEncoded()));
        json.addProperty("public", Base64.getEncoder().encodeToString(publicKey.getEncoded()));
        return json.toString();
    }

    /**
     * @return the base64url SHA-256 JWK thumbprint (RFC 7638), sent as {@code dpop_jkt} when authorizing
     */
    public String thumbprint() {
        return thumbprint;
    }

    ECPublicKey publicKey() {
        return publicKey;
    }

    /**
     * Creates a DPoP proof JWT for one request.
     *
     * @param method the HTTP method of the request
     * @param url the request URL without query or fragment
     * @param nonce the nonce the server asked for, if any
     */
    public String proof(String method, String url, @Nullable String nonce) {
        JsonObject payload = new JsonObject();
        payload.addProperty("jti", UUID.randomUUID().toString());
        payload.addProperty("htm", method);
        payload.addProperty("htu", url);
        payload.addProperty("iat", Instant.now().getEpochSecond());
        if (nonce != null) {
            payload.addProperty("nonce", nonce);
        }
        String header = "{\"alg\":\"ES256\",\"typ\":\"dpop+jwt\",\"jwk\":" + jwk + "}";
        String input = B64URL.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + B64URL.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        try {
            // JWS ES256 wants the raw R||S signature, not the DER encoding the plain algorithm produces
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey);
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + B64URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign DPoP proof", e);
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Left-pads or strips the sign byte of a BigInteger encoding to the 32 bytes a P-256 coordinate needs. */
    private static byte[] unsigned32(byte[] value) {
        byte[] out = new byte[32];
        int length = Math.min(value.length, 32);
        System.arraycopy(value, value.length - length, out, 32 - length, length);
        return out;
    }
}
