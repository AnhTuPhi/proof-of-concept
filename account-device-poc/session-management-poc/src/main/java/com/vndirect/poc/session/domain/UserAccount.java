package com.vndirect.poc.session.domain;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class UserAccount {
    private final Long id;
    private final String username;
    private volatile String passwordHash;
    private final AtomicInteger tokenVersion = new AtomicInteger(1);
    private volatile Instant passwordChangedAt = Instant.now();

    public UserAccount(Long id, String username, String passwordHash) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public int getTokenVersion() { return tokenVersion.get(); }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }

    /** Bumping the token version invalidates every JWT and refresh token previously issued. */
    public int bumpTokenVersion(String newPasswordHash) {
        if (newPasswordHash != null) {
            this.passwordHash = newPasswordHash;
            this.passwordChangedAt = Instant.now();
        }
        return tokenVersion.incrementAndGet();
    }
}
