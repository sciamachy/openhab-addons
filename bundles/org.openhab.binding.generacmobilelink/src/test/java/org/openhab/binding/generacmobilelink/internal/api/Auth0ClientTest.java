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

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.HttpCookieStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.generacmobilelink.internal.api.AuthException.Reason;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests {@link Auth0Client} against a fake Auth0 tenant that checks what the real one is known to check: the
 * login cookie, the PKCE verifier, the DPoP proof and its nonce, and the key thumbprint committed to at authorize.
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public class Auth0ClientTest {
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "secret";
    private static final String OTP = "123456";
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    private final FakeAuth0 auth0 = new FakeAuth0();
    private @Nullable HttpServer server;
    private @Nullable HttpClient httpClient;
    private @Nullable Auth0Client client;

    @BeforeEach
    public void setUp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", auth0::handle);
        server.start();
        this.server = server;
        auth0.base = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClient httpClient = new HttpClient();
        httpClient.setFollowRedirects(false);
        httpClient.setCookieStore(new HttpCookieStore.Empty());
        httpClient.start();
        this.httpClient = httpClient;
        client = new Auth0Client(httpClient, auth0.base, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    public void tearDown() throws Exception {
        HttpClient httpClient = this.httpClient;
        if (httpClient != null) {
            httpClient.stop();
        }
        HttpServer server = this.server;
        if (server != null) {
            server.stop(0);
        }
    }

    private Auth0Client client() {
        Auth0Client client = this.client;
        assertNotNull(client);
        return client;
    }

    @Test
    public void loginWithoutMfa() throws Exception {
        TokenSet tokens = client().login(EMAIL, PASSWORD);

        assertEquals("access-1", tokens.accessToken());
        assertEquals("refresh-1", tokens.refreshToken());
        assertEquals(NOW.plus(Duration.ofSeconds(7200)), tokens.expiresAt());
        assertEquals(auth0.dpopJkt, tokens.key().thumbprint());
        assertEquals(List.of("/authorize", "/u/login/identifier", "/u/login/password", "/authorize/resume",
                "/oauth/token", "/oauth/token"), auth0.paths);
    }

    @Test
    public void unknownEmailIsInvalidCredentials() {
        AuthException e = assertThrows(AuthException.class, () -> client().login("nobody@example.com", PASSWORD));
        assertEquals(Reason.INVALID_CREDENTIALS, e.getReason());
    }

    @Test
    public void wrongPasswordIsInvalidCredentials() {
        AuthException e = assertThrows(AuthException.class, () -> client().login(EMAIL, "wrong"));
        assertEquals(Reason.INVALID_CREDENTIALS, e.getReason());
        assertFalse(auth0.paths.contains("/oauth/token"));
    }

    @Test
    public void blockedAccountIsLoginRejected() {
        auth0.passwordErrorCode = "too-many-failures";
        AuthException e = assertThrows(AuthException.class, () -> client().login(EMAIL, "wrong"));
        assertEquals(Reason.LOGIN_REJECTED, e.getReason());
    }

    @Test
    public void serverErrorIsIOException() {
        auth0.failPath = "/u/login/password";
        assertThrows(IOException.class, () -> client().login(EMAIL, PASSWORD));
    }

    @Test
    public void otpChallengePausesAndResumes() throws Exception {
        auth0.mfaType = "otp";
        MfaRequiredException mfa = assertThrows(MfaRequiredException.class, () -> client().login(EMAIL, PASSWORD));
        PendingLogin pending = mfa.getPendingLogin();
        assertEquals("otp", pending.getMfaType());
        assertEquals(NOW, pending.getCreatedAt());
        assertFalse(auth0.paths.contains("/oauth/token"));

        TokenSet tokens = client().submitMfaCode(pending, OTP);

        assertEquals("access-1", tokens.accessToken());
        assertEquals(auth0.dpopJkt, tokens.key().thumbprint());
    }

    @Test
    public void challengePageIsLoadedBeforePausing() throws Exception {
        auth0.mfaType = "email";
        assertThrows(MfaRequiredException.class, () -> client().login(EMAIL, PASSWORD));
        assertEquals("GET /u/mfa-email-challenge", auth0.requests.get(auth0.requests.size() - 1));
    }

    @Test
    public void codeErrorOtherThanAWrongCodeEndsTheLogin() throws Exception {
        auth0.mfaType = "otp";
        auth0.mfaErrorCode = "too-many-failures";
        PendingLogin pending = assertThrows(MfaRequiredException.class, () -> client().login(EMAIL, PASSWORD))
                .getPendingLogin();
        AuthException e = assertThrows(AuthException.class, () -> client().submitMfaCode(pending, "000000"));
        assertEquals(Reason.LOGIN_REJECTED, e.getReason());
    }

    @Test
    public void browserCapabilityCheckIsAnswered() throws Exception {
        auth0.mfaType = "otp";
        auth0.detectCapabilities = true;
        PendingLogin pending = assertThrows(MfaRequiredException.class, () -> client().login(EMAIL, PASSWORD))
                .getPendingLogin();
        assertEquals("otp", pending.getMfaType());
        assertEquals("false", auth0.webauthnAnswer);
    }

    @Test
    public void absoluteRedirectsAreFollowed() throws Exception {
        auth0.absoluteRedirects = true;
        assertEquals("access-1", client().login(EMAIL, PASSWORD).accessToken());
    }

    @Test
    public void callbackWithErrorIsLoginRejected() {
        auth0.callbackError = "access_denied";
        AuthException e = assertThrows(AuthException.class, () -> client().login(EMAIL, PASSWORD));
        assertEquals(Reason.LOGIN_REJECTED, e.getReason());
        assertFalse(auth0.paths.contains("/oauth/token"));
    }

    @Test
    public void firewallPageIsIOException() {
        auth0.authorizeStatus = 403;
        assertThrows(IOException.class, () -> client().login(EMAIL, PASSWORD));
    }

    @Test
    public void wrongCodeCanBeRetriedOnTheSameLogin() throws Exception {
        auth0.mfaType = "sms";
        PendingLogin pending = assertThrows(MfaRequiredException.class, () -> client().login(EMAIL, PASSWORD))
                .getPendingLogin();
        assertEquals("sms", pending.getMfaType());

        AuthException e = assertThrows(AuthException.class, () -> client().submitMfaCode(pending, "000000"));
        assertEquals(Reason.INVALID_MFA_CODE, e.getReason());

        assertEquals("access-1", client().submitMfaCode(pending, OTP).accessToken());
    }

    @Test
    public void unsupportedFactor() {
        auth0.mfaType = "push";
        AuthException e = assertThrows(AuthException.class, () -> client().login(EMAIL, PASSWORD));
        assertEquals(Reason.MFA_UNSUPPORTED, e.getReason());
    }

    @Test
    public void customPromptIsAccepted() throws Exception {
        auth0.customPrompt = true;
        assertEquals("access-1", client().login(EMAIL, PASSWORD).accessToken());
        assertTrue(auth0.paths.contains("/u/custom-prompt/terms"));
    }

    @Test
    public void customPromptThatNeedsAPerson() {
        auth0.customPrompt = true;
        auth0.customPromptAcceptable = false;
        AuthException e = assertThrows(AuthException.class, () -> client().login(EMAIL, PASSWORD));
        assertEquals(Reason.INTERACTION_REQUIRED, e.getReason());
    }

    @Test
    public void foreignStateInCallbackIsRejected() {
        auth0.callbackState = "forged";
        assertThrows(IOException.class, () -> client().login(EMAIL, PASSWORD));
    }

    @Test
    public void refreshKeepsTheRefreshTokenWhenNotRotated() throws Exception {
        DPoPKey key = DPoPKey.generate();
        TokenSet tokens = client().refresh("refresh-1", key);
        assertEquals("access-2", tokens.accessToken());
        assertEquals("refresh-1", tokens.refreshToken());
        assertSame(key, tokens.key());
    }

    @Test
    public void refreshUsesARotatedRefreshToken() throws Exception {
        auth0.rotateRefreshToken = true;
        assertEquals("refresh-2", client().refresh("refresh-1", DPoPKey.generate()).refreshToken());
    }

    @Test
    public void invalidGrantAs403IsInvalidGrant() {
        auth0.refreshStatus = 403;
        AuthException e = assertThrows(AuthException.class, () -> client().refresh("refresh-1", DPoPKey.generate()));
        assertEquals(Reason.INVALID_GRANT, e.getReason());
    }

    @Test
    public void otherTokenErrorsKeepTheRefreshToken() {
        // Only invalid_grant means the refresh token is gone; anything else must not force a new login
        auth0.refreshStatus = 403;
        auth0.refreshError = "access_denied";
        assertThrows(IOException.class, () -> client().refresh("refresh-1", DPoPKey.generate()));
        auth0.refreshStatus = 400;
        auth0.refreshError = "invalid_request";
        assertThrows(IOException.class, () -> client().refresh("refresh-1", DPoPKey.generate()));
    }

    @Test
    public void refreshServerErrorIsIOException() {
        auth0.failPath = "/oauth/token";
        assertThrows(IOException.class, () -> client().refresh("refresh-1", DPoPKey.generate()));
    }

    /**
     * Just enough of Auth0 Universal Login for the flows above. Fails requests the real tenant would fail.
     */
    private static class FakeAuth0 {
        String base = "";
        final List<String> paths = new ArrayList<>();
        final List<String> requests = new ArrayList<>();
        String mfaErrorCode = "invalid-code";
        boolean detectCapabilities;
        boolean capabilitiesDetected;
        @Nullable
        String webauthnAnswer;
        boolean absoluteRedirects;
        @Nullable
        String callbackError;
        int authorizeStatus = 302;
        String refreshError = "invalid_grant";
        @Nullable
        String mfaType;
        boolean customPrompt;
        boolean customPromptAcceptable = true;
        boolean rotateRefreshToken;
        int refreshStatus = 200;
        String passwordErrorCode = "wrong-credentials";
        @Nullable
        String failPath;
        @Nullable
        String callbackState;

        @Nullable
        String dpopJkt;
        @Nullable
        String codeChallenge;
        @Nullable
        String oauthState;
        boolean mfaPassed;
        boolean promptPassed;
        String nonce = "n-1";

        void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                paths.add(path);
                requests.add(exchange.getRequestMethod() + " " + path);
                if (path.equals(failPath)) {
                    respond(exchange, 503, "unavailable");
                } else if ("/authorize".equals(path) && authorizeStatus != 302) {
                    respond(exchange, authorizeStatus, "<html>Request blocked</html>");
                } else if ("/authorize".equals(path)) {
                    Map<String, String> query = Auth0Client.query(exchange.getRequestURI());
                    assertEquals(Auth0Client.CLIENT_ID, query.get("client_id"));
                    assertEquals("S256", query.get("code_challenge_method"));
                    dpopJkt = query.get("dpop_jkt");
                    codeChallenge = query.get("code_challenge");
                    oauthState = query.get("state");
                    exchange.getResponseHeaders().add("Set-Cookie", "auth0=session; Path=/; HttpOnly");
                    redirect(exchange, "/u/login/identifier?state=s-identifier");
                } else if ("/u/login/identifier".equals(path)) {
                    Map<String, String> form = form(exchange, "s-identifier");
                    redirect(exchange, EMAIL.equals(form.get("username")) ? "/u/login/password?state=s-password"
                            : "/u/login/identifier?state=s-identifier");
                } else if ("/u/login/password".equals(path)) {
                    Map<String, String> form = form(exchange, "s-password");
                    if (PASSWORD.equals(form.get("password"))) {
                        redirect(exchange, "/authorize/resume?state=s-resume");
                    } else {
                        respond(exchange, 400, "<span data-error-code=\"" + passwordErrorCode + "\"></span>");
                    }
                } else if ("/authorize/resume".equals(path)) {
                    requireCookie(exchange);
                    String mfaType = this.mfaType;
                    String error = callbackError;
                    if (error != null) {
                        redirect(exchange, Auth0Client.REDIRECT_URI + "?error=" + error + "&state=" + oauthState);
                    } else if (detectCapabilities && !capabilitiesDetected) {
                        redirect(exchange, "/u/mfa-detect-browser-capabilities?state=s-detect");
                    } else if (mfaType != null && !mfaPassed) {
                        redirect(exchange, "/u/mfa-" + mfaType + "-challenge?state=s-mfa");
                    } else if (customPrompt && !promptPassed) {
                        redirect(exchange, "/u/custom-prompt/terms?state=s-prompt");
                    } else {
                        String state = callbackState;
                        redirect(exchange, Auth0Client.REDIRECT_URI + "?code=the-code&state="
                                + (state != null ? state : oauthState));
                    }
                } else if ("/u/mfa-detect-browser-capabilities".equals(path)) {
                    Map<String, String> form = form(exchange, "s-detect");
                    webauthnAnswer = form.get("webauthn-available");
                    capabilitiesDetected = true;
                    redirect(exchange, "/authorize/resume?state=s-resume4");
                } else if (path.startsWith("/u/mfa-") && "GET".equals(exchange.getRequestMethod())) {
                    requireCookie(exchange);
                    respond(exchange, 200, "<form>enter the code</form>");
                } else if (path.startsWith("/u/mfa-")) {
                    Map<String, String> form = form(exchange, "s-mfa");
                    if (OTP.equals(form.get("code"))) {
                        mfaPassed = true;
                        redirect(exchange, "/authorize/resume?state=s-resume2");
                    } else {
                        respond(exchange, 400, "<span data-error-code=\"" + mfaErrorCode + "\"></span>");
                    }
                } else if ("/u/custom-prompt/terms".equals(path)) {
                    form(exchange, "s-prompt");
                    if (customPromptAcceptable) {
                        promptPassed = true;
                        redirect(exchange, "/authorize/resume?state=s-resume3");
                    } else {
                        respond(exchange, 200, "<form>please verify your email</form>");
                    }
                } else if ("/oauth/token".equals(path)) {
                    token(exchange);
                } else {
                    respond(exchange, 404, "not found");
                }
            } catch (AssertionError | Exception e) {
                respond(exchange, 418, "fake Auth0 refused the request: " + e);
            }
        }

        private void token(HttpExchange exchange) throws Exception {
            JsonObject body = JsonParser
                    .parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String proof = exchange.getRequestHeaders().getFirst("DPoP");
            assertNotNull(proof);
            JsonObject claims = verifyProof(proof, base + "/oauth/token");
            if (!claims.has("nonce")) {
                exchange.getResponseHeaders().add("DPoP-Nonce", nonce);
                respond(exchange, 400, "{\"error\":\"use_dpop_nonce\"}");
                return;
            }
            assertEquals(nonce, claims.get("nonce").getAsString());
            switch (body.get("grant_type").getAsString()) {
                case "authorization_code" -> {
                    assertEquals("the-code", body.get("code").getAsString());
                    assertEquals(Auth0Client.REDIRECT_URI, body.get("redirect_uri").getAsString());
                    byte[] verifier = body.get("code_verifier").getAsString().getBytes(StandardCharsets.US_ASCII);
                    assertEquals(codeChallenge,
                            Base64.getUrlEncoder().withoutPadding().encodeToString(DPoPKey.sha256(verifier)));
                    respond(exchange, 200,
                            "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\",\"expires_in\":7200,\"token_type\":\"Bearer\"}");
                }
                case "refresh_token" -> {
                    assertEquals("refresh-1", body.get("refresh_token").getAsString());
                    if (refreshStatus != 200) {
                        respond(exchange, refreshStatus, "{\"error\":\"" + refreshError
                                + "\",\"error_description\":\"Unknown or invalid refresh token.\"}");
                    } else {
                        respond(exchange, 200, "{\"access_token\":\"access-2\",\"expires_in\":7200"
                                + (rotateRefreshToken ? ",\"refresh_token\":\"refresh-2\"" : "") + "}");
                    }
                }
                default -> respond(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
            }
        }

        /**
         * Checks the proof's signature against the key in its header and, for logins, that the key is the one the
         * authorization request committed to.
         */
        private JsonObject verifyProof(String proof, String url) throws Exception {
            String[] parts = proof.split("\\.");
            assertEquals(3, parts.length);
            Base64.Decoder decoder = Base64.getUrlDecoder();
            JsonObject header = JsonParser.parseString(new String(decoder.decode(parts[0]), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals("dpop+jwt", header.get("typ").getAsString());
            assertEquals("ES256", header.get("alg").getAsString());
            JsonObject jwk = header.getAsJsonObject("jwk");
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(
                            new ECPoint(new BigInteger(1, decoder.decode(jwk.get("x").getAsString())),
                                    new BigInteger(1, decoder.decode(jwk.get("y").getAsString()))),
                            parameters.getParameterSpec(ECParameterSpec.class)));
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            assertTrue(signature.verify(decoder.decode(parts[2])), "DPoP signature");
            JsonObject claims = JsonParser.parseString(new String(decoder.decode(parts[1]), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals("POST", claims.get("htm").getAsString());
            assertEquals(url, claims.get("htu").getAsString());
            String jkt = dpopJkt;
            if (jkt != null) {
                String canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + jwk.get("x").getAsString()
                        + "\",\"y\":\"" + jwk.get("y").getAsString() + "\"}";
                assertEquals(jkt, Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(DPoPKey.sha256(canonical.getBytes(StandardCharsets.US_ASCII))));
            }
            return claims;
        }

        private Map<String, String> form(HttpExchange exchange, String expectedState) throws IOException {
            assertEquals("POST", exchange.getRequestMethod());
            requireCookie(exchange);
            assertEquals(expectedState, Auth0Client.query(exchange.getRequestURI()).get("state"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = Auth0Client.query(URI.create("x:/?" + body));
            assertEquals(expectedState, form.get("state"));
            assertEquals("default", form.get("action"));
            return form;
        }

        private void requireCookie(HttpExchange exchange) {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            assertNotNull(cookie, "login cookie");
            assertTrue(cookie.contains("auth0=session"), "login cookie");
        }

        private void redirect(HttpExchange exchange, String location) throws IOException {
            exchange.getResponseHeaders().add("Location",
                    absoluteRedirects && location.startsWith("/") ? base + location : location);
            respond(exchange, 302, "");
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        }
    }
}
