package com.claude.emqx.mqtt5;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/mqtt5")
public class Mqtt5Controller {
    private final Mqtt5Demo demo;
    public Mqtt5Controller(Mqtt5Demo demo) { this.demo = demo; }

    @PostMapping("/request")
    public Map<String, Object> request(@RequestParam(defaultValue = "ping") String body,
                                       @RequestParam(defaultValue = "trace-1") String traceId)
            throws Exception {
        String resp = demo.request("rpc/device-001/req", "rpc/device-001/resp", body,
                Map.of("traceId", traceId, "tenant", "tenant-a")).get(5, TimeUnit.SECONDS);
        return Map.of("response", resp, "traceId", traceId);
    }

    @GetMapping("/reasons")
    public List<String> reasons() { return demo.reasonHistory(); }
}
