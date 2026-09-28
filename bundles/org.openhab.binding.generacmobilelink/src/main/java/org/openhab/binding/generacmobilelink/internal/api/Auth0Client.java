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
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.UrlEncoded;
import org.openhab.binding.generacmobilelink.internal.api.AuthException.Reason;
import org.openhab.binding.generacmobilelink.internal.dto.TokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * Logs in to MobileLink the way the Generac iOS app does: Auth0 Universal Login with email and password (and a
 * one-time code if the account uses MFA), PKCE, and a DPoP-bound authorization code exchange. The resulting access
 * token is a plain bearer token for the MobileLink API.
 *
 * Auth0 needs its session cookies during the login, and only then. Each login keeps its own cookie jar, so the
 * {@link HttpClient} passed in must not store cookies itself (see {@code HttpClient#setCookieStore}).
 *
 * @author Chris Harris - Initial contribution
 */
@NonNullByDefault
public class Auth0Client {
    private static final String AUTH0_BASE = "https://auth.ecobee.com";
    // The Generac iOS app's public client. There is no client registration for third parties.
    static final String CLIENT_ID = "eyjSuHZLjX3JC1lNmougLa8rjUw666TN";
    static final String REDIRECT_URI = "com.generac.mobilelink.auth0://auth.ecobee.com/ios/com.generac.mobilelink/callback";
    private static final String REDIRECT_PREFIX = "com.generac.mobilelink.auth0:";
    private static final String SCOPE = "openid email offline_access invoke:api";
    private static final String AUDIENCE = "https://prod.ecobee.com/api/v1";
    private static final String AUTH0_CLIENT_INFO = "{\"env\":{\"swift\":\"6.x\",\"iOS\":\"26.4\"},\"version\":\"2.16.2\",\"name\":\"Auth0.swift\"}";
    /** The User-Agent of the Generac iOS app, which the MobileLink API also sees from it. */
    public static final String USER_AGENT_APP = "mobilelink/86535 CFNetwork/3860.500.112 Darwin/25.4.0";
    private static final String USER_AGENT_WEB = "Mozilla/5.0 (iPhone; CPU iPhone OS 26_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148";

    private static final Pattern MFA_CHALLENGE_PATH = Pattern.compile(".*/u/mfa-(otp|sms|email)-challenge.*");
    private static final Pattern ERROR_CODE = Pattern.compile("data-error-code=\"([^\"]+)\"");
    private static final int REQUEST_TIMEOUT_SECONDS = 20;
    /** Custom prompts and MFA steps between the password and the final redirect, with generous headroom. */
    private static final int MAX_LOGIN_STEPS = 8;

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new Gson();

    private final Logger logger = LoggerFactory.getLogger(Auth0Client.class);
    private final HttpClient httpClient;
    private final String authBase;
    private final Clock clock;

    public Auth0Client(HttpClient httpClient) {
        this(httpClient, AUTH0_BASE, Clock.systemUTC());
    }

    Auth0Client(HttpClient httpClient, String authBase, Clock clock) {
        this.httpClient = httpClient;
        this.authBase = authBase;
        this.clock = clock;
    }

    /**
     * Logs in with email and password.
     *
     * @return the tokens
     * @throws MfaRequiredException if Auth0 asks for a one-time code; finish with
     *             {@link #submitMfaCode(PendingLogin, String)}
     * @throws AuthException if the login was refused
     * @throws IOException on communication errors and unexpected responses
     */
    public TokenSet login(String email, String password) throws IOException, AuthException, MfaRequiredException {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        DPoPKey key = DPoPKey.generate();
        String verifier = randomToken();
        String state = randomToken();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("code_challenge",
                B64URL.encodeToString(DPoPKey.sha256(verifier.getBytes(StandardCharsets.US_ASCII))));
        params.put("code_challenge_method", "S256");
        params.put("redirect_uri", REDIRECT_URI);
        params.put("scope", SCOPE);
        params.put("audience", AUDIENCE);
        params.put("state", state);
        params.put("dpop_jkt", key.thumbprint());
        params.put("client_id", CLIENT_ID);
        // Always show the login form, even if Auth0 still has a session for these cookies
        params.put("prompt", "login");
        params.put("auth0Client", B64URL.encodeToString(AUTH0_CLIENT_INFO.getBytes(StandardCharsets.UTF_8)));
        URI next = redirect(
                send(cookies, web(URI.create(authBase + "/authorize?" + queryString(params)), HttpMethod.GET)),
                "authorize");

        Fields identifier = new Fields();
        identifier.put("username", email);
        identifier.put("js-available", "true");
        // Deny WebAuthn, as at the capability check below: a headless client cannot use passkeys or security keys,
        // and saying so keeps Auth0 from offering them
        identifier.put("webauthn-available", "false");
        identifier.put("is-brave", "false");
        identifier.put("webauthn-platform-available", "false");
        next = postForm(cookies, URI.create(authBase + "/u/login/identifier"), requireState(next), identifier,
                "identifier");
        if (!path(next).endsWith("/u/login/password")) {
            // Auth0 shows the identifier page again for an unknown email address
            throw new AuthException(Reason.INVALID_CREDENTIALS, "Email address not accepted");
        }

        Fields passwordForm = new Fields();
        passwordForm.put("username", email);
        passwordForm.put("password", password);
        next = postForm(cookies, URI.create(authBase + "/u/login/password"), requireState(next), passwordForm,
                "password");
        if (!path(next).endsWith("/authorize/resume")) {
            throw new AuthException(Reason.INVALID_CREDENTIALS, "Password not accepted");
        }
        return finish(cookies, verifier, key, state, next);
    }

    /**
     * Submits the one-time code for a login that is waiting for one.
     *
     * @return the tokens
     * @throws AuthException with {@link Reason#INVALID_MFA_CODE} if the code was not accepted; the pending login can
     *             take another code
     */
    public TokenSet submitMfaCode(PendingLogin pending, String code)
            throws IOException, AuthException, MfaRequiredException {
        Fields form = new Fields();
        form.put("code", code);
        ContentResponse response = send(pending.cookies(),
                formRequest(pending.challengeUrl(), pending.challengeState(), form));
        if (!isRedirect(response.getStatus())) {
            // Auth0 renders the challenge page again, with the reason as an error code such as invalid-code. Other
            // codes (too many attempts, an expired transaction) mean this login cannot take another code.
            String error = describeError(response);
            throw new AuthException(error.contains("code") ? Reason.INVALID_MFA_CODE : Reason.LOGIN_REJECTED,
                    "One-time code not accepted (" + error + ")");
        }
        URI next = redirect(response, "mfa");
        if (MFA_CHALLENGE_PATH.matcher(path(next)).matches()) {
            throw new AuthException(Reason.INVALID_MFA_CODE, "One-time code not accepted");
        }
        return finish(pending.cookies(), pending.codeVerifier(), pending.key(), pending.oauthState(), next);
    }

    /**
     * Gets a new access token.
     *
     * @throws AuthException with {@link Reason#INVALID_GRANT} if the refresh token is no longer valid
     */
    public TokenSet refresh(String refreshToken, DPoPKey key) throws IOException, AuthException {
        JsonObject body = new JsonObject();
        body.addProperty("grant_type", "refresh_token");
        body.addProperty("client_id", CLIENT_ID);
        body.addProperty("refresh_token", refreshToken);
        TokenResponse tokens = tokenRequest(body, key, "refresh");
        String accessToken = tokens.accessToken;
        if (accessToken == null) {
            throw new IOException("Token refresh returned no access token");
        }
        // Auth0 does not rotate refresh tokens for this client today, but use a new one if it ever sends one
        String newRefreshToken = tokens.refreshToken;
        return new TokenSet(accessToken, expiry(tokens), newRefreshToken != null ? newRefreshToken : refreshToken, key);
    }

    /**
     * Follows the redirects after the password or MFA step until Auth0 hands out the authorization code, answering
     * custom prompts on the way, then exchanges the code for tokens.
     */
    private TokenSet finish(CookieManager cookies, String verifier, DPoPKey key, String state, URI next)
            throws IOException, AuthException, MfaRequiredException {
        for (int step = 0; step < MAX_LOGIN_STEPS; step++) {
            String path = path(next);
            if (next.toString().startsWith(REDIRECT_PREFIX)) {
                return exchangeCode(next, verifier, key, state);
            } else if (path.endsWith("/authorize/resume")) {
                next = redirect(send(cookies, web(next, HttpMethod.GET)), "resume");
            } else if (MFA_CHALLENGE_PATH.matcher(path).matches()) {
                String mfaType = path.replaceFirst(".*/u/mfa-(\\w+)-challenge.*", "$1");
                logger.debug("Auth0 asks for a one-time code ({})", mfaType);
                // Load the challenge page like a browser does, in case rendering it is what sends the SMS or email
                send(cookies, web(next, HttpMethod.GET));
                throw new MfaRequiredException(new PendingLogin(cookies, verifier, key, state, mfaType,
                        stripQuery(next), requireState(next), clock.instant()));
            } else if (path.endsWith("/u/mfa-detect-browser-capabilities")) {
                // Asked on tenants that offer security keys, before choosing the factor. Deny WebAuthn so that Auth0
                // picks a factor that works without a browser.
                Fields capabilities = new Fields();
                capabilities.put("js-available", "true");
                capabilities.put("is-brave", "false");
                capabilities.put("webauthn-available", "false");
                capabilities.put("webauthn-platform-available", "false");
                next = redirect(send(cookies, formRequest(stripQuery(next), requireState(next), capabilities)),
                        "detect-browser-capabilities");
            } else if (path.contains("/u/mfa-")) {
                throw new AuthException(Reason.MFA_UNSUPPORTED,
                        "Unsupported second factor " + path.replaceFirst(".*/u/", ""));
            } else if (path.contains("/u/passkey")) {
                throw new AuthException(Reason.INTERACTION_REQUIRED, "Auth0 asks for a passkey");
            } else if (path.contains("/u/custom-prompt/")) {
                // Pages such as updated terms, which Auth0 shows once per account. Accept with the default action,
                // as the app's primary button would.
                ContentResponse response = send(cookies,
                        formRequest(stripQuery(next), requireState(next), new Fields()));
                if (!isRedirect(response.getStatus())) {
                    throw new AuthException(Reason.INTERACTION_REQUIRED,
                            "Auth0 shows a page that needs a person (" + describeError(response) + ")");
                }
                next = redirect(response, "custom-prompt");
            } else {
                throw new IOException("Unexpected login redirect to " + path);
            }
        }
        throw new IOException("Login did not finish within " + MAX_LOGIN_STEPS + " steps");
    }

    private TokenSet exchangeCode(URI callback, String verifier, DPoPKey key, String state)
            throws IOException, AuthException {
        Map<String, String> query = query(callback);
        String error = query.get("error");
        if (error != null) {
            logger.debug("Login refused: {} {}", error, query.get("error_description"));
            throw new AuthException(Reason.LOGIN_REJECTED, "Auth0 error " + error);
        }
        String code = query.get("code");
        if (code == null || !state.equals(query.get("state"))) {
            throw new IOException("Login callback without a code or with a foreign state");
        }
        JsonObject body = new JsonObject();
        body.addProperty("grant_type", "authorization_code");
        body.addProperty("client_id", CLIENT_ID);
        body.addProperty("code", code);
        body.addProperty("code_verifier", verifier);
        body.addProperty("redirect_uri", REDIRECT_URI);
        TokenResponse tokens;
        try {
            tokens = tokenRequest(body, key, "code exchange");
        } catch (AuthException e) {
            // A code that is refused right after it was issued is a protocol problem, not a credential problem
            throw new IOException(e.getMessage(), e);
        }
        String accessToken = tokens.accessToken;
        String refreshToken = tokens.refreshToken;
        if (accessToken == null || refreshToken == null) {
            throw new IOException("Code exchange did not return both an access and a refresh token");
        }
        logger.debug("Logged in, access token valid for {} s", tokens.expiresIn);
        return new TokenSet(accessToken, expiry(tokens), refreshToken, key);
    }

    /**
     * Calls the token endpoint with a DPoP proof. Auth0 may answer the first proof with {@code use_dpop_nonce} and a
     * nonce to include, so that case is retried once.
     */
    private TokenResponse tokenRequest(JsonObject body, DPoPKey key, String what) throws IOException, AuthException {
        String url = authBase + "/oauth/token";
        String nonce = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Request request = httpClient.newRequest(url).method(HttpMethod.POST).followRedirects(false)
                    .timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).agent(USER_AGENT_APP)
                    .header(HttpHeader.ACCEPT, "application/json").header("DPoP", key.proof("POST", url, nonce))
                    .content(new StringContentProvider("application/json", body.toString(), StandardCharsets.UTF_8));
            ContentResponse response = send(null, request);
            int status = response.getStatus();
            TokenResponse tokens;
            try {
                tokens = GSON.fromJson(response.getContentAsString(), TokenResponse.class);
            } catch (JsonSyntaxException e) {
                tokens = null;
            }
            if (tokens == null) {
                throw new IOException(what + ": HTTP " + status + " without a JSON body");
            }
            String error = tokens.error;
            if (status == 200 && error == null) {
                return tokens;
            }
            String newNonce = response.getHeaders().get("DPoP-Nonce");
            if (status == 400 && "use_dpop_nonce".equals(error) && newNonce != null && nonce == null) {
                nonce = newNonce;
                continue;
            }
            String description = what + " failed: HTTP " + status + " " + error + " " + tokens.errorDescription;
            // Auth0 answers an invalid refresh token with invalid_grant, which Generac's tenant sends as HTTP 403
            // rather than the 400 RFC 6749 asks for, so trust the error code over the status. Anything else, a WAF
            // page included, must not cost the refresh token.
            if ("invalid_grant".equals(error)) {
                throw new AuthException(Reason.INVALID_GRANT, description);
            }
            if (error != null) {
                throw new OAuthErrorException(error, description);
            }
            throw new IOException(description);
        }
        throw new IOException(what + " failed: DPoP nonce not accepted");
    }

    private Instant expiry(TokenResponse tokens) {
        // Auth0 issues two-hour tokens; assume one hour should expires_in ever be missing
        long seconds = tokens.expiresIn > 0 ? tokens.expiresIn : 3600;
        return clock.instant().plus(Duration.ofSeconds(seconds));
    }

    private Request web(URI uri, HttpMethod method) {
        return httpClient.newRequest(uri).method(method).followRedirects(false)
                .timeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).agent(USER_AGENT_WEB)
                .header(HttpHeader.ACCEPT, "text/html,*/*");
    }

    /**
     * A Universal Login form post: the state goes in the query and the body, and {@code action=default} picks the
     * primary button.
     */
    private Request formRequest(URI page, String state, Fields fields) {
        URI target = URI.create(page + "?state=" + UrlEncoded.encodeString(state));
        Fields body = new Fields();
        body.put("state", state);
        fields.forEach(body::put);
        body.put("action", "default");
        return web(target, HttpMethod.POST).header(HttpHeader.ORIGIN, authBase)
                .header(HttpHeader.REFERER, target.toString()).content(new FormContentProvider(body));
    }

    private URI postForm(CookieManager cookies, URI page, String state, Fields fields, String step)
            throws IOException, AuthException {
        ContentResponse response = send(cookies, formRequest(page, state, fields));
        int status = response.getStatus();
        if (isRedirect(status)) {
            return redirect(response, step);
        }
        Matcher matcher = ERROR_CODE.matcher(response.getContentAsString());
        if (status >= 400 && status < 500 && matcher.find()) {
            String code = matcher.group(1);
            logger.debug("Auth0 rejected the {} step: {}", step, code);
            // Codes such as wrong-credentials, wrong-email-credentials or user-blocked; anything else is a refusal
            // for another reason (too many attempts, blocked IP), which retrying makes worse.
            Reason reason = code.contains("credential") || code.contains("password") ? Reason.INVALID_CREDENTIALS
                    : Reason.LOGIN_REJECTED;
            throw new AuthException(reason, "Auth0 error code " + code);
        }
        throw new IOException("Login " + step + " step: HTTP " + status);
    }

    /**
     * Sends a request, carrying Auth0's cookies through the login when a cookie jar is given.
     */
    private ContentResponse send(@Nullable CookieManager cookies, Request request) throws IOException {
        URI uri = request.getURI();
        try {
            if (cookies != null) {
                for (String cookie : cookies.get(uri, Map.of()).getOrDefault("Cookie", List.of())) {
                    request.header(HttpHeader.COOKIE, cookie);
                }
            }
            ContentResponse response = request.send();
            if (cookies != null) {
                cookies.put(uri, Map.of("Set-Cookie", response.getHeaders().getValuesList(HttpHeader.SET_COOKIE)));
            }
            int status = response.getStatus();
            if (status == 429 || status >= 500) {
                throw new IOException("HTTP " + status + " from " + uri.getPath());
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (TimeoutException | ExecutionException e) {
            throw new IOException(e);
        }
    }

    private URI redirect(ContentResponse response, String step) throws IOException {
        String location = response.getHeaders().get(HttpHeader.LOCATION);
        if (!isRedirect(response.getStatus()) || location == null) {
            throw new IOException("Login " + step + " step: expected a redirect, got HTTP " + response.getStatus()
                    + " (" + describeError(response) + ")");
        }
        try {
            return response.getRequest().getURI().resolve(location);
        } catch (IllegalArgumentException e) {
            throw new IOException("Login " + step + " step: malformed redirect", e);
        }
    }

    private static String path(URI uri) {
        String path = uri.getPath();
        return path == null ? "" : path;
    }

    private static boolean isRedirect(int status) {
        return status == 302 || status == 303;
    }

    private static String describeError(ContentResponse response) {
        Matcher matcher = ERROR_CODE.matcher(response.getContentAsString());
        return matcher.find() ? matcher.group(1) : "HTTP " + response.getStatus();
    }

    private static String requireState(URI uri) throws IOException {
        String state = query(uri).get("state");
        if (state == null) {
            throw new IOException("Login redirect to " + uri.getPath() + " without a state");
        }
        return state;
    }

    private static URI stripQuery(URI uri) {
        String s = uri.toString();
        int q = s.indexOf('?');
        return URI.create(q < 0 ? s : s.substring(0, q));
    }

    private static String queryString(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        params.forEach((name, value) -> query.append(query.isEmpty() ? "" : "&").append(UrlEncoded.encodeString(name))
                .append('=').append(UrlEncoded.encodeString(value)));
        return query.toString();
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    result.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    // Malformed percent encoding; the parameter is treated as absent
                }
            }
        }
        return result;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64URL.encodeToString(bytes);
    }
}
