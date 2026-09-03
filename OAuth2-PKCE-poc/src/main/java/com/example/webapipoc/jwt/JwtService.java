package com.example.webapipoc.jwt;

import com.example.webapipoc.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT signing/parsing using HS256 (POC). Production should use RS256/ES256
 * with private key in KMS.
 *
 * Three token types are produced:
 *   - "access"  — short-lived, presented as Bearer
 *   - "refresh" — long-lived, carries a family_id used for reuse detection
 */
@Service
public class JwtService {

    static final String CLAIM_TOKEN_TYPE = "typ";
    static final String CLAIM_FAMILY_ID = "fam";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final AppProperties props;
    private final SecretKey key;

    public JwtService(AppProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken issueAccessToken(String subject) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(props.getJwt().getAccessTtlSeconds());
        String jwt = Jwts.builder()
            .issuer(props.getJwt().getIssuer())
            .subject(subject)
            .id(jti)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .claim(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
            .signWith(key)
            .compact();
        return new IssuedToken(jwt, jti, null, exp);
    }

    public IssuedToken issueRefreshToken(String subject, String familyId) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(props.getJwt().getRefreshTtlSeconds());
        String jwt = Jwts.builder()
            .issuer(props.getJwt().getIssuer())
            .subject(subject)
            .id(jti)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .claim(CLAIM_TOKEN_TYPE, TYPE_REFRESH)
            .claim(CLAIM_FAMILY_ID, familyId)
            .signWith(key)
            .compact();
        return new IssuedToken(jwt, jti, familyId, exp);
    }

    /** @throws JwtException if token is invalid / expired / tampered. */
    public Claims parse(String jwt) {
        Jws<Claims> jws = Jwts.parser()
            .verifyWith(key)
            .requireIssuer(props.getJwt().getIssuer())
            .build()
            .parseSignedClaims(jwt);
        return jws.getPayload();
    }

    public String tokenType(Claims claims) {
        return claims.get(CLAIM_TOKEN_TYPE, String.class);
    }

    public String familyId(Claims claims) {
        return claims.get(CLAIM_FAMILY_ID, String.class);
    }

    public record IssuedToken(String jwt, String jti, String familyId, Instant expiresAt) {}
}
