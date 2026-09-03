package com.vndirect.poc.merge.web;

import com.vndirect.poc.merge.domain.*;
import com.vndirect.poc.merge.repo.*;
import com.vndirect.poc.merge.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/merge")
@CrossOrigin
public class AccountMergeController {

    private final AccountMergeService mergeService;
    private final UserRepository userRepo;
    private final AuthMethodRepository authRepo;
    private final TradeOrderRepository orderRepo;
    private final WatchlistRepository watchlistRepo;
    private final AuditEventRepository auditRepo;
    private final SeedData seed;

    public AccountMergeController(AccountMergeService mergeService,
                                  UserRepository userRepo,
                                  AuthMethodRepository authRepo,
                                  TradeOrderRepository orderRepo,
                                  WatchlistRepository watchlistRepo,
                                  AuditEventRepository auditRepo,
                                  SeedData seed) {
        this.mergeService = mergeService;
        this.userRepo = userRepo;
        this.authRepo = authRepo;
        this.orderRepo = orderRepo;
        this.watchlistRepo = watchlistRepo;
        this.auditRepo = auditRepo;
        this.seed = seed;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> listUsers() {
        return userRepo.findAll().stream().map(u -> Map.<String, Object>of(
            "id", u.getId(),
            "displayName", u.getDisplayName(),
            "primaryEmail", u.getPrimaryEmail(),
            "phone", Optional.ofNullable(u.getPhone()).orElse(""),
            "status", u.getStatus().name(),
            "mergedIntoUserId", Optional.ofNullable(u.getMergedIntoUserId()).orElse(-1L),
            "authMethods", authRepo.findByUserId(u.getId()).stream().map(a -> Map.of(
                "provider", a.getProvider().name(), "email", Optional.ofNullable(a.getEmail()).orElse(""))).toList(),
            "orders", orderRepo.findByUserId(u.getId()).size(),
            "watchlists", watchlistRepo.findByUserId(u.getId()).size()
        )).toList();
    }

    @GetMapping("/preview")
    public ConflictPreview preview(@RequestParam Long source, @RequestParam Long target) {
        return mergeService.previewMerge(source, target);
    }

    @PostMapping("/execute")
    public MergeReport execute(@RequestBody MergeRequest req) {
        return mergeService.merge(req);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> simulateLogin(@RequestBody Map<String, String> body) {
        AuthProvider provider = AuthProvider.valueOf(body.get("provider"));
        String externalId = body.get("externalId");
        return mergeService.resolveLogin(provider, externalId)
            .<ResponseEntity<Map<String, Object>>>map(u -> ResponseEntity.ok(Map.of(
                "resolvedUserId", u.getId(),
                "displayName", u.getDisplayName(),
                "primaryEmail", u.getPrimaryEmail(),
                "note", "auth method " + provider + ":" + externalId + " → user " + u.getId()
            )))
            .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "no such auth method")));
    }

    @GetMapping("/audit/{userId}")
    public List<AuditEvent> audit(@PathVariable Long userId) {
        return auditRepo.findByUserIdOrderByAtDesc(userId);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        seed.reset();
        return Map.of("status", "reset", "users", userRepo.count());
    }
}
