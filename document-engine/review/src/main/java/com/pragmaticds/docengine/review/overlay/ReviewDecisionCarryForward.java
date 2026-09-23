package com.pragmaticds.docengine.review.overlay;

import com.pragmaticds.docengine.extraction.overlay.FieldDecisionCarryForwardPort;
import com.pragmaticds.docengine.review.ReviewJson;
import com.pragmaticds.docengine.review.domain.ReviewDecision;
import com.pragmaticds.docengine.review.repo.ReviewDecisionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code :review}-side implementation of the carry-forward port — the write mirror of {@link
 * ReviewFieldOverlay}, and wired here for the same module-graph reason.
 *
 * <p>Carrying a correction is an APPEND, exactly like making one: a new {@code review_decision} row
 * naming the REPLACEMENT field id, with the same value the human supplied. Nothing is rewritten,
 * nothing is deleted, and the original decision stays exactly where it is — so the trail reads
 * "a human corrected field X to V, then a re-extraction carried V onto field Y", which is what
 * actually happened.
 *
 * <p>{@code decided_by} stays the ORIGINAL human. That column exists so a value can always be
 * traced to the person accountable for it, and they are still that person: the engine is copying
 * their judgement forward, not forming one of its own. The {@code reason} column names the decision
 * this was carried from, so a carried row is never mistaken for a fresh human action.
 *
 * <p>This runs on a pipeline thread with no {@code AuthContext}, which is why it writes through the
 * repository rather than {@code FieldCorrectionService} — that service correctly refuses a
 * non-human principal, and a carry-forward has no principal at all.
 */
@Component
public class ReviewDecisionCarryForward implements FieldDecisionCarryForwardPort {

    private static final Logger log = LoggerFactory.getLogger(ReviewDecisionCarryForward.class);

    /** Prefix of the carried row's reason. Metadata only — never a value. */
    static final String CARRIED_FROM = "carried forward from review_decision ";

    private final ReviewDecisionRepository decisions;

    public ReviewDecisionCarryForward(ReviewDecisionRepository decisions) {
        this.decisions = decisions;
    }

    @Override
    public int carryForward(List<Carry> carries) {
        if (carries == null || carries.isEmpty()) {
            return 0;
        }
        Map<UUID, ReviewDecision> latestCorrections =
                latestCorrectionsFor(carries.stream().map(Carry::previousFieldId).toList());
        if (latestCorrections.isEmpty()) {
            return 0;
        }

        List<ReviewDecision> carried = new ArrayList<>();
        for (Carry carry : carries) {
            ReviewDecision source = latestCorrections.get(carry.previousFieldId());
            if (source == null) {
                // Reviewed, but never corrected (CONFIRM / REJECT). The replacement's
                // review_status already carries that; there is no human VALUE to re-attach.
                continue;
            }
            carried.add(
                    new ReviewDecision(
                            ReviewDecision.SUBJECT_EXTRACTED_FIELD,
                            carry.newFieldId(),
                            ReviewDecision.ACTION_CORRECT,
                            // What the human's value now stands in front of: the NEW machine
                            // reading, not the old one, so the trail shows what was superseded.
                            ReviewJson.object("value", carry.machineValue()),
                            // The human's value, moved verbatim as stored JSON — it is never
                            // unwrapped here, so a correction cannot leak through this path.
                            source.getNewValue(),
                            CARRIED_FROM + source.getId(),
                            source.getDecidedBy()));
        }
        if (carried.isEmpty()) {
            return 0;
        }
        decisions.saveAll(carried);
        // Count only: the values in these rows are human corrections and may be NPI.
        log.info("carried corrections forward count={}", carried.size());
        return carried.size();
    }

    /**
     * The newest {@code CORRECT} per replaced field, batched into one query. The repository orders
     * newest-first across the whole set, so the FIRST row seen for a subject is its current
     * correction — the same rule {@link ReviewFieldOverlay} resolves the effective value with, kept
     * identical on purpose: what gets carried forward must be exactly what was being served.
     */
    private Map<UUID, ReviewDecision> latestCorrectionsFor(List<UUID> previousFieldIds) {
        Map<UUID, ReviewDecision> latest = new HashMap<>();
        for (ReviewDecision decision :
                decisions.findBySubjectTypeAndSubjectIdInAndActionOrderByDecidedAtDesc(
                        ReviewDecision.SUBJECT_EXTRACTED_FIELD,
                        previousFieldIds,
                        ReviewDecision.ACTION_CORRECT)) {
            latest.putIfAbsent(decision.getSubjectId(), decision);
        }
        return latest;
    }
}
