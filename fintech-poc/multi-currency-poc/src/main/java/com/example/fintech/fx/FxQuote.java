package com.example.fintech.fx;

import com.example.fintech.common.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fx_quotes")
public class FxQuote {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Id
    private String id;

    @Column(name = "from_ccy", nullable = false)
    @Enumerated(EnumType.STRING)
    private Currency from;

    @Column(name = "to_ccy", nullable = false)
    @Enumerated(EnumType.STRING)
    private Currency to;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal rate;

    @Column(nullable = false)
    private Instant lockedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    protected FxQuote() {}

    public FxQuote(Currency from, Currency to, BigDecimal rate) {
        this.id = UUID.randomUUID().toString();
        this.from = from;
        this.to = to;
        this.rate = rate;
        this.lockedAt = Instant.now();
        this.expiresAt = lockedAt.plus(TTL);
    }

    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }

    public String getId() { return id; }
    public Currency getFrom() { return from; }
    public Currency getTo() { return to; }
    public BigDecimal getRate() { return rate; }
    public Instant getLockedAt() { return lockedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
