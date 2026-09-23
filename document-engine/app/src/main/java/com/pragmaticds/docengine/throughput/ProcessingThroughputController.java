package com.pragmaticds.docengine.throughput;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * This org's measured processing rates. Tenancy is invisible here by design: the service resolves
 * the org from {@code TenantContext}. Covered by the {@code GET /v1/**} READONLY+ rule in
 * {@code SecurityConfig.matrix}; numbers only, never content.
 */
@RestController
public class ProcessingThroughputController {

    private final ProcessingThroughputService service;

    public ProcessingThroughputController(ProcessingThroughputService service) {
        this.service = service;
    }

    @GetMapping("/v1/processing/throughput")
    public Throughput currentThroughput() {
        return service.current();
    }
}
