package com.example.espoc.reindex.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch.core.ReindexResponse;
import co.elastic.clients.elasticsearch.tasks.GetTasksResponse;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.web.ApiException;
import com.example.espoc.reindex.config.ReindexProperties;
import com.example.espoc.reindex.service.MigrationState.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Coordinates the v1 → v2 migration. Each phase transition is its own method so you can step through
 * manually (admin endpoints) or call {@link #start()} to run the whole flow.
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    private final ReindexProperties props;
    private final MigrationState state;

    public MigrationService(ElasticsearchClient es, IndexAdmin indexAdmin,
                            ReindexProperties props, MigrationState state) {
        this.es = es;
        this.indexAdmin = indexAdmin;
        this.props = props;
        this.state = state;
    }

    /**
     * Full pipeline: create v2 → enable dual-write → reindex → swap → drain dual-write.
     * Each step is recoverable: if reindex fails, you stay in DUAL_WRITE_ENABLED phase and can re-trigger.
     */
    public void start() {
        if (state.phase() != Phase.IDLE && state.phase() != Phase.COMPLETED && state.phase() != Phase.ROLLED_BACK) {
            throw ApiException.conflict("MIGRATION_IN_PROGRESS",
                    "Already in phase " + state.phase());
        }
        try {
            indexAdmin.createIfMissing(props.v2Index(), "es/products-v2-mapping.json");
            state.setPhase(Phase.DUAL_WRITE_ENABLED);
            log.info("Phase → DUAL_WRITE_ENABLED. New writes now go to both {} and {}", props.v1Index(), props.v2Index());

            runReindex();
        } catch (IOException e) {
            state.setLastError(e.getMessage());
            throw ApiException.internal("MIGRATION_FAILED", "Migration start failed", e);
        }
    }

    @Async
    public void runReindex() {
        try {
            state.setPhase(Phase.REINDEXING);
            log.info("Phase → REINDEXING ({} → {})", props.v1Index(), props.v2Index());

            // wait_for_completion=false so we can poll task progress.
            ReindexResponse resp = es.reindex(r -> r
                    .source(s -> s.index(props.v1Index()))
                    .dest(d -> d.index(props.v2Index()))
                    .conflicts(Conflicts.Proceed)
                    .waitForCompletion(false));

            String taskId = resp.task();
            state.setReindexTaskId(taskId);
            log.info("Reindex task started: {}", taskId);

            // Poll until done.
            while (true) {
                GetTasksResponse t = es.tasks().get(g -> g.taskId(taskId));
                if (t.task() != null && t.task().status() != null) {
                    long created = jsonLong(t.task().status().toString(), "created");
                    long updated = jsonLong(t.task().status().toString(), "updated");
                    state.setReindexProgress(created, updated);
                }
                if (Boolean.TRUE.equals(t.completed())) {
                    log.info("Reindex completed");
                    break;
                }
                Thread.sleep(500);
            }

            state.setPhase(Phase.READY_TO_SWAP);
            swap();
            // Brief settle time then turn off dual-write
            Thread.sleep(1000);
            complete();
        } catch (Exception e) {
            state.setLastError(e.getMessage());
            state.setPhase(Phase.DUAL_WRITE_ENABLED);
            log.error("Reindex flow failed — staying in DUAL_WRITE_ENABLED so you can retry", e);
        }
    }

    /** The atomic alias swap. This is the only step that affects production traffic. */
    public void swap() throws IOException {
        if (state.phase() != Phase.READY_TO_SWAP && state.phase() != Phase.REINDEXING) {
            throw ApiException.conflict("BAD_PHASE", "Cannot swap from phase " + state.phase());
        }
        indexAdmin.swapAlias(props.alias(), props.v1Index(), props.v2Index());
        state.setPhase(Phase.SWAPPED);
        log.info("Phase → SWAPPED. Alias {} → {}", props.alias(), props.v2Index());
    }

    /** Turn off dual-write. Old index (v1) remains for rollback. */
    public void complete() {
        state.setPhase(Phase.COMPLETED);
        log.info("Phase → COMPLETED. Dual-write off. {} retained for rollback.", props.v1Index());
    }

    /** Reverse the swap. v1 must still exist (and dual-write must have kept it current). */
    public void rollback() throws IOException {
        indexAdmin.swapAlias(props.alias(), props.v2Index(), props.v1Index());
        state.setPhase(Phase.ROLLED_BACK);
        log.warn("Rolled back. Alias {} → {}", props.alias(), props.v1Index());
    }

    /** After a successful migration window, drop v1. */
    public void deleteV1() throws IOException {
        if (state.phase() != Phase.COMPLETED) {
            throw ApiException.conflict("BAD_PHASE", "Refusing to delete v1 in phase " + state.phase());
        }
        indexAdmin.deleteIfExists(props.v1Index());
        log.info("Deleted {}", props.v1Index());
    }

    private long jsonLong(String json, String key) {
        // Cheap path: status is a Map → toString. For a real impl, deserialize via Jackson.
        int i = json.indexOf("\"" + key + "\"=");
        if (i < 0) return -1;
        int s = i + key.length() + 3;
        int e = json.indexOf(',', s);
        if (e < 0) e = json.indexOf('}', s);
        try { return Long.parseLong(json.substring(s, e).trim()); }
        catch (Exception ex) { return -1; }
    }
}
