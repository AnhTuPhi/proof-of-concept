package com.example.espoc.sizing.controller;

import com.example.espoc.sizing.service.IlmService;
import com.example.espoc.sizing.service.MappingExplosionService;
import com.example.espoc.sizing.service.ShardSizingCalculator;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sizing")
public class SizingController {

    private final ShardSizingCalculator calc;
    private final IlmService ilm;
    private final MappingExplosionService explode;

    public SizingController(ShardSizingCalculator calc, IlmService ilm, MappingExplosionService explode) {
        this.calc = calc; this.ilm = ilm; this.explode = explode;
    }

    @GetMapping("/calculator")
    public Map<String, Object> calculator(@RequestParam double dailyGb,
                                          @RequestParam int retentionDays,
                                          @RequestParam(defaultValue = "30") double targetShardGb,
                                          @RequestParam(defaultValue = "3")  int nodes,
                                          @RequestParam(defaultValue = "16") int heapGb) {
        return calc.calculate(dailyGb, retentionDays, targetShardGb, nodes, heapGb);
    }

    @PostMapping("/ilm/install")
    public Map<String, Object> ilmInstall() throws IOException { return ilm.install(); }

    @PostMapping("/ilm/load")
    public Map<String, Object> ilmLoad(@RequestParam(defaultValue = "10000") int count) throws IOException {
        return ilm.load(count);
    }

    @PostMapping("/explode-mapping")
    public Map<String, Object> explode(@RequestParam(defaultValue = "1500") int keys) throws IOException {
        return explode.explode(keys);
    }

    @PostMapping("/explode-mapping/fix")
    public Map<String, Object> explodeFix(@RequestParam(defaultValue = "1500") int keys) throws IOException {
        return explode.fix(keys);
    }
}
