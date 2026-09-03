package com.claude.emqx.shadow;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/shadow")
public class ShadowController {

    private final DeviceShadow shadow;
    public ShadowController(DeviceShadow shadow) { this.shadow = shadow; }

    /** Backend sets desired state. Shadow publishes delta on the MQTT side. */
    @PostMapping("/desired")
    public Map<String, Object> setDesired(@RequestParam String deviceId,
                                          @RequestBody Map<String, Object> desired) throws Exception {
        shadow.setDesired(deviceId, desired);
        return Map.of("deviceId", deviceId, "desired", desired);
    }

    @GetMapping("/snapshot")
    public Map<String, Object> get(@RequestParam String deviceId) {
        return shadow.snapshot(deviceId);
    }
}
