package com.example.bookingpoc.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Enables cross-pod scheduler locking via ShedLock + JDBC. Without this
 * config, every replica's {@code @Scheduled} fires independently — fine for
 * correctness (the underlying SQL is idempotent + uses row locks) but
 * wasteful at scale.
 *
 * <p>Activate with {@code booking.scheduler.distributed=true} AND have the
 * {@code shedlock} table available (the DDL is in data.sql with IF NOT EXISTS).
 *
 * <p>The {@code @SchedulerLock} annotation on the sweeper is a no-op until
 * this config is on the classpath active.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
@ConditionalOnProperty(name = "booking.scheduler.distributed", havingValue = "true")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
