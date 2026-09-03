package com.claude.kafka.cqrs;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication(scanBasePackages = {
        "com.claude.kafka.cqrs",
        "com.claude.kafka.common"
})
@EnableKafka
public class CqrsApp {
    public static void main(String[] args) {
        SpringApplication.run(CqrsApp.class, args);
    }

    @Bean
    public ElasticsearchClient esClient(@Value("${app.es.host:localhost}") String host,
                                        @Value("${app.es.port:9200}") int port) {
        RestClient rest = RestClient.builder(new HttpHost(host, port)).build();
        return new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()));
    }
}
