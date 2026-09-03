package vn.com.dgo.poc.bloom;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "bloom")
public class BloomConfig {

    /** Số phần tử dự kiến — quyết định kích thước bitset. */
    private int expectedInsertions = 100_000;

    /** Tỷ lệ false-positive chấp nhận. Càng nhỏ càng tốn RAM. */
    private double falsePositiveProbability = 0.01;

    @Bean
    public BloomFilterHolder bloomFilterHolder() {
        return new BloomFilterHolder(expectedInsertions, falsePositiveProbability);
    }

    public int getExpectedInsertions() {
        return expectedInsertions;
    }

    public void setExpectedInsertions(int expectedInsertions) {
        this.expectedInsertions = expectedInsertions;
    }

    public double getFalsePositiveProbability() {
        return falsePositiveProbability;
    }

    public void setFalsePositiveProbability(double falsePositiveProbability) {
        this.falsePositiveProbability = falsePositiveProbability;
    }
}
