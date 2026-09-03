package vn.com.dgo.poc.chash;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Consistent hash ring với virtual nodes.
 *
 * Mỗi physical node được nhân lên thành N điểm trên vòng tròn 64-bit, phân bố tải đều hơn.
 * Khi thêm/bớt 1 node, chỉ K/N keys phải remap (K = tổng key, N = số node).
 */
public class ConsistentHashRing implements KeyRouter {

    private final int virtualNodesPerNode;
    private final TreeMap<Long, RedisNode> ring = new TreeMap<>();
    private final List<RedisNode> physicalNodes = new ArrayList<>();
    private final HashFunction hash = Hashing.murmur3_128();

    public ConsistentHashRing(int virtualNodesPerNode) {
        this.virtualNodesPerNode = virtualNodesPerNode;
    }

    @Override
    public synchronized void addNode(RedisNode node) {
        if (physicalNodes.contains(node)) {
            return;
        }
        physicalNodes.add(node);
        for (int i = 0; i < virtualNodesPerNode; i++) {
            ring.put(hashOf(node.identity() + "#" + i), node);
        }
    }

    @Override
    public synchronized void removeNode(RedisNode node) {
        if (!physicalNodes.remove(node)) {
            return;
        }
        for (int i = 0; i < virtualNodesPerNode; i++) {
            ring.remove(hashOf(node.identity() + "#" + i));
        }
    }

    @Override
    public RedisNode route(String key) {
        if (ring.isEmpty()) {
            return null;
        }
        long h = hashOf(key);
        Map.Entry<Long, RedisNode> entry = ring.ceilingEntry(h);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        return entry.getValue();
    }

    @Override
    public synchronized List<RedisNode> nodes() {
        return List.copyOf(physicalNodes);
    }

    @Override
    public String name() {
        return "consistent-hash";
    }

    public int ringSize() {
        return ring.size();
    }

    private long hashOf(String s) {
        return hash.hashString(s, StandardCharsets.UTF_8).asLong();
    }
}
