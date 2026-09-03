package com.claude.emqx.session;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/session")
public class SessionController {

    private final SessionDemo demo;
    public SessionController(SessionDemo demo) { this.demo = demo; }

    @PostMapping("/connect")
    public SessionDemo.ConnectResult connect(@RequestParam String clientId,
                                             @RequestParam(defaultValue = "true") boolean cleanStart,
                                             @RequestParam(defaultValue = "0") long sessionExpiry) throws Exception {
        return demo.connect(clientId, cleanStart, sessionExpiry);
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(@RequestParam String clientId) throws Exception {
        demo.disconnect(clientId);
        return Map.of("clientId", clientId, "disconnected", true);
    }

    @PostMapping("/publish-while-offline")
    public Map<String, Object> publish(@RequestParam String clientId,
                                       @RequestParam(defaultValue = "10") int count) throws Exception {
        demo.publishToOfflineClient(clientId, count);
        return Map.of("clientId", clientId, "published", count);
    }

    @GetMapping("/received")
    public Map<String, Object> received(@RequestParam String clientId) {
        return Map.of("clientId", clientId, "totalReceived", demo.receivedBy(clientId));
    }
}
