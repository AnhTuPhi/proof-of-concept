package com.example.bookingpoc.overbooking.domain;

/**
 * Bumping priority: when seats run short, FIRST is bumped LAST.
 * The numeric rank is used by the bumping engine; lower rank = higher priority
 * to keep (so BASIC_ECONOMY bumps first).
 */
public enum FareClass {
    FIRST(1, 1500),
    BUSINESS(2, 900),
    ECONOMY(3, 400),
    BASIC_ECONOMY(4, 150);

    private final int rank;
    private final int baseFare;

    FareClass(int rank, int baseFare) {
        this.rank = rank;
        this.baseFare = baseFare;
    }

    public int getRank() { return rank; }
    public int getBaseFare() { return baseFare; }
}
