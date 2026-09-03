package vn.com.dgo.poc.hikari;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WorkController {

    private final WorkService work;
    private final LoadRunner runner;
    private final DataSource dataSource;

    public WorkController(WorkService work, LoadRunner runner, DataSource dataSource) {
        this.work = work;
        this.runner = runner;
        this.dataSource = dataSource;
    }

    @GetMapping("/work")
    public Map<String, Object> single(@RequestParam(defaultValue = "100") int ms) {
        long t0 = System.nanoTime();
        work.doWork(ms);
        return Map.of("workMs", ms, "elapsedMs", (System.nanoTime() - t0) / 1_000_000);
    }

    @PostMapping("/load")
    public LoadRunner.LoadReport load(
            @RequestParam(defaultValue = "20") int concurrency,
            @RequestParam(defaultValue = "200") int total,
            @RequestParam(defaultValue = "100") int workMs) throws InterruptedException {
        return runner.run(concurrency, total, workMs);
    }

    @GetMapping("/pool")
    public Map<String, Object> poolStats() {
        if (!(dataSource instanceof HikariDataSource hds)) {
            return Map.of("error", "not a HikariDataSource");
        }
        var bean = hds.getHikariPoolMXBean();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("poolName", hds.getPoolName());
        result.put("maximumPoolSize", hds.getMaximumPoolSize());
        result.put("minimumIdle", hds.getMinimumIdle());
        result.put("connectionTimeout", hds.getConnectionTimeout());
        result.put("leakDetectionThreshold", hds.getLeakDetectionThreshold());
        result.put("activeConnections", bean.getActiveConnections());
        result.put("idleConnections", bean.getIdleConnections());
        result.put("totalConnections", bean.getTotalConnections());
        result.put("threadsAwaitingConnection", bean.getThreadsAwaitingConnection());
        return result;
    }
}
