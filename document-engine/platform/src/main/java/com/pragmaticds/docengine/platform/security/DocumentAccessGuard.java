package com.pragmaticds.docengine.platform.security;

import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The subject-access seam. For the Phase-7a MVP its rule is simply org-membership: a subject
 * (package, page, document, field) is accessible iff it belongs to the caller's org — which is
 * exactly what a {@code findByIdAndOrgId} load already proves. This component is where finer,
 * per-document grants will later hang without touching every controller.
 *
 * <p>Deliberate policy: a subject the caller may NOT access is reported as ABSENT (404), never
 * FORBIDDEN (403). A 403 confirms the id exists in another tenant; a 404 leaks nothing. This
 * mirrors the existing controllers' {@code DomainException.notFound(NOT_FOUND)} behaviour, so the
 * guard and the hand-rolled loads stay indistinguishable to a caller.
 */
@Component
public class DocumentAccessGuard {

    /**
     * Returns the subject if the org-scoped load found it, else raises the not-found the callers
     * already use. Callers pass the result of a {@code findByIdAndOrgId} — the org match IS the
     * access check.
     *
     * @param orgScopedSubject the result of loading the subject by id AND the caller's org
     */
    public <T> T requireAccessible(Optional<T> orgScopedSubject) {
        return orgScopedSubject.orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
    }
}
