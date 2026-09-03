package com.claude.emqx.ota;

import com.claude.emqx.common.client.MqttClientProperties;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/ota")
public class OtaController {

    private final OtaServer server;
    private final MqttClientProperties props;
    private final Map<String, SimulatedDevice> devices = new ConcurrentHashMap<>();
    private final Random rng = new Random();

    public OtaController(OtaServer server, MqttClientProperties props) {
        this.server = server;
        this.props = props;
    }

    /**
     * Publish a campaign with a randomly-generated firmware blob.
     * sizeKb controls the image size; chunkSize default 4KB keeps a single chunk
     * well under EMQX's default 256KB max-packet limit.
     */
    @PostMapping("/campaign")
    public Map<String, Object> campaign(@RequestParam String targetClass,
                                        @RequestParam String version,
                                        @RequestParam(defaultValue = "256") int sizeKb,
                                        @RequestParam(defaultValue = "4096") int chunkSize) throws MqttException {
        byte[] image = new byte[sizeKb * 1024];
        rng.nextBytes(image);
        Firmware fw = server.publishCampaign(targetClass, version, image, chunkSize);
        return Map.of(
                "version", fw.version(),
                "sha256",  fw.sha256(),
                "size",    fw.bytes().length,
                "chunkSize", fw.chunkSize(),
                "totalChunks", fw.totalChunks());
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestParam String targetClass) throws MqttException {
        server.cancelCampaign(targetClass);
        return Map.of("targetClass", targetClass, "result", "cancelled");
    }

    @PostMapping("/device")
    public Map<String, Object> device(@RequestParam String deviceId, @RequestParam String targetClass) throws MqttException {
        SimulatedDevice d = new SimulatedDevice(deviceId, targetClass, props);
        d.connect();
        devices.put(deviceId, d);
        return Map.of("deviceId", deviceId, "class", targetClass);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new HashMap<>();
        Map<String, Object> deviceView = new HashMap<>();
        devices.forEach((id, d) -> deviceView.put(id, Map.of(
                "active",   d.activeVersion(),
                "pending",  d.pendingVersion() == null ? "" : d.pendingVersion(),
                "received", d.receivedCount())));
        out.put("devices", deviceView);
        out.put("serverProgress", server.progress());
        out.put("catalog", server.catalog().keySet());
        return out;
    }
}
