package com.example.webapipoc.jwt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for refresh tokens with rotation + reuse detection.
 *
 *   - `records` maps jti → {subject, familyId, used, expiresAt}
 *   - `revokedFamilies` records family ids that are revoked (reuse detected or logout)
 *
 * Production: replace with Redis or a DB table. The semantics stay identical.
 *
 * Reuse detection works because:
 *   - Each refresh call marks the consumed jti as `used` and issues a NEW jti in the same family.
 *   - If an attacker who stole the OLD refresh token replays it after the legit user already rotated,
 *     the store sees `used==true` for that jti → entire family revoked → both attacker AND
 *     legit user are signed out (the legit user re-logs in fresh; the attacker is locked out forever).
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private final Map<String, Record> records = new ConcurrentHashMap<>();
    private final Map<String, Instant> revokedFamilies = new ConcurrentHashMap<>();

    public void register(String jti, String subject, String familyId, Instant expiresAt) {
        records.put(jti, new Record(jti, subject, familyId, false, expiresAt));
    }

    public RotationOutcome rotate(String jti) {
        Record existing = records.get(jti);
        if (existing == null) {
            // Unknown jti — token from outside this server's lifetime, or already evicted
            return new RotationOutcome(Status.UNKNOWN, null, null);
        }
        if (revokedFamilies.containsKey(existing.familyId())) {
            log.warn("Refresh token used from REVOKED family={} jti={} subject={}",
                existing.familyId(), jti, existing.subject());
            return new RotationOutcome(Status.FAMILY_REVOKED, existing.subject(), existing.familyId());
        }
        if (existing.used()) {
            // REUSE DETECTED — revoke the whole family
            revokeFamily(existing.familyId());
            log.error("🚨 REFRESH TOKEN REUSE DETECTED! jti={} familyId={} subject={} — revoking entire family",
                jti, existing.familyId(), existing.subject());
            return new RotationOutcome(Status.REUSE_DETECTED, existing.subject(), existing.familyId());
        }
        if (existing.expiresAt().isBefore(Instant.now())) {
            return new RotationOutcome(Status.EXPIRED, existing.subject(), existing.familyId());
        }
        // Mark this jti as used (one-shot)
        records.put(jti, existing.markUsed());
        return new RotationOutcome(Status.OK, existing.subject(), existing.familyId());
    }

    public void revokeFamily(String familyId) {
        revokedFamilies.put(familyId, Instant.now());
        // Optional: also wipe records for that family
        records.values().removeIf(r -> r.familyId().equals(familyId));
    }

    public boolean isFamilyRevoked(String familyId) {
        return revokedFamilies.containsKey(familyId);
    }

    /** For demo UI inspection only. */
    public Map<String, Record> snapshot() { return Map.copyOf(records); }
    public Map<String, Instant> revokedFamiliesSnapshot() { return Map.copyOf(revokedFamilies); }

    public enum Status { OK, REUSE_DETECTED, FAMILY_REVOKED, EXPIRED, UNKNOWN }

    public record RotationOutcome(Status status, String subject, String familyId) {}

    public record Record(String jti, String subject, String familyId, boolean used, Instant expiresAt) {
        Record markUsed() { return new Record(jti, subject, familyId, true, expiresAt); }
    }
}
