package com.vndirect.poc.session.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtl;
    private final long refreshTtl;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-token-ttl-seconds}") long accessTtl,
                      @Value("${app.jwt.refresh-token-ttl-seconds}") long refreshTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public String issueAccess(Long userId, String sessionId, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(String.valueOf(userId))
            .claim("sid", sessionId)
            .claim("tv", tokenVersion)
            .claim("typ", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(accessTtl)))
            .signWith(key)
            .compact();
    }

    public String issueRefresh(Long userId, String sessionId, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(String.valueOf(userId))
            .claim("sid", sessionId)
            .claim("tv", tokenVersion)
            .claim("typ", "refresh")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(refreshTtl)))
            .signWith(key)
            .compact();
    }

    public Claims parse(String token) {
        Jws<Claims> parsed = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        return parsed.getPayload();
    }

    public long getAccessTtl() { return accessTtl; }
    public long getRefreshTtl() { return refreshTtl; }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String msg) { super(msg); }
        public InvalidTokenException(String msg, JwtException cause) { super(msg, cause); }
    }
}
