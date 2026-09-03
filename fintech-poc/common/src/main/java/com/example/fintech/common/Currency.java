package com.example.fintech.common;

public enum Currency {
    VND(0),
    USD(2),
    EUR(2),
    JPY(0),
    GBP(2);

    private final int scale;

    Currency(int scale) {
        this.scale = scale;
    }

    public int scale() {
        return scale;
    }
}
