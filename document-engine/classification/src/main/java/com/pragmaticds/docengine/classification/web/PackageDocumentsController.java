package com.pragmaticds.docengine.classification.web;

import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The split projection: a package's logical documents with their pages and per-page
 * classifications, plus the pages that stayed unassigned and WHY (blank or duplicate).
 *
 * <p>The assembly itself lives in {@link PackageDocumentsAssembler} so there is ONE read-model path
 * shared with {@code RegroupService} (which reuses it to snapshot the grouping and to return the
 * updated view). This controller only resolves the tenant and delegates.
 *
 * <p>Tenancy: the assembler org-guards the package with {@code findByIdAndOrgIdAndDeletedAtIsNull} —
 * a cross-tenant id answers 404 exactly like a nonexistent one, so another org's package ids are
 * never confirmed to exist.
 */
@RestController
public class PackageDocumentsController {

    private final PackageDocumentsAssembler assembler;

    public PackageDocumentsController(PackageDocumentsAssembler assembler) {
        this.assembler = assembler;
    }

    @GetMapping("/v1/packages/{id}/documents")
    public PackageDocumentsView get(@PathVariable UUID id) {
        return assembler.build(id, TenantContext.require());
    }
}
