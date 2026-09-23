package com.pragmaticds.docengine.review.overlay;

import com.pragmaticds.docengine.extraction.overlay.FieldOverlayPort;
import com.pragmaticds.docengine.review.ReviewJson;
import com.pragmaticds.docengine.review.domain.ReviewDecision;
import com.pragmaticds.docengine.review.repo.ReviewDecisionRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@code :review}-side implementation of the extraction overlay port. Wiring it here — not in
 * {@code :extraction} — is what keeps the module graph honest: {@code :extraction} owns the read
 * models and the port interface, {@code :review} owns human decisions and supplies the effective
 * value at read time. The extraction controllers inject the port through an {@code ObjectProvider},
 * so if this module were absent they would simply render the machine value.
 */
@Component
public class ReviewFieldOverlay implements FieldOverlayPort {

    private final ReviewDecisionRepository decisions;

    public ReviewFieldOverlay(ReviewDecisionRepository decisions) {
        this.decisions = decisions;
    }

    @Override
    public Map<UUID, String> effectiveValues(Collection<UUID> fieldIds) {
        if (fieldIds == null || fieldIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> effective = new LinkedHashMap<>();
        // Ordered newest-first; the FIRST decision seen per field is the current correction.
        for (ReviewDecision decision :
                decisions.findBySubjectTypeAndSubjectIdInAndActionOrderByDecidedAtDesc(
                        ReviewDecision.SUBJECT_EXTRACTED_FIELD,
                        fieldIds,
                        ReviewDecision.ACTION_CORRECT)) {
            effective.computeIfAbsent(
                    decision.getSubjectId(), id -> ReviewJson.read(decision.getNewValue(), "value"));
        }
        return effective;
    }
}
