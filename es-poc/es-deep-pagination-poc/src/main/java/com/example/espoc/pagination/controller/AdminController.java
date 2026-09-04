package com.example.espoc.pagination.controller;

import com.example.espoc.common.es.IndexAdmin;
import com.example.espoc.pagination.config.PaginationProperties;
import com.example.espoc.pagination.service.DataLoader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final DataLoader loader;
    private final IndexAdmin indexAdmin;
    private final PaginationProperties props;

    public AdminController(DataLoader loader, IndexAdmin indexAdmin, PaginationProperties props) {
        this.loader = loader;
        this.indexAdmin = indexAdmin;
        this.props = props;
    }

    @PostMapping("/reload")
    public Map<String, Object> reload(@RequestParam(defaultValue = "1000000") long count) throws IOException {
        indexAdmin.deleteIfExists(props.indexName());
        indexAdmin.createIfMissing(props.indexName(), "es/products-mapping.json");
        loader.load(0, count);
        return Map.of("loaded", count);
    }
}
