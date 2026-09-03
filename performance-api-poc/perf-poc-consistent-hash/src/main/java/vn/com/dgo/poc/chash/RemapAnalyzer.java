package vn.com.dgo.poc.chash;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sinh tập key giả lập, chụp ảnh "key → node" trước & sau khi topology đổi,
 * báo cáo phần trăm key bị đổi node.
 *
 * Đây là metric quan trọng nhất khi đánh giá consistent-hash:
 * thêm 1 node trong N → kỳ vọng ~1/(N+1) key phải remap (consistent-hash),
 * trong khi modulo router thì gần như TẤT CẢ key remap.
 */
@Component
public class RemapAnalyzer {

    public RemapReport analyze(KeyRouter router, int sampleSize, RedisNode nodeToAdd) {
        List<String> keys = randomKeys(sampleSize);
        Map<String, String> before = snapshot(router, keys);

        router.addNode(nodeToAdd);
        Map<String, String> after = snapshot(router, keys);
        router.removeNode(nodeToAdd);

        long remapped = countDiff(before, after);
        return new RemapReport(
                router.name(),
                sampleSize,
                router.nodes().size(),
                router.nodes().size() + 1,
                remapped,
                (double) remapped / sampleSize);
    }

    private Map<String, String> snapshot(KeyRouter router, List<String> keys) {
        Map<String, String> map = new HashMap<>(keys.size() * 2);
        for (String k : keys) {
            RedisNode r = router.route(k);
            map.put(k, r == null ? "" : r.identity());
        }
        return map;
    }

    private long countDiff(Map<String, String> a, Map<String, String> b) {
        long count = 0;
        for (Map.Entry<String, String> e : a.entrySet()) {
            if (!e.getValue().equals(b.get(e.getKey()))) {
                count++;
            }
        }
        return count;
    }

    private List<String> randomKeys(int n) {
        var rnd = ThreadLocalRandom.current();
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> "key-" + rnd.nextLong())
                .toList();
    }

    public record RemapReport(
            String router,
            int sampleSize,
            int nodesBefore,
            int nodesAfter,
            long remappedKeys,
            double remapRate) {
    }
}
