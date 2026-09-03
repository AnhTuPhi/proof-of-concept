package com.vndirect.poc.session.domain;

import java.time.Instant;

public class Session {
    private final String sessionId;
    private final Long userId;
    private final String deviceLabel;
    private final String userAgent;
    private final String ipAddress;
    private volatile String refreshTokenJti;
    private volatile int tokenVersionAtIssue;
    private final Instant createdAt;
    private volatile Instant lastSeenAt;
    private volatile boolean revoked;

    public Session(String sessionId, Long userId, String deviceLabel, String userAgent,
                   String ipAddress, String refreshTokenJti, int tokenVersionAtIssue) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.deviceLabel = deviceLabel;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
        this.refreshTokenJti = refreshTokenJti;
        this.tokenVersionAtIssue = tokenVersionAtIssue;
        this.createdAt = Instant.now();
        this.lastSeenAt = this.createdAt;
    }

    public String getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public String getDeviceLabel() { return deviceLabel; }
    public String getUserAgent() { return userAgent; }
    public String getIpAddress() { return ipAddress; }
    public String getRefreshTokenJti() { return refreshTokenJti; }
    public void setRefreshTokenJti(String jti) { this.refreshTokenJti = jti; }
    public int getTokenVersionAtIssue() { return tokenVersionAtIssue; }
    public void setTokenVersionAtIssue(int v) { this.tokenVersionAtIssue = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void touch() { this.lastSeenAt = Instant.now(); }
    public boolean isRevoked() { return revoked; }
    public void revoke() { this.revoked = true; }
}
