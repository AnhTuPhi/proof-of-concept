package com.example.espoc.facets.controller;

import com.example.espoc.facets.dto.SearchRequestDto;
import com.example.espoc.facets.dto.SearchResponseDto;
import com.example.espoc.facets.service.SearchService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
public class ProductSearchController {

    private final SearchService svc;

    public ProductSearchController(SearchService svc) { this.svc = svc; }

    @GetMapping("/search")
    public SearchResponseDto search(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) String brand,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String priceBucket,
                                    @RequestParam(required = false) Integer minRating,
                                    @RequestParam(defaultValue = "20") int size) {
        return svc.search(new SearchRequestDto(q, brand, category, priceBucket, minRating, size));
    }
}
