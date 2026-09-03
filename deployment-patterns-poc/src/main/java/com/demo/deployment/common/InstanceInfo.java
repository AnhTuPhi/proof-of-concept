package com.demo.deployment.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record InstanceInfo(String instanceId, String version, String color) {
}
