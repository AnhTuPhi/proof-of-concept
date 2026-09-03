package vn.com.poc.realtime.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.poc.realtime.grpc.proto.PriceStreamServiceGrpc;
import vn.com.poc.realtime.grpc.proto.PriceUpdate;
import vn.com.poc.realtime.grpc.proto.SubscribeRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Browser cannot speak raw gRPC (would need grpc-web proxy). This controller
 * opens a gRPC client to the local server, collects N updates, and returns
 * them as plain JSON so the demo page can show the gRPC pipe end-to-end.
 */
@RestController
@RequestMapping("/api/grpc")
public class GrpcDemoController {

    private final int port;

    public GrpcDemoController(@Value("${grpc.port:9090}") int port) {
        this.port = port;
    }

    @GetMapping("/demo")
    public Map<String, Object> demo(
            @RequestParam(defaultValue = "10") int count,
            @RequestParam(defaultValue = "VND,FPT,HPG") String symbols) throws InterruptedException {

        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
        try {
            PriceStreamServiceGrpc.PriceStreamServiceStub stub = PriceStreamServiceGrpc.newStub(channel);
            List<PriceUpdate> collected = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(count);

            StreamObserver<PriceUpdate> responseObs = new StreamObserver<>() {
                @Override
                public void onNext(PriceUpdate u) {
                    synchronized (collected) {
                        if (collected.size() < count) {
                            collected.add(u);
                            latch.countDown();
                        }
                    }
                }

                @Override
                public void onError(Throwable t) {
                    while (latch.getCount() > 0) latch.countDown();
                }

                @Override
                public void onCompleted() {
                    // server closed
                }
            };

            StreamObserver<SubscribeRequest> requestObs = stub.streamPrices(responseObs);
            requestObs.onNext(SubscribeRequest.newBuilder()
                    .setAction(SubscribeRequest.Action.SUBSCRIBE)
                    .addAllSymbols(Arrays.asList(symbols.split(",")))
                    .build());

            boolean ok = latch.await(15, TimeUnit.SECONDS);
            try {
                requestObs.onCompleted();
            } catch (Exception ignored) {
                // already closed
            }

            List<Map<String, Object>> data;
            synchronized (collected) {
                data = collected.stream().map(this::toJson).toList();
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", ok);
            resp.put("count", data.size());
            resp.put("requestedCount", count);
            resp.put("symbols", symbols);
            resp.put("data", data);
            return resp;
        } finally {
            channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private Map<String, Object> toJson(PriceUpdate u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", u.getSymbol());
        m.put("price", u.getPrice());
        m.put("change", u.getChange());
        m.put("changePercent", u.getChangePercent());
        m.put("volume", u.getVolume());
        m.put("ts", u.getTimestampMillis());
        return m;
    }
}
