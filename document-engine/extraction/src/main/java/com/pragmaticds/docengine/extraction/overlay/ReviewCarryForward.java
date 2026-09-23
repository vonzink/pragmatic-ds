package com.pragmaticds.docengine.extraction.overlay;

import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import com.pragmaticds.docengine.extraction.repo.ExtractedFieldRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The ONE place a replacement of {@code extracted_field} rows carries the human review that was
 * attached to the rows it replaced.
 *
 * <p>Two paths replace rows and BOTH must call this, or the bug returns on whichever one does not:
 * {@code FieldExtractionService} deletes and recreates a package's fields on every re-extraction,
 * and the AI extraction stage deletes a NONE-method occurrence before inserting the value it read.
 * Keeping the rule in a component rather than duplicating it is what stops the two paths from
 * drifting into disagreeing about whether a correction survives.
 *
 * <p>It does exactly two things per rebinding {@link ReviewCarryForwardPlanner} sanctions: stamps
 * the replacement's {@code review_status} (the Layer-2 materialisation of the latest decision, which
 * {@code EffectiveStatus} reads), and asks {@code :review} to append the carried correction against
 * the new row id (the Layer-4 truth the read overlay resolves). Both are required — the status alone
 * would claim a correction the overlay could not serve, and the decision alone would serve a
 * corrected value under a NOT_REVIEWED badge.
 *
 * <p>Runs inside the caller's transaction: a re-extraction that rolls back must not leave carried
 * decisions behind pointing at rows that were never committed.
 */
@Component
public class ReviewCarryForward {

    private static final Logger log = LoggerFactory.getLogger(ReviewCarryForward.class);

    private final ExtractedFieldRepository fields;
    private final ObjectProvider<FieldDecisionCarryForwardPort> carryForward;

    public ReviewCarryForward(
            ExtractedFieldRepository fields,
            ObjectProvider<FieldDecisionCarryForwardPort> carryForward) {
        this.fields = fields;
        this.carryForward = carryForward;
    }

    /**
     * Carries review from the replaced rows onto their replacements.
     *
     * @param before every current occurrence as it stood before the replacement, snapshotted while
     *     the rows still existed
     * @param after the rows that replaced them, already persisted (their ids are needed)
     * @return the number of occurrences whose review was carried
     */
    public int apply(List<FieldSnapshot> before, List<ExtractedField> after) {
        if (before.isEmpty() || after.isEmpty()) {
            return 0;
        }
        Map<UUID, ExtractedField> replacementsById = new LinkedHashMap<>();
        List<FieldSnapshot> afterSnapshots = new ArrayList<>(after.size());
        for (ExtractedField field : after) {
            replacementsById.put(field.getId(), field);
            afterSnapshots.add(FieldSnapshot.of(field));
        }

        List<ReviewCarryForwardPlanner.Rebinding> rebindings =
                ReviewCarryForwardPlanner.plan(before, afterSnapshots);
        if (rebindings.isEmpty()) {
            return 0;
        }

        List<FieldDecisionCarryForwardPort.Carry> carries = new ArrayList<>(rebindings.size());
        int flagged = 0;
        for (ReviewCarryForwardPlanner.Rebinding rebinding : rebindings) {
            ExtractedField replacement = replacementsById.get(rebinding.newFieldId());
            replacement.applyReviewStatus(rebinding.reviewStatus());
            if (!rebinding.sameReading()) {
                // The human decided against text that has since been re-read differently. Their
                // value still stands (dropping it would restore a reading they refused), but a
                // reviewer has to see the disagreement rather than inherit it silently.
                replacement.markCarriedReviewNeedsRecheck();
                flagged++;
            }
            fields.save(replacement);
            carries.add(
                    new FieldDecisionCarryForwardPort.Carry(
                            rebinding.previousFieldId(),
                            rebinding.newFieldId(),
                            replacement.getDisplayedText()));
        }

        FieldDecisionCarryForwardPort port = carryForward.getIfAvailable();
        int corrections = port == null ? 0 : port.carryForward(carries);
        // Counts and ids only — a carried value is a human's correction and may be NPI.
        log.info(
                "review carried forward occurrences={} corrections={} flaggedForRecheck={}",
                rebindings.size(),
                corrections,
                flagged);
        return rebindings.size();
    }
}
