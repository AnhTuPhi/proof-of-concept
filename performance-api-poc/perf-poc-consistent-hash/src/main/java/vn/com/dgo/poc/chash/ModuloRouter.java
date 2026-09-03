package vn.com.dgo.poc.chash;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Naive `hash(key) % N` router để so sánh.
 *
 * Khi thay đổi N, hầu hết key bị remap — đây là vấn đề mà consistent-hash giải quyết.
 */
public class ModuloRouter implements KeyRouter {

    private final List<RedisNode> nodes = new ArrayList<>();
    private final HashFunction hash = Hashing.murmur3_128();

    @Override
    public synchronized void addNode(RedisNode node) {
        if (!nodes.contains(node)) {
            nodes.add(node);
        }
    }

    @Override
    public synchronized void removeNode(RedisNode node) {
        nodes.remove(node);
    }

    @Override
    public RedisNode route(String key) {
        List<RedisNode> snapshot = nodes();
        if (snapshot.isEmpty()) {
            return null;
        }
        long h = hash.hashString(key, StandardCharsets.UTF_8).asLong();
        int idx = Math.floorMod(h, snapshot.size());
        return snapshot.get(idx);
    }

    @Override
    public synchronized List<RedisNode> nodes() {
        return List.copyOf(nodes);
    }

    @Override
    public String name() {
        return "modulo";
    }
}
