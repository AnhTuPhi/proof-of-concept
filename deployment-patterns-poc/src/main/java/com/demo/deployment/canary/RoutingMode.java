package com.demo.deployment.canary;

public enum RoutingMode {
    /** 100% to the active color; admin flips active to switch instantly. */
    BLUE_GREEN,
    /** Split traffic by weight to GREEN, remainder to BLUE. */
    CANARY
}
