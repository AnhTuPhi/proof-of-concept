package com.demo.deployment.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InstanceInfo.class)
public class AppConfig {
}
