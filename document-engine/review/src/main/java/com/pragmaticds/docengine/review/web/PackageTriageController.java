package com.pragmaticds.docengine.review.web;

import com.pragmaticds.docengine.review.triage.PackageTriageView;
import com.pragmaticds.docengine.review.triage.UnknownTriageService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/packages/{id}/triage} — the work the engine could not classify: documents typed
 * UNKNOWN end to end, runs of untyped pages absorbed by the typed document in front of them, and
 * the near misses where a pack fell just under its own threshold.
 *
 * <p>REVIEWER+. The central matrix gates every {@code GET /v1/**} at READONLY; the narrower
 * REVIEWER rule is enforced by {@link UnknownTriageService} — see its class javadoc for why a
 * worklist is not a read-only consumer's surface, and the design doc §6 for the declarative matrix
 * line that should follow.
 *
 * <p>Assembly lives in the service, not here, for the same reason
 * {@code PackageDocumentsController} delegates to its assembler: this projection joins across
 * classification and review, and a controller is the wrong place for that. Tenancy: the service
 * org-guards the package with {@code findByIdAndOrgIdAndDeletedAtIsNull}, so a cross-tenant or
 * soft-deleted id answers 404 exactly like a nonexistent one.
 */
@RestController
public class PackageTriageController {

    private final UnknownTriageService triage;

    public PackageTriageController(UnknownTriageService triage) {
        this.triage = triage;
    }

    /**
     * Named {@code triageQueue}, not {@code get}.
     *
     * <p>springdoc derives {@code operationId} from the METHOD name, and every controller method
     * called {@code get} lands in one anonymous {@code get_N} sequence numbered by scan order.
     * Adding another does not collide loudly — it INSERTS, renumbering every later member. Measured
     * on this branch, taking {@code get_2} shifted six already-shipped endpoints by one and would
     * have renamed every generated client method with them. A distinct name keeps this endpoint out
     * of that sequence entirely.
     */
    @GetMapping("/v1/packages/{id}/triage")
    public PackageTriageView triageQueue(@PathVariable UUID id) {
        return triage.triage(id);
    }
}
