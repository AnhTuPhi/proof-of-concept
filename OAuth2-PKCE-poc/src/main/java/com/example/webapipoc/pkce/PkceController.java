package com.example.webapipoc.pkce;

import com.example.webapipoc.config.AppProperties;
import com.example.webapipoc.jwt.JwtService;
import com.example.webapipoc.jwt.RefreshTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal OAuth2 Authorization Code + PKCE flow (public client).
 *
 * Flow (S256 method):
 *   1) Client generates `code_verifier` = random 43-128 char URL-safe string.
 *      Computes `code_challenge` = BASE64URL(SHA256(code_verifier)).
 *   2) Client redirects user to:
 *        GET /oauth/authorize
 *          ?response_type=code
 *          &client_id=demo-spa
 *          &redirect_uri=...
 *          &state=...
 *          &code_challenge=...
 *          &code_challenge_method=S256
 *   3) For POC we auto-issue a code (production would show a login + consent screen here).
 *      Server stores {code → code_challenge, method, client_id, redirect_uri, expiresAt}.
 *      Redirects back to redirect_uri?code=...&state=...
 *   4) Client POSTs to /oauth/token:
 *        grant_type=authorization_code
 *        code=...
 *        code_verifier=...
 *        client_id=demo-spa
 *        redirect_uri=...
 *      Server validates BASE64URL(SHA256(code_verifier)) == stored code_challenge → issues tokens.
 *
 * Why PKCE matters: the `code_challenge` is sent over a redirect (browser-visible). An attacker who
 * steals the code STILL cannot exchange it because they don't have the `code_verifier` (kept in the
 * SPA's memory and never sent until the back-channel POST).
 */
@RestController
@RequestMapping("/oauth")
public class PkceController {

    private static final Logger log = LoggerFactory.getLogger(PkceController.class);

    private final AppProperties props;
    private final JwtService jwt;
    private final RefreshTokenStore refreshStore;

    /** code → AuthorizationCode (one-shot, single-use). */
    private final ConcurrentHashMap<String, AuthorizationCode> codes = new ConcurrentHashMap<>();

    public PkceController(AppProperties props, JwtService jwt, RefreshTokenStore refreshStore) {
        this.props = props;
        this.jwt = jwt;
        this.refreshStore = refreshStore;
    }

    @GetMapping("/authorize")
    public Object authorize(
        @RequestParam(name = "response_type") String responseType,
        @RequestParam(name = "client_id") String clientId,
        @RequestParam(name = "redirect_uri") String redirectUri,
        @RequestParam(name = "state", required = false) String state,
        @RequestParam(name = "code_challenge") String codeChallenge,
        @RequestParam(name = "code_challenge_method", defaultValue = "S256") String method,
        // POC: pre-authenticated subject passed as query param. Production uses login form + session.
        @RequestParam(name = "subject", defaultValue = "alice") String subject
    ) {
        if (!"code".equals(responseType)) return error(redirectUri, state, "unsupported_response_type");
        if (!props.getOauth().getClientId().equals(clientId)) return error(redirectUri, state, "unauthorized_client");
        if (!props.getOauth().getRedirectUri().equals(redirectUri)) return error(redirectUri, state, "invalid_redirect_uri");
        if (!"S256".equals(method) && !"plain".equals(method)) return error(redirectUri, state, "invalid_request");
        if (codeChallenge.length() < 43 || codeChallenge.length() > 128) return error(redirectUri, state, "invalid_request");

        String code = randomUrlSafe(32);
        codes.put(code, new AuthorizationCode(
            code, clientId, redirectUri, subject,
            codeChallenge, method,
            Instant.now().plusSeconds(props.getOauth().getCodeTtlSeconds())
        ));

        StringBuilder url = new StringBuilder(redirectUri)
            .append(redirectUri.contains("?") ? "&" : "?")
            .append("code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        if (state != null) {
            url.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
        }
        log.info("[PKCE] Issued auth code for client={} subject={} challengeMethod={}", clientId, subject, method);
        return new RedirectView(url.toString());
    }

    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> token(
        @RequestParam(name = "grant_type") String grantType,
        @RequestParam(name = "code", required = false) String code,
        @RequestParam(name = "code_verifier", required = false) String codeVerifier,
        @RequestParam(name = "client_id", required = false) String clientId,
        @RequestParam(name = "redirect_uri", required = false) String redirectUri
    ) {
        if (!"authorization_code".equals(grantType)) {
            return badRequest("unsupported_grant_type", "only authorization_code is implemented");
        }
        if (code == null || codeVerifier == null || clientId == null || redirectUri == null) {
            return badRequest("invalid_request", "missing parameters");
        }
        AuthorizationCode ac = codes.remove(code); // one-shot
        if (ac == null) return badRequest("invalid_grant", "code unknown or already used");
        if (ac.expiresAt().isBefore(Instant.now())) return badRequest("invalid_grant", "code expired");
        if (!ac.clientId().equals(clientId)) return badRequest("invalid_grant", "client mismatch");
        if (!ac.redirectUri().equals(redirectUri)) return badRequest("invalid_grant", "redirect_uri mismatch");

        // PKCE check
        String expected = ac.codeChallenge();
        String actual = switch (ac.method()) {
            case "S256" -> base64UrlSha256(codeVerifier);
            case "plain" -> codeVerifier;
            default -> "";
        };
        if (!expected.equals(actual)) {
            log.warn("[PKCE] code_verifier rejected for client={}", clientId);
            return badRequest("invalid_grant", "code_verifier does not match code_challenge");
        }

        // Issue tokens (reuse the JWT machinery so refresh-rotation also covers PKCE-issued sessions)
        String subject = ac.subject();
        String familyId = UUID.randomUUID().toString();
        var access = jwt.issueAccessToken(subject);
        var refresh = jwt.issueRefreshToken(subject, familyId);
        refreshStore.register(refresh.jti(), subject, familyId, refresh.expiresAt());

        return ResponseEntity.ok(Map.of(
            "access_token", access.jwt(),
            "token_type", "Bearer",
            "expires_in", props.getJwt().getAccessTtlSeconds(),
            "refresh_token", refresh.jwt(),
            "scope", "read",
            "family_id", familyId
        ));
    }

    // --- helpers ---

    private static String base64UrlSha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buf = new byte[bytes];
        new java.security.SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static ResponseEntity<?> badRequest(String error, String description) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", error, "error_description", description));
    }

    private RedirectView error(String redirectUri, String state, String code) {
        String url = redirectUri + (redirectUri.contains("?") ? "&" : "?") + "error=" + code
            + (state != null ? "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) : "");
        return new RedirectView(url);
    }

    private record AuthorizationCode(
        String code, String clientId, String redirectUri, String subject,
        String codeChallenge, String method, Instant expiresAt
    ) {}
}
