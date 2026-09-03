package com.poc.upload.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.util.List;

@Configuration
public class S3Config {

    private static final Logger log = LoggerFactory.getLogger(S3Config.class);

    @Value("${poc.s3.endpoint}")
    private String endpoint;

    @Value("${poc.s3.region}")
    private String region;

    @Value("${poc.s3.bucket}")
    private String bucketName;

    @Value("${poc.s3.access-key}")
    private String accessKey;

    @Value("${poc.s3.secret-key}")
    private String secretKey;

    @Value("${poc.s3.path-style}")
    private boolean pathStyle;

    public String bucket() {
        return bucketName;
    }

    @Bean(destroyMethod = "close")
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build())
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build())
                .build();
    }

    @Bean
    public CommandLineRunner s3Bootstrap(S3Client s3) {
        return args -> {
            try {
                ensureBucket(s3);
                configureCors(s3);
                log.info("S3 ready: endpoint={} bucket={}", endpoint, bucketName);
            } catch (Exception e) {
                log.warn("S3 bootstrap failed (is MinIO running at {}?): {}", endpoint, e.getMessage());
                log.warn("S3 mode in the UI will fail until MinIO is reachable. Local mode still works.");
            }
        };
    }

    private void ensureBucket(S3Client s3) {
        boolean exists = s3.listBuckets().buckets().stream()
                .anyMatch(b -> b.name().equals(bucketName));
        if (!exists) {
            log.info("Creating bucket {}", bucketName);
            s3.createBucket(b -> b.bucket(bucketName));
        }
    }

    private void configureCors(S3Client s3) {
        CORSRule rule = CORSRule.builder()
                .allowedOrigins(List.of("*"))
                .allowedMethods(List.of("GET", "PUT", "POST", "HEAD"))
                .allowedHeaders(List.of("*"))
                .exposeHeaders(List.of("ETag", "Content-Length", "Content-Range", "Accept-Ranges"))
                .maxAgeSeconds(3000)
                .build();
        s3.putBucketCors(b -> b.bucket(bucketName)
                .corsConfiguration(c -> c.corsRules(rule)));
        log.info("CORS policy applied to bucket {}", bucketName);
    }
}
