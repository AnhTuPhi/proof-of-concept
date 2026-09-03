package vn.com.poc.realtime.websocket;

import jakarta.annotation.PostConstruct;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import vn.com.poc.realtime.service.PriceEventBus;

@Component
public class PriceBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final PriceEventBus bus;

    public PriceBroadcaster(SimpMessagingTemplate messagingTemplate, PriceEventBus bus) {
        this.messagingTemplate = messagingTemplate;
        this.bus = bus;
    }

    @PostConstruct
    void init() {
        bus.subscribe(price -> {
            messagingTemplate.convertAndSend("/topic/prices", price);
            messagingTemplate.convertAndSend("/topic/prices/" + price.symbol(), price);
        });
    }

    @MessageMapping("/ping")
    @SendTo("/topic/pong")
    public String ping(String msg) {
        return "pong: " + msg + " @ " + System.currentTimeMillis();
    }
}
