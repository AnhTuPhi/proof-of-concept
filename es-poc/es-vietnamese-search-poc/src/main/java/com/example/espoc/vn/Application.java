package com.example.espoc.vn;

import com.example.espoc.common.config.EsClientConfig;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.web.GlobalExceptionHandler;
import com.example.espoc.vn.config.VnProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(VnProperties.class)
@Import({ EsClientConfig.class, IndexAdmin.class, GlobalExceptionHandler.class })
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
}
