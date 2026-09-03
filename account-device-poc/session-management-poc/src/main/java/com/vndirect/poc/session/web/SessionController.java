package com.vndirect.poc.session.web;

import com.vndirect.poc.session.domain.Session;
import com.vndirect.poc.session.domain.UserAccount;
import com.vndirect.poc.session.service.SessionService;
import com.vndirect.poc.session.service.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/session")
@CrossOrigin
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) { this.sessions = sessions; }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest req) {
        try {
            TokenPair tp = sessions.login(
                body.get("username"),
                body.get("password"),
                body.getOrDefault("deviceLabel", "unknown"),
                Optional.ofNullable(req.getHeader("User-Agent")).orElse("unknown"),
                req.getRemoteAddr()
            );
            return ResponseEntity.ok(tp);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(401).body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/whoami")
    public ResponseEntity<?> whoami(@RequestHeader(value = "Authorization", required = false) String auth) {
        String token = stripBearer(auth);
        if (token == null) return ResponseEntity.status(401).body(Map.of("error", "no token"));
        SessionService.ValidationResult v = sessions.validateAccess(token);
        if (!v.valid()) return ResponseEntity.status(401).body(Map.of("error", v.reason()));
        UserAccount u = sessions.findUser(v.userId()).orElseThrow();
        return ResponseEntity.ok(Map.of(
            "userId", u.getId(),
            "username", u.getUsername(),
            "sessionId", v.sessionId(),
            "tokenVersion", v.tokenVersion()
        ));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(sessions.refresh(body.get("refreshToken")));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(401).body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/devices/{userId}")
    public List<Map<String, Object>> devices(@PathVariable Long userId) {
        return sessions.listSessions(userId).stream().map(this::toView).toList();
    }

    @PostMapping("/logout/{sessionId}")
    public Map<String, Object> logout(@PathVariable String sessionId) {
        sessions.logoutSession(sessionId);
        return Map.of("status", "revoked", "sessionId", sessionId);
    }

    @PostMapping("/logout-everywhere")
    public Map<String, Object> logoutEverywhere(@RequestBody Map<String, Object> body) {
        Long userId = ((Number) body.get("userId")).longValue();
        String newPassword = (String) body.get("newPassword");
        int newVer = sessions.logoutEverywhere(userId, newPassword);
        return Map.of("status", "ok", "newTokenVersion", newVer, "passwordChanged", newPassword != null);
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return List.of("alice", "bob").stream()
            .map(n -> sessions.findUser(n).orElseThrow())
            .map(u -> Map.<String, Object>of(
                "userId", u.getId(),
                "username", u.getUsername(),
                "tokenVersion", u.getTokenVersion()
            )).toList();
    }

    private Map<String, Object> toView(Session s) {
        return Map.of(
            "sessionId", s.getSessionId(),
            "deviceLabel", s.getDeviceLabel(),
            "userAgent", s.getUserAgent(),
            "ipAddress", s.getIpAddress(),
            "tokenVersionAtIssue", s.getTokenVersionAtIssue(),
            "createdAt", s.getCreatedAt().toString(),
            "lastSeenAt", s.getLastSeenAt().toString(),
            "revoked", s.isRevoked()
        );
    }

    private String stripBearer(String h) {
        if (h == null || !h.startsWith("Bearer ")) return null;
        return h.substring(7);
    }
}
