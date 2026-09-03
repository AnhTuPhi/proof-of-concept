package vn.com.dgo.poc.chash;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý JedisPool per node, route bằng KeyRouter.
 * Nếu node được add sau khi app khởi động, pool sẽ được tạo lazily.
 */
@Component
public class RedisShardClient {

    private final ConsistentHashRing ring;
    private final Map<String, JedisPool> pools = new ConcurrentHashMap<>();

    public RedisShardClient(ConsistentHashRing ring) {
        this.ring = ring;
    }

    public void put(String key, String value) {
        try (Jedis jedis = poolFor(ring.route(key)).getResource()) {
            jedis.set(key, value);
        }
    }

    public String get(String key) {
        try (Jedis jedis = poolFor(ring.route(key)).getResource()) {
            return jedis.get(key);
        }
    }

    public long countKeys(RedisNode node) {
        try (Jedis jedis = poolFor(node).getResource()) {
            return jedis.dbSize();
        }
    }

    public Map<String, Long> distribution() {
        Map<String, Long> result = new HashMap<>();
        for (RedisNode node : ring.nodes()) {
            try {
                result.put(node.name(), countKeys(node));
            } catch (Exception e) {
                result.put(node.name(), -1L);
            }
        }
        return result;
    }

    public void flush(RedisNode node) {
        try (Jedis jedis = poolFor(node).getResource()) {
            jedis.flushDB();
        }
    }

    private JedisPool poolFor(RedisNode node) {
        return pools.computeIfAbsent(node.identity(), id -> {
            JedisPoolConfig cfg = new JedisPoolConfig();
            cfg.setMaxTotal(32);
            cfg.setMaxIdle(8);
            return new JedisPool(cfg, node.host(), node.port(), 2000);
        });
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(p -> {
            try {
                p.close();
            } catch (Exception ignored) {
                // best-effort
            }
        });
    }
}
