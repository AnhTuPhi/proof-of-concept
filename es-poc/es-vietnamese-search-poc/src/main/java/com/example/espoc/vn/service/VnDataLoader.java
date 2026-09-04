package com.example.espoc.vn.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.common.id.IdGenerators;
import com.example.espoc.vn.config.VnProperties;
import com.example.espoc.vn.model.VnProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates synthetic Vietnamese products and indexes them into the configured indexes.
 * No external dictionaries — uses a small set of canonical product words mixed combinatorially.
 */
@Component
public class VnDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VnDataLoader.class);

    // Real Vietnamese product nouns. Mix of categories.
    private static final String[][] NAMES = {
            {"Điện thoại",  "điện thoại di động cao cấp",         "electronics"},
            {"Máy tính bảng","máy tính bảng màn hình lớn",         "electronics"},
            {"Cà phê",      "cà phê rang xay nguyên chất",          "grocery"},
            {"Áo sơ mi",    "áo sơ mi nam dài tay hàng hiệu",       "clothing"},
            {"Giày thể thao","giày thể thao nam nữ chính hãng",      "clothing"},
            {"Đèn pin",     "đèn pin LED sạc điện công suất cao",   "outdoors"},
            {"Bàn phím",    "bàn phím cơ chơi game RGB",            "electronics"},
            {"Nồi cơm điện","nồi cơm điện đa năng dung tích lớn",   "kitchen"},
            {"Sách giáo khoa","sách giáo khoa lớp 10 môn toán",     "books"},
            {"Đồng hồ thông minh","đồng hồ thông minh theo dõi sức khỏe","electronics"},
            {"Quần áo trẻ em","quần áo trẻ em cotton mềm mại",      "clothing"},
            {"Túi xách",    "túi xách da nữ thời trang Hàn Quốc",   "clothing"},
            {"Bình giữ nhiệt","bình giữ nhiệt 500ml inox",           "kitchen"},
            {"Bộ chăn ga",  "bộ chăn ga gối đệm cao cấp",            "kitchen"}
    };

    private final ElasticsearchClient es;
    private final IndexAdmin indexAdmin;
    private final VnProperties props;

    public VnDataLoader(ElasticsearchClient es, IndexAdmin indexAdmin, VnProperties props) {
        this.es = es;
        this.indexAdmin = indexAdmin;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        load(props.standardIndex(), "es/standard-mapping.json");
        load(props.foldedIndex(),   "es/folded-mapping.json");
        if (props.icuEnabled()) {
            load(props.icuIndex(), "es/icu-mapping.json");
        } else {
            log.info("ICU disabled — skipping {} (install analysis-icu plugin to enable)", props.icuIndex());
        }
    }

    private void load(String indexName, String mappingPath) throws IOException {
        try {
            indexAdmin.createIfMissing(indexName, mappingPath);
        } catch (Exception e) {
            log.error("Failed to create {} — does the analyzer plugin exist? {}", indexName, e.toString());
            return;
        }
        long existing = es.count(c -> c.index(indexName)).count();
        if (existing >= props.sampleSize()) {
            log.info("{} already has {} docs", indexName, existing);
            return;
        }
        List<BulkOperation> ops = new ArrayList<>(props.sampleSize());
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < props.sampleSize(); i++) {
            String[] row = NAMES[r.nextInt(NAMES.length)];
            VnProduct p = new VnProduct(IdGenerators.ulid(),
                    row[0] + " " + (i % 100),
                    row[1],
                    row[2],
                    r.nextLong(50_000, 20_000_000));
            ops.add(BulkOperation.of(o -> o.index(idx -> idx.id(p.id()).document(p))));
        }
        es.bulk(BulkRequest.of(b -> b.index(indexName).operations(ops)));
        es.indices().refresh(req -> req.index(indexName));
        log.info("Loaded {} docs into {}", props.sampleSize(), indexName);
    }
}
