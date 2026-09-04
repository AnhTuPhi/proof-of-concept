package com.example.espoc.common.config;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(EsClientProperties.class)
public class EsClientConfig {

    @Bean(destroyMethod = "close")
    public RestClient restClient(EsClientProperties props) {
        HttpHost[] hosts = props.uris().stream()
                .map(URI::create)
                .map(uri -> new HttpHost(uri.getHost(), uri.getPort() == -1 ? 9200 : uri.getPort(), uri.getScheme()))
                .toArray(HttpHost[]::new);

        RestClient.Builder builder = RestClient.builder(hosts)
                .setRequestConfigCallback(rc -> rc
                        .setConnectTimeout((int) props.connectTimeout().toMillis())
                        .setSocketTimeout((int) props.socketTimeout().toMillis()))
                .setHttpClientConfigCallback(this::configureHttp);

        builder.setHttpClientConfigCallback(http -> {
            HttpAsyncClientBuilder configured = configureHttp(http);
            if (StringUtils.hasText(props.username())) {
                BasicCredentialsProvider creds = new BasicCredentialsProvider();
                creds.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(props.username(), props.password()));
                configured.setDefaultCredentialsProvider(creds);
            }
            return configured.setMaxConnTotal(props.maxConnTotal())
                    .setMaxConnPerRoute(props.maxConnPerRoute());
        });

        return builder.build();
    }

    private HttpAsyncClientBuilder configureHttp(HttpAsyncClientBuilder http) {
        return http;
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper()));
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }

    @Bean
    public ElasticsearchAsyncClient elasticsearchAsyncClient(ElasticsearchTransport transport) {
        return new ElasticsearchAsyncClient(transport);
    }

    private ObjectMapper objectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return m;
    }
}
