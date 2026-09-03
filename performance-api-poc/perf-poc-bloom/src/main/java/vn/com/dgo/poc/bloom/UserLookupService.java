package vn.com.dgo.poc.bloom;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class UserLookupService {

    private final UserRepository repository;
    private final BloomFilterHolder bloom;

    private final AtomicLong dbHitsWithBloom = new AtomicLong();
    private final AtomicLong dbHitsNoBloom = new AtomicLong();
    private final AtomicLong bloomRejects = new AtomicLong();
    private final AtomicLong falsePositives = new AtomicLong();

    public UserLookupService(UserRepository repository, BloomFilterHolder bloom) {
        this.repository = repository;
        this.bloom = bloom;
    }

    /**
     * Lookup KHÔNG dùng bloom — mỗi miss vẫn xuống DB.
     */
    public Optional<User> findWithoutBloom(String email) {
        dbHitsNoBloom.incrementAndGet();
        return repository.findByEmail(email);
    }

    /**
     * Lookup CÓ bloom — nếu bloom nói "chắc chắn không tồn tại" thì skip DB.
     * Nếu bloom nói "có thể tồn tại" thì xuống DB; có thể là false-positive.
     */
    public Optional<User> findWithBloom(String email) {
        if (!bloom.mightContain(email)) {
            bloomRejects.incrementAndGet();
            return Optional.empty();
        }
        dbHitsWithBloom.incrementAndGet();
        Optional<User> result = repository.findByEmail(email);
        if (result.isEmpty()) {
            falsePositives.incrementAndGet();
        }
        return result;
    }

    public void registerKey(String email) {
        bloom.put(email);
    }

    public void rebuildFromDb() {
        bloom.rebuild(repository.findAll().stream().map(User::getEmail).toList());
    }

    public Stats snapshot() {
        return new Stats(
                dbHitsNoBloom.get(),
                dbHitsWithBloom.get(),
                bloomRejects.get(),
                falsePositives.get(),
                bloom.approximateElementCount(),
                bloom.expectedFpp());
    }

    public void resetCounters() {
        dbHitsNoBloom.set(0);
        dbHitsWithBloom.set(0);
        bloomRejects.set(0);
        falsePositives.set(0);
    }

    public record Stats(
            long dbHitsNoBloom,
            long dbHitsWithBloom,
            long bloomRejects,
            long falsePositives,
            long bloomElementCount,
            double bloomExpectedFpp) {
    }
}
