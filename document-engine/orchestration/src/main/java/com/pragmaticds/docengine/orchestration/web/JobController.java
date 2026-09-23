package com.pragmaticds.docengine.orchestration.web;

import com.pragmaticds.docengine.orchestration.JobService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Job inspection and resume. Tenancy is invisible here by design: the service resolves the org
 * from TenantContext and a cross-tenant id is a 404 miss — the controller cannot leak what it
 * never learns.
 */
@RestController
@RequestMapping("/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(jobService.getJob(id));
    }

    /** 202: the replay is dispatched; the body is a snapshot, progress arrives via GET. */
    @PostMapping("/{id}/resume")
    public ResponseEntity<JobResponse> resume(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(jobService.resume(id)));
    }

    /** 202: the cancel is requested; the runner honours it at the job's next checkpoint. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<JobResponse> cancel(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(jobService.cancel(id)));
    }
}
