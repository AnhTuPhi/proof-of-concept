package com.example.bookingpoc.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
@ConditionalOnProperty(name = "booking.seat.hold-store", havingValue = "redis")
public class RedisConfig {

    /**
     * Compare-and-delete: only delete the key if its current value still
     * matches what we expect (prevents releasing a hold owned by someone
     * else after a TTL race).
     */
    @Bean
    public RedisScript<Long> softHoldReleaseScript() {
        String lua = """
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                  return redis.call('DEL', KEYS[1])
                else
                  return 0
                end
                """;
        return new DefaultRedisScript<>(lua, Long.class);
    }
}
