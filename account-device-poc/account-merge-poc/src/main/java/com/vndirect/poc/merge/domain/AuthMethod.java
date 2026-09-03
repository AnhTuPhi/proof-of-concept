package com.vndirect.poc.merge.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "auth_methods", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"provider", "externalId"})
})
public class AuthMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider;

    @Column(nullable = false)
    private String externalId;

    private String email;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public AuthMethod() {}

    public AuthMethod(Long userId, AuthProvider provider, String externalId, String email) {
        this.userId = userId;
        this.provider = provider;
        this.externalId = externalId;
        this.email = email;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public AuthProvider getProvider() { return provider; }
    public String getExternalId() { return externalId; }
    public String getEmail() { return email; }
    public Instant getCreatedAt() { return createdAt; }
}
