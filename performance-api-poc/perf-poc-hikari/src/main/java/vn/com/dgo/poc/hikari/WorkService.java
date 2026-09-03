package vn.com.dgo.poc.hikari;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WorkService {

    private final JdbcTemplate jdbc;

    public WorkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Giữ connection trong `workMs` ms — mô phỏng truy vấn nặng.
     * pg_sleep tốn connection thật sự, ko phải Thread.sleep ở app side.
     */
    public Long doWork(int workMs) {
        Double seconds = workMs / 1000.0;
        return jdbc.queryForObject(
                "select extract(epoch from clock_timestamp())::bigint from pg_sleep(?)",
                Long.class,
                seconds);
    }
}
