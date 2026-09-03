package com.poc.batchingest.controller;

import com.poc.batchingest.service.JobLauncherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/jobs")
@Slf4j
public class JobLaunchController {

    private final JobLauncherService launcher;

    public JobLaunchController(JobLauncherService launcher) {
        this.launcher = launcher;
    }

    @PostMapping("/{jobName}")
    public ResponseEntity<JobResponse> launch(@PathVariable String jobName,
                                              @RequestBody(required = false) Map<String, String> params) throws Exception {
        JobExecution exec = launcher.launch(jobName, params);
        return ResponseEntity.accepted().body(JobResponse.from(exec));
    }

    @PostMapping("/{executionId}/restart")
    public ResponseEntity<Map<String, Object>> restart(@PathVariable long executionId) throws Exception {
        Long newId = launcher.restart(executionId);
        return ResponseEntity.accepted().body(Map.of("originalExecutionId", executionId, "newExecutionId", newId));
    }

    @PostMapping("/{executionId}/stop")
    public ResponseEntity<Map<String, Object>> stop(@PathVariable long executionId) throws Exception {
        boolean stopped = launcher.stop(executionId);
        return ResponseEntity.ok(Map.of("executionId", executionId, "stopRequested", stopped));
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<JobResponse> status(@PathVariable long executionId) {
        JobExecution exec = launcher.status(executionId);
        if (exec == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(JobResponse.from(exec));
    }

    public record JobResponse(
            long executionId,
            String jobName,
            String status,
            String exitCode,
            String exitDescription,
            List<StepSummary> steps) {

        static JobResponse from(JobExecution exec) {
            List<StepSummary> steps = exec.getStepExecutions().stream()
                    .map(StepSummary::from)
                    .toList();
            return new JobResponse(
                    exec.getId(),
                    exec.getJobInstance().getJobName(),
                    exec.getStatus().name(),
                    exec.getExitStatus().getExitCode(),
                    exec.getExitStatus().getExitDescription(),
                    steps);
        }
    }

    public record StepSummary(
            String stepName,
            String status,
            long read,
            long written,
            long skipped,
            long commitCount) {

        static StepSummary from(StepExecution step) {
            return new StepSummary(
                    step.getStepName(),
                    step.getStatus().name(),
                    step.getReadCount(),
                    step.getWriteCount(),
                    step.getReadSkipCount() + step.getProcessSkipCount() + step.getWriteSkipCount(),
                    step.getCommitCount());
        }
    }
}
