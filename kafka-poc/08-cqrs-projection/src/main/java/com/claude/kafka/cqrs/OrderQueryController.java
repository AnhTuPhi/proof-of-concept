package com.claude.kafka.cqrs;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Read-side API. The write side never knows this exists.
 * <pre>
 *   GET /orders/{id}
 *   GET /orders/by-customer?id=cust-1
 *   GET /orders/by-status?status=SHIPPED
 * </pre>
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderQueryController {

    private final ElasticsearchClient es;

    @GetMapping("/{id}")
    public OrderReadModel get(@PathVariable("id") String id) throws Exception {
        return es.get(g -> g.index(OrderProjector.INDEX).id(id), OrderReadModel.class).source();
    }

    @GetMapping("/by-customer")
    public List<OrderReadModel> byCustomer(@RequestParam("id") String id) throws Exception {
        SearchResponse<OrderReadModel> resp = es.search(s -> s
                        .index(OrderProjector.INDEX)
                        .query(q -> q.term(t -> t.field("customerId").value(id)))
                        .size(100),
                OrderReadModel.class);
        return resp.hits().hits().stream().map(h -> h.source()).toList();
    }

    @GetMapping("/by-status")
    public Map<String, Object> byStatus(@RequestParam String status) throws Exception {
        SearchResponse<OrderReadModel> resp = es.search(s -> s
                        .index(OrderProjector.INDEX)
                        .query(q -> q.term(t -> t.field("status").value(status)))
                        .size(50),
                OrderReadModel.class);
        return Map.of(
                "total", resp.hits().total() != null ? resp.hits().total().value() : 0,
                "results", resp.hits().hits().stream().map(h -> h.source()).toList()
        );
    }
}
