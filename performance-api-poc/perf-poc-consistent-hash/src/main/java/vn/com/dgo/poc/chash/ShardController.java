package vn.com.dgo.poc.chash;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
@RequestMapping("/api")
public class ShardController {

    private final ConsistentHashRing ring;
    private final ModuloRouter modulo;
    private final RedisShardClient client;
    private final RemapAnalyzer analyzer;

    public ShardController(ConsistentHashRing ring,
                           ModuloRouter modulo,
                           RedisShardClient client,
                           RemapAnalyzer analyzer) {
        this.ring = ring;
        this.modulo = modulo;
        this.client = client;
        this.analyzer = analyzer;
    }

    // ============== Topology ==============

    @GetMapping("/topology")
    public Map<String, Object> topology() {
        return Map.of(
                "consistentHash", Map.of(
                        "physicalNodes", ring.nodes(),
                        "ringSize", ring.ringSize()),
                "modulo", Map.of(
                        "physicalNodes", modulo.nodes()));
    }

    @PostMapping("/topology/node")
    public ResponseEntity<Map<String, Object>> addNode(
            @RequestParam String name,
            @RequestParam String host,
            @RequestParam int port) {
        RedisNode node = new RedisNode(name, host, port);
        ring.addNode(node);
        modulo.addNode(node);
        return ResponseEntity.ok(Map.of("added", node, "ringSize", ring.ringSize()));
    }

    @DeleteMapping("/topology/node")
    public Map<String, Object> removeNode(@RequestParam String name) {
        RedisNode target = ring.nodes().stream()
                .filter(n -> n.name().equals(name))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return Map.of("removed", false);
        }
        ring.removeNode(target);
        modulo.removeNode(target);
        return Map.of("removed", true, "node", target);
    }

    // ============== Routing demo (real Redis) ==============

    @PostMapping("/keys/{key}")
    public Map<String, Object> put(@PathVariable String key, @RequestParam String value) {
        RedisNode node = ring.route(key);
        client.put(key, value);
        return Map.of("key", key, "routedTo", node);
    }

    @GetMapping("/keys/{key}")
    public Map<String, Object> get(@PathVariable String key) {
        RedisNode node = ring.route(key);
        return Map.of("key", key, "node", node, "value", client.get(key));
    }

    @PostMapping("/keys/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "10000") int count) {
        ring.nodes().forEach(client::flush);
        var rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            String k = "key-" + i + "-" + rnd.nextInt(100000);
            client.put(k, "v" + i);
        }
        return Map.of("seeded", count, "distribution", client.distribution());
    }

    @GetMapping("/distribution")
    public Map<String, Long> distribution() {
        return client.distribution();
    }

    // ============== Remap rate comparison (simulation) ==============

    @PostMapping("/simulate/add")
    public Map<String, Object> simulateAdd(
            @RequestParam(defaultValue = "10000") int sampleSize,
            @RequestParam(defaultValue = "candidate-X") String name,
            @RequestParam(defaultValue = "localhost") String host,
            @RequestParam(defaultValue = "6399") int port) {
        RedisNode candidate = new RedisNode(name, host, port);
        return Map.of(
                "consistentHash", analyzer.analyze(ring, sampleSize, candidate),
                "modulo", analyzer.analyze(modulo, sampleSize, candidate));
    }
}
