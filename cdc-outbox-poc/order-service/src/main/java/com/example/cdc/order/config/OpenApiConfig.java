package com.example.cdc.order.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("order-service API")
                        .description("Producer for the cdc-outbox-poc demo. Creates Order entities and emits "
                                + "OrderCreated/OrderPaid/OrderCancelled events via the Transactional Outbox pattern.")
                        .version("1.0.0")
                        .contact(new Contact().name("cdc-outbox-poc"))
                        .license(new License().name("MIT")));
    }
}
