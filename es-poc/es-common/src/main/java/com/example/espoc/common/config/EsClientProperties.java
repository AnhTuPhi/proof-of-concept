package com.example.espoc.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app.es")
public record EsClientProperties(
        List<String> uris,
        String username,
        String password,
        Duration connectTimeout,
        Duration socketTimeout,
        Integer maxConnTotal,
        Integer maxConnPerRoute
) {
    public EsClientProperties {
        if (uris == null || uris.isEmpty()) uris = List.of("http://localhost:9200");
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(2);
        if (socketTimeout == null) socketTimeout = Duration.ofSeconds(30);
        if (maxConnTotal == null) maxConnTotal = 50;
        if (maxConnPerRoute == null) maxConnPerRoute = 25;
    }
}
