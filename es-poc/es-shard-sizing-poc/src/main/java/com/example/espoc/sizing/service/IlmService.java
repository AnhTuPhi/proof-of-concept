package com.example.espoc.sizing.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.example.espoc.common.es.JsonResource;
import com.example.espoc.common.id.IdGenerators;
import com.example.espoc.common.web.ApiException;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Installs ILM policy + composable index template + the initial rollover-target index with the
 * write alias. After that, just write to the alias; ES handles rollover.
 */
@Service
public class IlmService {

    private static final Logger log = LoggerFactory.getLogger(IlmService.class);
    private static final String POLICY_NAME = "audit-policy";
    private static final String TEMPLATE_NAME = "audit-template";
    private static final String INITIAL_INDEX = "audit-000001";
    private static final String WRITE_ALIAS = "audit-write";

    private final ElasticsearchClient es;
    private final RestClient restClient;

    public IlmService(ElasticsearchClient es, RestClient restClient) {
        this.es = es;
        this.restClient = restClient;
    }

    public Map<String, Object> install() throws IOException {
        // The typed client's ILM API has shifted between 8.x revs — raw HTTP via the underlying
        // RestClient is the most version-stable path. We resolve the RestClient from the typed
        // transport and PUT the JSON bodies directly.
        rawPut("/_ilm/policy/" + POLICY_NAME, "es/audit-ilm-policy.json");
        rawPut("/_index_template/" + TEMPLATE_NAME, "es/audit-template.json");

        // 2. Initial rollover index with the alias attached as write target
        if (!es.indices().exists(e -> e.index(INITIAL_INDEX)).value()) {
            es.indices().create(c -> c.index(INITIAL_INDEX)
                    .aliases(WRITE_ALIAS, a -> a.isWriteIndex(true)));
            log.info("Created {} with write alias {}", INITIAL_INDEX, WRITE_ALIAS);
        }
        return Map.of("policy", POLICY_NAME, "template", TEMPLATE_NAME,
                "initialIndex", INITIAL_INDEX, "writeAlias", WRITE_ALIAS);
    }

    public Map<String, Object> load(int count) throws IOException {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<BulkOperation> ops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> doc = Map.of(
                    "timestamp", Instant.now().toString(),
                    "actor", "user-" + r.nextInt(1000),
                    "action", new String[]{"login", "view", "edit", "delete"}[r.nextInt(4)],
                    "resource", "doc-" + r.nextInt(100_000),
                    "outcome", r.nextInt(20) == 0 ? "failure" : "success");
            ops.add(BulkOperation.of(o -> o.index(idx -> idx.id(IdGenerators.ulid()).document(doc))));
        }
        es.bulk(BulkRequest.of(b -> b.index(WRITE_ALIAS).operations(ops)));
        return Map.of("written", count, "alias", WRITE_ALIAS);
    }

    private void rawPut(String path, String classpathJson) {
        try (var is = JsonResource.stream(classpathJson)) {
            Request request = new Request("PUT", path);
            request.setJsonEntity(new String(is.readAllBytes()));
            restClient.performRequest(request);
            log.info("PUT {}", path);
        } catch (IOException e) {
            throw ApiException.internal("ILM_PUT_FAILED", "PUT " + path + " failed", e);
        }
    }
}
