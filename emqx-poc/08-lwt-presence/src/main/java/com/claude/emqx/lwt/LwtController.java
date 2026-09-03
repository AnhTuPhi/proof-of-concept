package com.claude.emqx.lwt;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/lwt")
public class LwtController {

    private final LwtDeviceFactory devices;
    private final PresenceObserver observer;

    public LwtController(LwtDeviceFactory devices, PresenceObserver observer) {
        this.devices = devices; this.observer = observer;
    }

    @PostMapping("/spawn")
    public Map<String, Object> spawn(@RequestParam String deviceId,
                                     @RequestParam(defaultValue = "30") int keepAlive,
                                     @RequestParam(defaultValue = "5") int willDelay) throws Exception {
        devices.spawnDevice(deviceId, keepAlive, willDelay);
        return Map.of("deviceId", deviceId, "keepAlive", keepAlive, "willDelay", willDelay);
    }

    @PostMapping("/graceful")
    public Map<String, Object> graceful(@RequestParam String deviceId) throws Exception {
        return Map.of("disconnected", devices.gracefulShutdown(deviceId));
    }

    @PostMapping("/kill")
    public Map<String, Object> kill(@RequestParam String deviceId) throws Exception {
        return Map.of("killed", devices.hardKill(deviceId));
    }

    @GetMapping("/presence")
    public Map<String, String> presence() { return observer.snapshot(); }

    @GetMapping("/events")
    public List<String> events() { return observer.recentEvents(); }
}
