package com.poc.kyc.controller;

import com.poc.kyc.controller.dto.*;
import com.poc.kyc.model.DlqEntry;
import com.poc.kyc.model.KycCase;
import com.poc.kyc.model.KycState;
import com.poc.kyc.repository.KycCaseRepository;
import com.poc.kyc.service.KycWorkflowService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kyc")
@CrossOrigin
public class KycController {

    private final KycWorkflowService workflow;
    private final KycCaseRepository repository;

    public KycController(KycWorkflowService workflow, KycCaseRepository repository) {
        this.workflow = workflow;
        this.repository = repository;
    }

    @PostMapping("/register")
    public ResponseEntity<CaseView> register(@Valid @RequestBody RegisterRequest req) {
        KycCase c = workflow.register(req.fullName(), req.email(), req.declaredAddress());
        return ResponseEntity.ok(CaseView.from(c));
    }

    @PostMapping("/{caseId}/upload")
    public ResponseEntity<CaseView> upload(@PathVariable String caseId,
                                           @Valid @RequestBody UploadRequest req) {
        KycCase c = workflow.uploadIdAndStart(caseId, req.fileName(), req.sizeBytes());
        return ResponseEntity.ok(CaseView.from(c));
    }

    @PostMapping("/{caseId}/review")
    public ResponseEntity<CaseView> review(@PathVariable String caseId,
                                           @Valid @RequestBody ReviewRequest req) {
        KycCase c = workflow.reviewerDecision(caseId, req.decision(), req.reviewerNote(), req.reviewer());
        return ResponseEntity.ok(CaseView.from(c));
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<CaseView> get(@PathVariable String caseId) {
        return repository.findById(caseId)
                .map(c -> ResponseEntity.ok(CaseView.from(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<CaseView>> list() {
        List<CaseView> list = repository.findAll().stream()
                .sorted(Comparator.comparing(KycCase::getCreatedAt).reversed())
                .map(CaseView::from)
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/queue/manual-review")
    public ResponseEntity<List<CaseView>> manualReviewQueue() {
        return ResponseEntity.ok(
                repository.findByState(KycState.PENDING_MANUAL_REVIEW).stream()
                        .sorted(Comparator.comparing(KycCase::getUpdatedAt))
                        .map(CaseView::from)
                        .toList());
    }

    @GetMapping("/dlq")
    public ResponseEntity<List<DlqEntry>> dlq() {
        return ResponseEntity.ok(
                repository.findAllDlqEntries().stream()
                        .sorted(Comparator.comparing(DlqEntry::getMovedAt).reversed())
                        .toList());
    }

    @PostMapping("/dlq/{dlqId}/replay")
    public ResponseEntity<CaseView> replay(@PathVariable String dlqId,
                                           @RequestParam(defaultValue = "admin") String actor) {
        KycCase c = workflow.replayFromDlq(dlqId, actor);
        return ResponseEntity.ok(CaseView.from(c));
    }

    @GetMapping("/stats")
    public ResponseEntity<StatsView> stats() {
        Map<String, Long> byState = new LinkedHashMap<>();
        Arrays.stream(KycState.values()).forEach(s -> byState.put(s.name(), repository.countByState(s)));

        long total = repository.findAll().size();
        long approved = repository.countByState(KycState.APPROVED);
        long rejected = repository.countByState(KycState.REJECTED);
        long pending = repository.countByState(KycState.PENDING_MANUAL_REVIEW);
        long dlq = repository.countByState(KycState.DEAD_LETTER);
        long inProgress = total - approved - rejected - pending - dlq;

        return ResponseEntity.ok(new StatsView(total, approved, rejected, pending, inProgress, dlq, byState));
    }
}
