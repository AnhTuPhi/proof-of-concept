package com.vndirect.poc.session.service;

import com.vndirect.poc.session.domain.Session;
import com.vndirect.poc.session.domain.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SessionService {

    private final JwtService jwt;
    private final Map<Long, UserAccount> usersById = new ConcurrentHashMap<>();
    private final Map<String, UserAccount> usersByName = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong userSeq = new AtomicLong(0);

    public SessionService(JwtService jwt) { this.jwt = jwt; }

    @PostConstruct
    public void seed() {
        register("alice", "wonderland");
        register("bob", "buildit");
    }

    public UserAccount register(String username, String password) {
        UserAccount u = new UserAccount(userSeq.incrementAndGet(), username, hash(password));
        usersById.put(u.getId(), u);
        usersByName.put(username, u);
        return u;
    }

    public TokenPair login(String username, String password, String deviceLabel,
                           String userAgent, String ipAddress) {
        UserAccount u = Optional.ofNullable(usersByName.get(username))
            .orElseThrow(() -> new IllegalArgumentException("no such user"));
        if (!u.getPasswordHash().equals(hash(password))) {
            throw new IllegalArgumentException("bad credentials");
        }
        return issueNewSession(u, deviceLabel, userAgent, ipAddress);
    }

    private TokenPair issueNewSession(UserAccount u, String deviceLabel, String userAgent, String ip) {
        String sid = UUID.randomUUID().toString();
        int tv = u.getTokenVersion();
        String access = jwt.issueAccess(u.getId(), sid, tv);
        String refresh = jwt.issueRefresh(u.getId(), sid, tv);
        String refreshJti = jwt.parse(refresh).getId();
        sessions.put(sid, new Session(sid, u.getId(), deviceLabel, userAgent, ip, refreshJti, tv));
        return new TokenPair(access, refresh, sid, jwt.getAccessTtl());
    }

    /**
     * Validates access token: signature + expiry + session not revoked + token version not stale.
     * tokenVersion mismatch is what makes the stateless-but-revocable trick work.
     */
    public ValidationResult validateAccess(String accessToken) {
        try {
            Claims c = jwt.parse(accessToken);
            if (!"access".equals(c.get("typ", String.class))) return ValidationResult.invalid("wrong token type");
            String sid = c.get("sid", String.class);
            int tv = c.get("tv", Integer.class);
            Long uid = Long.parseLong(c.getSubject());
            Session s = sessions.get(sid);
            if (s == null) return ValidationResult.invalid("session not found");
            if (s.isRevoked()) return ValidationResult.invalid("session revoked");
            UserAccount u = usersById.get(uid);
            if (u == null) return ValidationResult.invalid("user gone");
            if (tv != u.getTokenVersion()) return ValidationResult.invalid("token version stale (expected "
                + u.getTokenVersion() + ", got " + tv + ")");
            s.touch();
            return ValidationResult.ok(uid, sid, tv);
        } catch (JwtException ex) {
            return ValidationResult.invalid("jwt parse: " + ex.getMessage());
        }
    }

    /** Refresh-token rotation: old refresh JTI is single-use; new pair replaces it on the session. */
    public TokenPair refresh(String refreshToken) {
        Claims c;
        try {
            c = jwt.parse(refreshToken);
        } catch (JwtException ex) {
            throw new IllegalStateException("bad refresh: " + ex.getMessage(), ex);
        }
        if (!"refresh".equals(c.get("typ", String.class))) throw new IllegalStateException("not a refresh token");
        String sid = c.get("sid", String.class);
        String jti = c.getId();
        int tv = c.get("tv", Integer.class);
        Long uid = Long.parseLong(c.getSubject());

        Session s = sessions.get(sid);
        if (s == null) throw new IllegalStateException("session not found");
        if (s.isRevoked()) throw new IllegalStateException("session revoked");
        if (!Objects.equals(jti, s.getRefreshTokenJti())) {
            // Reuse of a rotated refresh token → indicates theft. Kill the session.
            s.revoke();
            throw new IllegalStateException("refresh token reuse detected → session revoked");
        }
        UserAccount u = usersById.get(uid);
        if (u == null) throw new IllegalStateException("user gone");
        if (tv != u.getTokenVersion()) {
            s.revoke();
            throw new IllegalStateException("token version stale → session revoked");
        }
        String newAccess = jwt.issueAccess(uid, sid, tv);
        String newRefresh = jwt.issueRefresh(uid, sid, tv);
        s.setRefreshTokenJti(jwt.parse(newRefresh).getId());
        s.touch();
        return new TokenPair(newAccess, newRefresh, sid, jwt.getAccessTtl());
    }

    public void logoutSession(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s != null) s.revoke();
    }

    /** Logout everywhere = bump user's token version. Every JWT/refresh in flight becomes invalid. */
    public int logoutEverywhere(Long userId, String newPassword) {
        UserAccount u = Optional.ofNullable(usersById.get(userId))
            .orElseThrow(() -> new IllegalArgumentException("no such user"));
        int newVer = u.bumpTokenVersion(newPassword == null ? null : hash(newPassword));
        sessions.values().stream()
            .filter(s -> s.getUserId().equals(userId))
            .forEach(Session::revoke);
        return newVer;
    }

    public List<Session> listSessions(Long userId) {
        return sessions.values().stream()
            .filter(s -> s.getUserId().equals(userId))
            .sorted(Comparator.comparing(Session::getCreatedAt).reversed())
            .toList();
    }

    public Optional<UserAccount> findUser(Long userId) { return Optional.ofNullable(usersById.get(userId)); }
    public Optional<UserAccount> findUser(String username) { return Optional.ofNullable(usersByName.get(username)); }

    private static String hash(String pwd) {
        // POC-only hash — DO NOT ship. Real code uses BCrypt/Argon2.
        return Integer.toHexString(("salt::" + pwd).hashCode());
    }

    public record ValidationResult(boolean valid, Long userId, String sessionId, Integer tokenVersion, String reason) {
        static ValidationResult ok(Long uid, String sid, int tv) { return new ValidationResult(true, uid, sid, tv, "ok"); }
        static ValidationResult invalid(String r) { return new ValidationResult(false, null, null, null, r); }
    }
}
