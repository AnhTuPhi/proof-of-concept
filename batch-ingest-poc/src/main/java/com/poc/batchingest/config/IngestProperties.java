package com.poc.batchingest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ingest")
public class IngestProperties {

    /** Chunk commit interval for all chunk-oriented steps. */
    private int chunkSize = 1000;

    /** Number of partitions per partitioned job (worker steps). */
    private int gridSize = 8;

    /** Maximum number of skipped exceptions before the step fails. */
    private int skipLimit = 50;

    private ThreadPool threadPool = new ThreadPool();
    private Csv csv = new Csv();
    private Parquet parquet = new Parquet();
    private DbSource dbSource = new DbSource();

    @Data
    public static class ThreadPool {
        private int core = 8;
        private int max = 16;
        private int queue = 1000;
    }

    @Data
    public static class Csv {
        private String inputDir = "./data/csv";
        private String glob = "*.csv";
        private String encoding = "UTF-8";
        private boolean skipHeader = true;
    }

    @Data
    public static class Parquet {
        private String inputDir = "./data/parquet";
        private String glob = "*.parquet";
    }

    @Data
    public static class DbSource {
        private String table = "source_transactions";
        private String idColumn = "id";
        private int fetchSize = 5000;
    }
}
