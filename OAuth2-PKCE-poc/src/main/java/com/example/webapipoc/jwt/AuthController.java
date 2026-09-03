package com.example.webapipoc.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * /auth/login   — username/password → access + refresh token (new family)
 * /auth/refresh — refresh token → new access + new refresh token (same family, rotation)
 * /auth/logout  — refresh token → revoke its family
 * /auth/debug   — list active refresh tokens + revoked families (POC only!)
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtService jwt;
    private final RefreshTokenStore store;
    private final UserStore users;

    public AuthController(JwtService jwt, RefreshTokenStore store, UserStore users) {
        this.jwt = jwt;
        this.store = store;
        this.users = users;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        var subject = users.authenticate(req.username(), req.password());
        if (subject.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid_credentials"));
        }
        String familyId = UUID.randomUUID().toString();
        var access = jwt.issueAccessToken(subject.get());
        var refresh = jwt.issueRefreshToken(subject.get(), familyId);
        store.register(refresh.jti(), subject.get(), familyId, refresh.expiresAt());
        return ResponseEntity.ok(new TokenResponse(
            access.jwt(),
            refresh.jwt(),
            "Bearer",
            access.expiresAt().toString(),
            refresh.expiresAt().toString(),
            familyId
        ));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest req) {
        Claims claims;
        try {
            claims = jwt.parse(req.refreshToken());
        } catch (JwtException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid_token", "reason", e.getMessage()));
        }

        if (!JwtService.TYPE_REFRESH.equals(jwt.tokenType(claims))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "wrong_token_type"));
        }

        String jti = claims.getId();
        var outcome = store.rotate(jti);
        switch (outcome.status()) {
            case OK -> {
                // success — fall through
            }
            case REUSE_DETECTED -> {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                        "error", "reuse_detected",
                        "message", "Refresh token was already consumed. Entire family revoked for safety.",
                        "familyId", outcome.familyId()
                    ));
            }
            case FAMILY_REVOKED -> {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "family_revoked", "familyId", outcome.familyId()));
            }
            case EXPIRED -> {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "expired"));
            }
            case UNKNOWN -> {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unknown_token"));
            }
        }

        // Issue new pair with same family
        String subject = outcome.subject();
        String familyId = outcome.familyId();
        var newAccess = jwt.issueAccessToken(subject);
        var newRefresh = jwt.issueRefreshToken(subject, familyId);
        store.register(newRefresh.jti(), subject, familyId, newRefresh.expiresAt());

        return ResponseEntity.ok(new TokenResponse(
            newAccess.jwt(),
            newRefresh.jwt(),
            "Bearer",
            newAccess.expiresAt().toString(),
            newRefresh.expiresAt().toString(),
            familyId
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody RefreshRequest req) {
        try {
            Claims claims = jwt.parse(req.refreshToken());
            String familyId = jwt.familyId(claims);
            if (familyId != null) store.revokeFamily(familyId);
            return ResponseEntity.ok(Map.of("revoked", familyId == null ? "(none)" : familyId));
        } catch (JwtException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid_token"));
        }
    }

    /** Debug only — production must NOT expose token internals. */
    @GetMapping("/debug")
    public Map<String, Object> debug() {
        return Map.of(
            "now", Instant.now().toString(),
            "activeRefreshTokens", store.snapshot(),
            "revokedFamilies", store.revokedFamiliesSnapshot()
        );
    }

    public record LoginRequest(String username, String password) {}
    public record RefreshRequest(String refreshToken) {}
    public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        String accessExpiresAt,
        String refreshExpiresAt,
        String familyId
    ) {}
}
