package com.claude.emqx.mqtt5;

import com.claude.emqx.common.client.MqttClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MqttClientProperties.class)
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
}
