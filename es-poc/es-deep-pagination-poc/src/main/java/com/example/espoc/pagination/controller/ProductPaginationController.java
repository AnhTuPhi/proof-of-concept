package com.example.espoc.pagination.controller;

import com.example.espoc.common.web.CursorPageResponse;
import com.example.espoc.common.web.PageResponse;
import com.example.espoc.pagination.model.ProductDoc;
import com.example.espoc.pagination.service.FromSizePaginationService;
import com.example.espoc.pagination.service.PitExportService;
import com.example.espoc.pagination.service.SearchAfterPaginationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductPaginationController {

    private final FromSizePaginationService fromSizeSvc;
    private final SearchAfterPaginationService searchAfterSvc;
    private final PitExportService pitSvc;

    public ProductPaginationController(FromSizePaginationService fromSizeSvc,
                                       SearchAfterPaginationService searchAfterSvc,
                                       PitExportService pitSvc) {
        this.fromSizeSvc = fromSizeSvc;
        this.searchAfterSvc = searchAfterSvc;
        this.pitSvc = pitSvc;
    }

    @GetMapping("/page")
    public PageResponse<ProductDoc> page(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return fromSizeSvc.page(page, size);
    }

    @GetMapping("/scroll")
    public CursorPageResponse<ProductDoc> scroll(@RequestParam(required = false) String cursor,
                                                 @RequestParam(defaultValue = "20") int size) {
        return searchAfterSvc.page(cursor, size);
    }

    @GetMapping("/export")
    public CursorPageResponse<ProductDoc> export(@RequestParam(required = false) String cursor,
                                                 @RequestParam(defaultValue = "500") int size) {
        return pitSvc.nextChunk(cursor, size);
    }
}
