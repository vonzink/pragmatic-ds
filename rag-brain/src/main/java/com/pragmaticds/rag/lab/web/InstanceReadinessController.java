package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.ops.InstanceReadinessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Objects;

/**
 * Admin readiness for the instance control plane.
 *
 * <p>Plain {@code GET} answers {@code 200} with the full report. Naming a capability with
 * {@code ?require=} turns the same report into a gate: {@code 503} when that capability is not
 * ready, with the report as the body either way — the rollout runbook's health calls read the
 * booleans to see <em>which</em> prerequisite is missing, not just that one is. Behind the admin
 * key filter like every other admin route; behind the instances flag like every other instance
 * route, so a disabled deployment answers a bare 404.
 */
@RestController
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
@RequestMapping("/api/ai/admin/instances")
public final class InstanceReadinessController {

    private final InstanceReadinessService readiness;

    public InstanceReadinessController(InstanceReadinessService readiness) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    @GetMapping(value = "/readiness", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InstanceReadinessService.ReadinessReport> readiness(
            @RequestParam(value = "require", required = false) String require) {
        InstanceReadinessService.ReadinessReport report = readiness.report();
        if (require == null || require.isBlank()) {
            return ResponseEntity.ok(report);
        }
        boolean ready = switch (require.toLowerCase(Locale.ROOT)) {
            case "read" -> report.readReady();
            case "execution" -> report.executionReady();
            case "connector" -> report.connectorReady();
            case "promotion" -> report.promotionReady();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unknown capability");
        };
        return ready
                ? ResponseEntity.ok(report)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(report);
    }
}
