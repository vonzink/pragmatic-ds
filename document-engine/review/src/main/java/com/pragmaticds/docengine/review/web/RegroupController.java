package com.pragmaticds.docengine.review.web;

import com.pragmaticds.docengine.classification.web.PackageDocumentsView;
import com.pragmaticds.docengine.review.regroup.RegroupRequest;
import com.pragmaticds.docengine.review.regroup.RegroupService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/packages/{id}/regroup} — REVIEWER+. Applies a reviewer's membership delta and
 * returns the updated {@code PackageDocumentsView} — the EXISTING documents shape (design §4.1), so
 * the UI re-renders with no new read model. The service returns {@code RegroupResult}; the wire body
 * is its unwrapped view. Delegates entirely to {@link RegroupService}; RBAC is enforced centrally in
 * {@code SecurityConfig}.
 */
@RestController
public class RegroupController {

    private final RegroupService service;

    public RegroupController(RegroupService service) {
        this.service = service;
    }

    @PostMapping("/v1/packages/{id}/regroup")
    public PackageDocumentsView regroup(
            @PathVariable UUID id, @RequestBody RegroupRequest request) {
        return service.regroup(id, request).documents();
    }
}
