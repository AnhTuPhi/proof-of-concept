package com.claude.emqx.cluster;

import com.claude.emqx.common.util.Json;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

/**
 * REST surface for the cluster probe + a thin wrapper over EMQX cluster Mgmt API.
 * The Mgmt API at {@code /api/v5/cluster} shows each node's view of cluster
 * membership; comparing those views is how you detect split-brain at the broker
 * level (each side believes it's the cluster).
 */
@RestController
@RequestMapping("/cluster")
public class ClusterController {

    private final ClusterProbe probe;
    private final WebClient web = WebClient.builder().build();

    @Value("${cluster.mgmt-urls:http://localhost:18083,http://localhost:18084,http://localhost:18085}")
    private String mgmtUrlsCsv;
    @Value("${cluster.mgmt-user:admin}")
    private String mgmtUser;
    @Value("${cluster.mgmt-pass:public}")
    private String mgmtPass;

    public ClusterController(ClusterProbe probe) { this.probe = probe; }

    /** Live MQTT-level connectivity per node. */
    @GetMapping("/connectivity")
    public Map<String, Object> connectivity() throws Exception {
        return Map.of(
                "nodes", probe.nodeStatus(),
                "probe", probe.probe());
    }

    /**
     * Ask each EMQX node about its view of the cluster. If two nodes' lists
     * differ, you're in split-brain. (The standard "running_nodes" list is
     * authoritative.)
     */
    @GetMapping("/membership")
    @SuppressWarnings("unchecked")
    public Map<String, Object> membership() {
        Map<String, Object> out = new HashMap<>();
        for (String mgmtUrl : mgmtUrlsCsv.split(",")) {
            try {
                String body = web.get()
                        .uri(mgmtUrl.trim() + "/api/v5/cluster")
                        .headers(h -> h.setBasicAuth(mgmtUser, mgmtPass))
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
                out.put(mgmtUrl.trim(), Json.fromBytes(body.getBytes(), Map.class));
            } catch (Exception e) {
                out.put(mgmtUrl.trim(), Map.of("error", e.getMessage()));
            }
        }
        return out;
    }

    /** One-shot probe; useful in shell loops. */
    @PostMapping("/probe")
    public ClusterProbe.ProbeResult probeOnce() throws Exception {
        return probe.probe();
    }
}
