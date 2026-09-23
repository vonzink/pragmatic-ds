package com.pragmaticds.docengine.extraction;

import com.pragmaticds.docengine.classification.InstanceBoundaryDetector;
import com.pragmaticds.docengine.extraction.extract.FieldExtractionEngine;
import com.pragmaticds.docengine.extraction.extract.FieldOutcome;
import com.pragmaticds.docengine.extraction.extract.PageContent;
import com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separates two documents of the same type by reading the identity they PRINT — the answer behind
 * {@link InstanceBoundaryDetector}.
 *
 * <p>Three consecutive monthly bank statements arrive as one run of {@code BANK_STATEMENT} pages.
 * No type changes, no anchor distinguishes one statement's header from the next, and the splitter
 * therefore produces one document that reports a single beginning balance for three months. The
 * statements are not actually indistinguishable, though: each prints its own statement period, the
 * schema already knows how to read one, and two disjoint periods inside one run PROVE two
 * documents.
 *
 * <p><b>It re-reads with the schema's own ladder, page by page.</b> The probe narrows the active
 * schema to just its declared {@code instanceKey} fields and runs the ordinary extraction engine
 * against ONE page at a time. That is the whole trick, and it is why this is not a second
 * extraction implementation: an anchor change in the pack changes how the period is found here
 * too, automatically, with nothing to keep in sync. (The full EXTRACTING stage cannot answer this
 * question — it scans a document's pages in order and the FIRST page that yields a value wins it,
 * so a merged three-statement document reports exactly one period and hides the other two.)
 *
 * <p><b>A page with no key CONTINUES the current instance.</b> A statement's later pages usually do
 * not reprint the period, so silence must mean "still the same statement" — the same rule the
 * splitter applies to an UNKNOWN page. Only a page whose key is FOUND and DIFFERENT from the one
 * in force starts a new instance, so the mechanism cuts on positive evidence and never on absence.
 *
 * <p><b>Read-only, and never fatal.</b> It writes nothing, and the port's contract lets it answer
 * "nothing" for any reason at all — no schema, no declared key, an unreadable page. The
 * deterministic type-and-anchor split then stands exactly as it did before Phase C. This mechanism
 * may only ever IMPROVE a split.
 */
@Service
public class InstanceKeyBoundaryDetector implements InstanceBoundaryDetector {

    private static final Logger log = LoggerFactory.getLogger(InstanceKeyBoundaryDetector.class);

    private final ExtractionSchemaLoader schemas;
    private final FieldExtractionEngine engine;

    /**
     * For its page projection ONLY — {@link FieldExtractionService#projectPage}. The probe borrows
     * the projection so it reads exactly the spans, tables and detections the extractor would; it
     * runs none of the service's persistence.
     */
    private final FieldExtractionService extraction;

    public InstanceKeyBoundaryDetector(
            ExtractionSchemaLoader schemas,
            FieldExtractionEngine engine,
            FieldExtractionService extraction) {
        this.schemas = schemas;
        this.engine = engine;
        this.extraction = extraction;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Set<UUID> pagesStartingNewInstance(String documentTypeCode, List<UUID> orderedPageIds) {
        if (documentTypeCode == null || orderedPageIds == null || orderedPageIds.size() < 2) {
            return Set.of();
        }
        SchemaDefinition schema = schemas.activeSchemaFor(documentTypeCode).orElse(null);
        if (schema == null || schema.instanceKey().isEmpty()) {
            // The common case by a distance: no type but BANK_STATEMENT declares a key today, and
            // this is the branch that keeps the mechanism free for every other package.
            return Set.of();
        }
        SchemaDefinition probe = schema.instanceKeyProjection();
        if (probe.fields().isEmpty()) {
            return Set.of();
        }

        Set<UUID> starts = new LinkedHashSet<>();
        String keyInForce = null;
        for (UUID pageId : orderedPageIds) {
            String key = instanceKeyOf(probe, pageId);
            if (key == null) {
                continue; // no key printed here — a continuation page of the instance in force
            }
            if (keyInForce == null) {
                keyInForce = key;
                continue; // the run's first readable key establishes the opening instance
            }
            if (!key.equals(keyInForce)) {
                starts.add(pageId);
                keyInForce = key;
            }
        }
        if (!starts.isEmpty()) {
            // Counts and the type only — never the key itself, which is borrower content.
            log.info(
                    "instance boundaries detected type={} pages={} boundaries={}",
                    documentTypeCode,
                    orderedPageIds.size(),
                    starts.size());
        }
        return Set.copyOf(starts);
    }

    /**
     * The instance key ONE page prints, or null.
     *
     * <p>Every declared key field must be FOUND on the page for it to count. A statement that
     * prints its start date and not its end date has told us less than the schema asked for, and
     * cutting on a half-read identity would split a document on a coincidence — the same
     * missing-over-wrong rule the extractors themselves follow.
     *
     * <p>The key is the NORMALIZED value where the normalizer produced one, so two spellings of
     * one date ({@code 01/15/2025}, {@code January 15, 2025}) compare equal and do not
     * manufacture a boundary between two pages of the same statement.
     */
    private String instanceKeyOf(SchemaDefinition probe, UUID pageId) {
        List<PageContent> page = extraction.projectPage(pageId, TenantContext.require());
        if (page.isEmpty()) {
            return null;
        }
        List<FieldOutcome> outcomes;
        try {
            outcomes = engine.extract(probe, page);
        } catch (RuntimeException e) {
            // A page the extractor cannot read is not evidence of a boundary. Class only: an
            // extractor message can quote document content.
            log.warn("instance key probe failed exception={}", e.getClass().getSimpleName());
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String fieldName : probe.instanceKey()) {
            FieldOutcome outcome =
                    outcomes.stream()
                            .filter(o -> o.field().name().equals(fieldName) && o.found())
                            .findFirst()
                            .orElse(null);
            if (outcome == null) {
                return null;
            }
            parts.add(normalizedKey(outcome));
        }
        return String.join("", parts);
    }

    private static String normalizedKey(FieldOutcome outcome) {
        if (outcome.normalized() != null) {
            if (outcome.normalized().date() != null) {
                return outcome.normalized().date().toString();
            }
            if (outcome.normalized().number() != null) {
                return outcome.normalized().number().stripTrailingZeros().toPlainString();
            }
            if (outcome.normalized().text() != null) {
                return outcome.normalized().text();
            }
        }
        // Unnormalized (a STRING field): the raw value, trimmed and case-folded, so trivial
        // print differences between two pages of one statement do not read as two statements.
        return outcome.rawValue() == null ? "" : outcome.rawValue().strip().toUpperCase();
    }
}
