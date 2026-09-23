package com.pragmaticds.docengine.extraction.overlay;

import com.pragmaticds.docengine.extraction.domain.ExtractedField;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Decides, with no database and no side effects, which human review decisions survive a
 * re-extraction — and which must not.
 *
 * <p>A review decision is bound to an {@code extracted_field} row id, and every path that re-reads
 * a package replaces those rows: the EXTRACTING stage deletes and recreates them wholesale, and the
 * AI stage deletes a NONE-method occurrence before inserting the value it read. Left alone, the
 * decision points at a row that no longer exists and the machine's reading quietly returns in place
 * of the human's — the one outcome the machine-value/human-value split exists to prevent, and one
 * that raises no error anywhere. This planner is what re-attaches the decision, by {@link
 * FieldSnapshot#coordinate() coordinate} rather than by id.
 *
 * <p><b>The governing rule is that a wrong value is worse than a missing one.</b> Three deliberate
 * consequences follow, and none of them should be "simplified" away:
 *
 * <ol>
 *   <li><b>A coordinate that does not match exactly once on both sides carries nothing.</b> The
 *       unique index makes duplicates impossible for a well-formed generation, so a duplicate here
 *       means something is wrong with an assumption — and the safe response to a broken assumption
 *       is to drop the carry, never to pick a row. A vanished coordinate (a regroup moved the page
 *       into a new document, a schema stopped emitting the field) likewise carries nothing: the
 *       decision stays on record in the append-only table, it simply stops being effective.
 *   <li><b>A CONFIRM does not survive a changed reading.</b> "A human looked at this and agreed" is
 *       a statement about a SPECIFIC value. If the re-read produced a different one, re-stamping it
 *       CONFIRMED would put a human's name on a number no human ever saw. It reverts to
 *       NOT_REVIEWED, which is honest and puts the occurrence back in the review queue.
 *   <li><b>A CORRECT or a REJECT survives even a changed reading, but is flagged.</b> These are the
 *       fail-safe direction. A correction is a statement about the DOCUMENT ("net pay is
 *       9,999.99"), not about the machine's attempt, and the document did not change — so dropping
 *       it would restore a machine value a human already refused. A rejection means "do not use
 *       what is here"; the re-read has not been examined either, so continuing to withhold it is
 *       the conservative direction. But the human made that call against text that has since been
 *       re-read differently, so the occurrence is escalated to {@code MANUAL_REVIEW_REQUIRED}: the
 *       human's value keeps being served AND a reviewer is told to look again.
 * </ol>
 *
 * <p>"Changed reading" is deliberately broader than a changed value: a different {@code schema_id}
 * counts too. A schema version bump can redefine what a field name means, and a decision made under
 * the old definition is evidence about the old definition.
 */
public final class ReviewCarryForwardPlanner {

    private ReviewCarryForwardPlanner() {}

    /**
     * One decision to re-attach from a replaced row onto its replacement.
     *
     * @param previousFieldId the row the {@code review_decision} names today
     * @param newFieldId the replacement occupying the same coordinate
     * @param reviewStatus the status to stamp on the replacement
     * @param sameReading whether the machine still reads what it read when the human decided; false
     *     means the value survives but the occurrence needs a fresh look
     */
    public record Rebinding(
            UUID previousFieldId, UUID newFieldId, String reviewStatus, boolean sameReading) {}

    /**
     * @param before every current occurrence as it stood before the replacement
     * @param after every occurrence that replaced them
     * @return the rebindings to apply, in {@code after} order; empty when nothing was reviewed
     */
    public static List<Rebinding> plan(
            Collection<FieldSnapshot> before, Collection<FieldSnapshot> after) {
        Map<String, FieldSnapshot> priorByCoordinate = uniqueByCoordinate(before);
        if (priorByCoordinate.isEmpty()) {
            return List.of();
        }
        Set<String> ambiguousAfter = duplicateCoordinates(after);

        List<Rebinding> rebindings = new ArrayList<>();
        for (FieldSnapshot replacement : after) {
            String coordinate = replacement.coordinate();
            if (ambiguousAfter.contains(coordinate)) {
                continue;
            }
            FieldSnapshot prior = priorByCoordinate.get(coordinate);
            if (prior == null || isUnreviewed(prior.reviewStatus())) {
                continue;
            }
            boolean sameReading =
                    Objects.equals(prior.schemaId(), replacement.schemaId())
                            && Objects.equals(prior.displayedText(), replacement.displayedText());
            if (!sameReading && ExtractedField.REVIEW_CONFIRMED.equals(prior.reviewStatus())) {
                // A confirmation is agreement with one specific value; it does not transfer.
                continue;
            }
            rebindings.add(
                    new Rebinding(
                            prior.fieldId(),
                            replacement.fieldId(),
                            prior.reviewStatus(),
                            sameReading));
        }
        return List.copyOf(rebindings);
    }

    /** Reviewed snapshots keyed by coordinate, with any coordinate seen twice dropped entirely. */
    private static Map<String, FieldSnapshot> uniqueByCoordinate(Collection<FieldSnapshot> before) {
        Map<String, FieldSnapshot> byCoordinate = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (FieldSnapshot snapshot : before) {
            if (isUnreviewed(snapshot.reviewStatus())) {
                continue;
            }
            if (byCoordinate.put(snapshot.coordinate(), snapshot) != null) {
                duplicates.add(snapshot.coordinate());
            }
        }
        duplicates.forEach(byCoordinate::remove);
        return byCoordinate;
    }

    private static Set<String> duplicateCoordinates(Collection<FieldSnapshot> after) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (FieldSnapshot snapshot : after) {
            if (!seen.add(snapshot.coordinate())) {
                duplicates.add(snapshot.coordinate());
            }
        }
        return duplicates;
    }

    private static boolean isUnreviewed(String reviewStatus) {
        return reviewStatus == null || ExtractedField.REVIEW_NOT_REVIEWED.equals(reviewStatus);
    }
}
