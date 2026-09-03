package vn.com.poc.realtime.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcServerConfig implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    private final int port;
    private final PriceStreamingServiceImpl service;
    private Server server;

    public GrpcServerConfig(@Value("${grpc.port:9090}") int port,
                            PriceStreamingServiceImpl service) {
        this.port = port;
        this.service = service;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            server = ServerBuilder.forPort(port)
                    .addService(service)
                    .build()
                    .start();
            log.info("gRPC server started on port {}", port);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gRPC server on port " + port, e);
        }
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (server != null) {
            log.info("Shutting down gRPC server...");
            server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
