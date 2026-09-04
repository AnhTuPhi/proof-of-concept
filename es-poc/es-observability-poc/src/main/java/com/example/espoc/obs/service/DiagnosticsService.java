package com.example.espoc.obs.service;

import com.example.espoc.common.web.ApiException;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps four ES diagnostic endpoints. We use the low-level RestClient because the typed client
 * doesn't surface {@code _nodes/hot_threads} (plain-text response) cleanly, and using one client
 * across all four keeps the wrapper small.
 */
@Service
public class DiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

    private final RestClient rest;
    @Value("${app.observability.index-name}") private String indexName;

    public DiagnosticsService(RestClient rest) { this.rest = rest; }

    /** Enable slow log with given thresholds (ms). 0 disables a level. */
    public Map<String, Object> enableSlowLog(long queryMs, long fetchMs) {
        String body = """
                {
                  "index.search.slowlog.threshold.query.warn":  "%dms",
                  "index.search.slowlog.threshold.query.info":  "%dms",
                  "index.search.slowlog.threshold.fetch.warn":  "%dms"
                }""".formatted(queryMs, queryMs / 2, fetchMs);
        return put("/" + indexName + "/_settings", body);
    }

    public Map<String, Object> disableSlowLog() {
        String body = """
                {
                  "index.search.slowlog.threshold.query.warn":  null,
                  "index.search.slowlog.threshold.query.info":  null,
                  "index.search.slowlog.threshold.fetch.warn":  null
                }""";
        return put("/" + indexName + "/_settings", body);
    }

    public Map<String, Object> hotThreads() {
        try {
            var resp = rest.performRequest(new Request("GET", "/_nodes/hot_threads"));
            String body = new String(resp.getEntity().getContent().readAllBytes());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("note", "Look at the bottom of each thread block — that's where time is being spent.");
            out.put("raw", body);
            return out;
        } catch (IOException e) {
            throw ApiException.internal("HOT_THREADS_FAILED", "hot threads call failed", e);
        }
    }

    public Map<String, Object> profile(String q) {
        String body = """
                {
                  "profile": true,
                  "size": 5,
                  "query": { "match": { "name": "%s" } }
                }
                """.formatted(q.replace("\"", "\\\""));
        Map<String, Object> result = post("/" + indexName + "/_search", body);
        result.put("note", "Look inside profile.shards[].searches[].query for clause-level nanos. " +
                "Skew across shards = skewed term distribution.");
        return result;
    }

    public Map<String, Object> diagnose() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clusterHealth", get("/_cluster/health"));
        out.put("nodesStats",    get("/_nodes/stats/jvm,indices,thread_pool"));
        out.put("catIndices",    rawText("/_cat/indices?v"));
        out.put("pendingTasks",  get("/_cluster/pending_tasks"));
        return out;
    }

    /* -- low-level helpers -- */

    private Map<String, Object> put(String path, String json) {
        try {
            Request r = new Request("PUT", path);
            r.setJsonEntity(json);
            var resp = rest.performRequest(r);
            return Map.of("status", resp.getStatusLine().getStatusCode(),
                    "body", new String(resp.getEntity().getContent().readAllBytes()));
        } catch (IOException e) {
            throw ApiException.internal("PUT_FAILED", "PUT " + path + " failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, String json) {
        try {
            Request r = new Request("POST", path);
            r.setJsonEntity(json);
            var resp = rest.performRequest(r);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(resp.getEntity().getContent(), Map.class);
        } catch (IOException e) {
            throw ApiException.internal("POST_FAILED", "POST " + path + " failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        try {
            var resp = rest.performRequest(new Request("GET", path));
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(resp.getEntity().getContent(), Map.class);
        } catch (IOException e) {
            log.warn("GET {} failed: {}", path, e.toString());
            return Map.of("error", e.getMessage());
        }
    }

    private String rawText(String path) {
        try {
            var resp = rest.performRequest(new Request("GET", path));
            return new String(resp.getEntity().getContent().readAllBytes());
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }
}
