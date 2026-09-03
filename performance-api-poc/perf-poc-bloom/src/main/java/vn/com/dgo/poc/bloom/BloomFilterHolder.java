package vn.com.dgo.poc.bloom;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wrapper cho phép rebuild Bloom filter atomic mà không cần lock đọc.
 *
 * Lý do: filter có thể rebuild offline (batch nightly) hoặc khi insert mới;
 * lookup hot path chỉ swap reference.
 */
public class BloomFilterHolder {

    private final int expectedInsertions;
    private final double fpp;
    private final AtomicReference<BloomFilter<CharSequence>> ref;

    public BloomFilterHolder(int expectedInsertions, double fpp) {
        this.expectedInsertions = expectedInsertions;
        this.fpp = fpp;
        this.ref = new AtomicReference<>(newFilter());
    }

    private BloomFilter<CharSequence> newFilter() {
        return BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                expectedInsertions,
                fpp);
    }

    public void put(String key) {
        ref.get().put(key);
    }

    public boolean mightContain(String key) {
        return ref.get().mightContain(key);
    }

    public void rebuild(Iterable<String> keys) {
        BloomFilter<CharSequence> next = newFilter();
        for (String k : keys) {
            next.put(k);
        }
        ref.set(next);
    }

    public long approximateElementCount() {
        return ref.get().approximateElementCount();
    }

    public double expectedFpp() {
        return ref.get().expectedFpp();
    }
}
