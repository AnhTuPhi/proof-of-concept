package com.example.espoc.ac;

import com.example.espoc.common.config.EsClientConfig;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.web.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({ EsClientConfig.class, IndexAdmin.class, GlobalExceptionHandler.class })
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
}
