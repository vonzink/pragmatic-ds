package com.pragmaticds.docengine.review.web;

import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.review.regroup.PageVerdictService;
import com.pragmaticds.docengine.review.regroup.PageVerdictService.Verdict;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/pages/{id}/verdict} — REVIEWER+. A reviewer clears a page's blank/duplicate signal
 * so it becomes assignable; it does not re-run detection and does not assign the page. Delegates
 * entirely to {@link PageVerdictService}; RBAC is enforced centrally in {@code SecurityConfig}.
 *
 * <p>Cross-tenant page ids answer 404 via the service's {@code findByIdAndOrgId} load. A SYSTEM
 * principal (no userId) is refused 403 — a human is always accountable for an override.
 */
@RestController
public class PageVerdictController {

    private final PageVerdictService service;

    public PageVerdictController(PageVerdictService service) {
        this.service = service;
    }

    @PostMapping("/v1/pages/{id}/verdict")
    public void override(@PathVariable UUID id, @RequestBody VerdictRequest body) {
        service.override(id, parseVerdict(body.verdict()), body.reason());
    }

    private static Verdict parseVerdict(String verdict) {
        if (verdict == null) {
            throw DomainException.badRequest(
                    ErrorCode.INVALID_REQUEST, Map.of("reason", "UNKNOWN_VERDICT"));
        }
        try {
            return Verdict.valueOf(verdict);
        } catch (IllegalArgumentException e) {
            // The unknown verdict string is not echoed — only the stable code.
            throw DomainException.badRequest(
                    ErrorCode.INVALID_REQUEST, Map.of("reason", "UNKNOWN_VERDICT"));
        }
    }

    /**
     * @param verdict NOT_BLANK · NOT_DUPLICATE
     * @param reason optional free text
     */
    public record VerdictRequest(String verdict, String reason) {}
}
