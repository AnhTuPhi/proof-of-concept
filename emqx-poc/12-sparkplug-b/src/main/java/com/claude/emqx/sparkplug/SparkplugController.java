package com.claude.emqx.sparkplug;

import com.claude.emqx.common.client.MqttClientProperties;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo driver. Spawn edge nodes, push data, simulate ungraceful disconnect to
 * see the broker fire NDEATH on the node's behalf.
 */
@RestController
@RequestMapping("/sparkplug")
public class SparkplugController {

    private final MqttClientProperties props;
    private final SparkplugHostApplication host;
    private final Map<String, SparkplugEdgeNode> nodes = new ConcurrentHashMap<>();

    public SparkplugController(MqttClientProperties props, SparkplugHostApplication host) {
        this.props = props;
        this.host  = host;
    }

    @PostMapping("/spawn")
    public Map<String, Object> spawn(@RequestParam String group, @RequestParam String node) throws MqttException {
        SparkplugEdgeNode n = new SparkplugEdgeNode(group, node, props);
        n.online();
        nodes.put(key(group, node), n);
        return Map.of("group", group, "node", node, "bdSeq", n.bdSeq());
    }

    @PostMapping("/data")
    public Map<String, Object> data(@RequestParam String group, @RequestParam String node,
                                    @RequestParam String metric, @RequestParam double value) throws MqttException {
        SparkplugEdgeNode n = require(group, node);
        n.publishData(PayloadCodec.metric(metric, SparkplugDataType.DOUBLE, value));
        return Map.of("group", group, "node", node, "metric", metric, "value", value);
    }

    @PostMapping("/device-birth")
    public Map<String, Object> deviceBirth(@RequestParam String group, @RequestParam String node,
                                           @RequestParam String device) throws MqttException {
        SparkplugEdgeNode n = require(group, node);
        n.deviceBirth(device,
                PayloadCodec.metric("Properties/SerialNo", SparkplugDataType.STRING, "SN-" + device),
                PayloadCodec.metric("Inputs/Temperature", SparkplugDataType.DOUBLE, 20.0),
                PayloadCodec.metric("Outputs/Heater", SparkplugDataType.BOOLEAN, false));
        return Map.of("group", group, "node", node, "device", device);
    }

    @PostMapping("/offline")
    public Map<String, Object> offline(@RequestParam String group, @RequestParam String node) throws MqttException {
        SparkplugEdgeNode n = require(group, node);
        n.offline();
        nodes.remove(key(group, node));
        return Map.of("group", group, "node", node, "result", "graceful NDEATH published");
    }

    /**
     * Simulates pulling the power cord: drop the TCP socket without sending
     * DISCONNECT. The broker holds the NDEATH we registered as LWT and will fire
     * it after keep-alive×1.5 elapses (≈45s with default config).
     */
    @PostMapping("/kill")
    public Map<String, Object> kill(@RequestParam String group, @RequestParam String node) {
        SparkplugEdgeNode n = require(group, node);
        try {
            n.client().disconnectForcibly(0, 0, false);  // immediate TCP close
        } catch (Exception e) {
            // expected — we just yanked the cord
        }
        nodes.remove(key(group, node));
        return Map.of("group", group, "node", node, "result", "TCP dropped, waiting for LWT");
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> out = new HashMap<>();
        host.snapshot().forEach((k, v) -> out.put(k, Map.of(
                "alive", v.alive,
                "bdSeq", v.bdSeq,
                "lastSeq", v.lastSeq,
                "metrics", v.metrics,
                "devices", v.devices.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> Map.of("alive", e.getValue().alive, "metrics", e.getValue().metrics))))));
        return out;
    }

    private SparkplugEdgeNode require(String group, String node) {
        var n = nodes.get(key(group, node));
        if (n == null) throw new IllegalStateException("no such node — spawn it first: " + group + "/" + node);
        return n;
    }

    private static String key(String group, String node) { return group + "/" + node; }
}
