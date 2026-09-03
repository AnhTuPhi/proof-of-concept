package com.demo.deployment.canary;

public record Backend(String color, String version) {
    public static final Backend BLUE = new Backend("BLUE", "v1");
    public static final Backend GREEN = new Backend("GREEN", "v2");
}
