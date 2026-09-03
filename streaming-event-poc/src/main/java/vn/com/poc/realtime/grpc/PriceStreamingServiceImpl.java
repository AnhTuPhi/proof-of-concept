package vn.com.poc.realtime.grpc;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import vn.com.poc.realtime.grpc.proto.PriceStreamServiceGrpc;
import vn.com.poc.realtime.grpc.proto.PriceUpdate;
import vn.com.poc.realtime.grpc.proto.SubscribeRequest;
import vn.com.poc.realtime.grpc.proto.WatchRequest;
import vn.com.poc.realtime.model.StockPrice;
import vn.com.poc.realtime.service.PriceEventBus;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PriceStreamingServiceImpl extends PriceStreamServiceGrpc.PriceStreamServiceImplBase {

    private final PriceEventBus bus;

    public PriceStreamingServiceImpl(PriceEventBus bus) {
        this.bus = bus;
    }

    @Override
    public void watch(WatchRequest req, StreamObserver<PriceUpdate> responseObserver) {
        Set<String> filter = new HashSet<>(req.getSymbolsList());
        AtomicBoolean active = new AtomicBoolean(true);

        Runnable[] unsubHolder = new Runnable[1];
        unsubHolder[0] = bus.subscribe(price -> {
            if (!active.get()) return;
            if (!filter.isEmpty() && !filter.contains(price.symbol())) return;
            try {
                responseObserver.onNext(toProto(price));
            } catch (Exception e) {
                if (active.compareAndSet(true, false)) {
                    unsubHolder[0].run();
                }
            }
        });

        if (responseObserver instanceof ServerCallStreamObserver<PriceUpdate> sc) {
            sc.setOnCancelHandler(() -> {
                if (active.compareAndSet(true, false)) {
                    unsubHolder[0].run();
                }
            });
        }
    }

    @Override
    public StreamObserver<SubscribeRequest> streamPrices(StreamObserver<PriceUpdate> responseObserver) {
        Set<String> filter = Collections.synchronizedSet(new HashSet<>());
        AtomicBoolean active = new AtomicBoolean(true);

        Runnable[] unsubHolder = new Runnable[1];
        unsubHolder[0] = bus.subscribe(price -> {
            if (!active.get()) return;
            boolean match;
            synchronized (filter) {
                match = filter.isEmpty() || filter.contains(price.symbol());
            }
            if (!match) return;
            try {
                responseObserver.onNext(toProto(price));
            } catch (Exception e) {
                if (active.compareAndSet(true, false)) {
                    unsubHolder[0].run();
                }
            }
        });

        if (responseObserver instanceof ServerCallStreamObserver<PriceUpdate> sc) {
            sc.setOnCancelHandler(() -> {
                if (active.compareAndSet(true, false)) {
                    unsubHolder[0].run();
                }
            });
        }

        return new StreamObserver<>() {
            @Override
            public void onNext(SubscribeRequest req) {
                synchronized (filter) {
                    if (req.getAction() == SubscribeRequest.Action.SUBSCRIBE) {
                        filter.addAll(req.getSymbolsList());
                    } else if (req.getAction() == SubscribeRequest.Action.UNSUBSCRIBE) {
                        req.getSymbolsList().forEach(filter::remove);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                if (active.compareAndSet(true, false)) {
                    unsubHolder[0].run();
                }
            }

            @Override
            public void onCompleted() {
                if (active.compareAndSet(true, false)) {
                    unsubHolder[0].run();
                }
                try {
                    responseObserver.onCompleted();
                } catch (Exception ignored) {
                    // stream may already be closed
                }
            }
        };
    }

    private PriceUpdate toProto(StockPrice price) {
        return PriceUpdate.newBuilder()
                .setSymbol(price.symbol())
                .setPrice(price.price().doubleValue())
                .setChange(price.change().doubleValue())
                .setChangePercent(price.changePercent().doubleValue())
                .setTimestampMillis(price.timestamp().toEpochMilli())
                .setVolume(price.volume())
                .build();
    }
}
