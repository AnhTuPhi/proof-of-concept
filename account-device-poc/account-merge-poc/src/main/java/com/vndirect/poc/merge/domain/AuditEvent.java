package com.vndirect.poc.merge.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 2000)
    private String detail;

    @Column(nullable = false)
    private Instant at = Instant.now();

    public AuditEvent() {}

    public AuditEvent(Long userId, String action, String detail) {
        this.userId = userId;
        this.action = action;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public Instant getAt() { return at; }
}
