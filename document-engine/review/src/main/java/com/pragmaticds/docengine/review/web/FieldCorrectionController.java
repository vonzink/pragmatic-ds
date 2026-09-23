package com.pragmaticds.docengine.review.web;

import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.review.FieldCorrectionService;
import com.pragmaticds.docengine.review.FieldCorrectionService.Action;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PATCH /v1/fields/{id}} — a human review decision on one extracted field. The response
 * carries the effective (overlaid) value and updated review status alongside the created decision;
 * the machine's Layer-2 value stays readable via {@code /v1/documents/{id}/fields} and the history
 * strip.
 *
 * <p>Authorization is REVIEWER+ (SecurityConfig). Cross-tenant field ids answer 404 via the
 * service's {@code findByIdAndOrgId} load. A SYSTEM principal (no userId) is refused 403 — a human
 * is always accountable for a correction.
 */
@RestController
public class FieldCorrectionController {

    private final FieldCorrectionService corrections;

    public FieldCorrectionController(FieldCorrectionService corrections) {
        this.corrections = corrections;
    }

    @PatchMapping("/v1/fields/{id}")
    public FieldCorrectionService.Result correct(
            @PathVariable UUID id, @RequestBody CorrectionRequest body) {
        return corrections.apply(
                id, parseAction(body.action()), body.value(), body.reason(), body.pageIndex());
    }

    private static Action parseAction(String action) {
        if (action == null) {
            throw DomainException.badRequest(
                    ErrorCode.INVALID_REQUEST, Map.of("reason", "ACTION_REQUIRED"));
        }
        try {
            return Action.valueOf(action);
        } catch (IllegalArgumentException e) {
            // The unknown action string is not echoed — only the stable code.
            throw DomainException.badRequest(
                    ErrorCode.INVALID_REQUEST, Map.of("reason", "UNKNOWN_ACTION"));
        }
    }

    /**
     * @param action CONFIRM · CORRECT · REJECT
     * @param value required for CORRECT — the human-authoritative value; ignored otherwise
     * @param reason optional free text
     * @param pageIndex optional, CORRECT only — the document-relative page the value is printed on
     */
    public record CorrectionRequest(String action, String value, String reason, Integer pageIndex) {}
}
