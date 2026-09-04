package com.example.espoc.sync.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "outbox_events", schema = "sync_outbox")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OutboxEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false) private String aggregateType;
    @Column(length = 40, nullable = false) private String aggregateId;
    @Column(length = 32, nullable = false) private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB", nullable = false)
    private String payload;

    @Column(nullable = false, updatable = false) private Instant createdAt;
    private Instant pickedUpAt;
    private Instant publishedAt;

    @Column(nullable = false) private int attempts;
    @Column(columnDefinition = "TEXT") private String lastError;

    @PrePersist void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
