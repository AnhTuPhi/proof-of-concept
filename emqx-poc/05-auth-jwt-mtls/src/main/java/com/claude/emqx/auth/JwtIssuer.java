package com.claude.emqx.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * Issues short-lived JWTs that EMQX validates with the same HMAC secret.
 *
 * <p>Claims contract (EMQX side reads these from emqx-overrides.conf):
 * <ul>
 *   <li>{@code sub} (Subject)   - the MQTT clientId. EMQX clientid_claim points here.</li>
 *   <li>{@code username}        - mapped to MQTT username for ACL queries.</li>
 *   <li>{@code tenant_id}       - custom claim used for topic-namespace ACL.</li>
 *   <li>{@code exp}             - short (5 min). Devices refresh before expiry.</li>
 *   <li>{@code acl}             - optional inline ACL (EMQX 5.x supports this);
 *                                 lets you avoid the Postgres ACL lookup.</li>
 * </ul>
 *
 * <p>Why HMAC and not RSA: HMAC verification is ~10x faster on the broker side
 * (no per-token public-key parse). For 100k device fleet authenticating once
 * per token rotation (every 5 min), HMAC keeps auth CPU under 1%. Use RSA only
 * if you have a strict requirement that the broker not hold a signing key
 * (multi-tenant SaaS broker).
 */
@Service
public class JwtIssuer {

    @Value("${auth.jwt.secret}")
    private String secret;

    @Value("${auth.jwt.ttl-seconds:300}")
    private long ttlSeconds;

    private Key signingKey;

    @PostConstruct
    void init() {
        // jjwt enforces minimum key length for HS256; pad if needed (demo only).
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        this.signingKey = new SecretKeySpec(bytes, SignatureAlgorithm.HS256.getJcaName());
    }

    public Token issue(String deviceId, String tenantId) {
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofSeconds(ttlSeconds));

        String token = Jwts.builder()
                .setSubject(deviceId)
                .claim("username", deviceId)            // ACL lookup keys on this
                .claim("tenant_id", tenantId)
                // Inline ACL = avoid a DB round-trip per CONNECT.
                // EMQX merges this with the AuthZ chain.
                .claim("acl", Map.of(
                        "pub", "tenant/" + tenantId + "/devices/" + deviceId + "/#",
                        "sub", "tenant/" + tenantId + "/devices/" + deviceId + "/cmd/#"
                ))
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();

        return new Token(token, deviceId, tenantId, exp.toEpochMilli());
    }

    public record Token(String token, String deviceId, String tenantId, long expiresAtEpochMillis) {}
}
