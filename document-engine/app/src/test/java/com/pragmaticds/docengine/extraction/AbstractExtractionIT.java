package com.pragmaticds.docengine.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.classification.AbstractClassificationIT;
import com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ParserPort.StageOutcome;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared plumbing for the extraction ITs, on top of the classification fixture bridge: truth
 * words become {@code text_span} rows verbatim, and {@link #insertFixtureLayout} synthesizes the
 * TABLE → TABLE_ROW → TABLE_CELL tree the live worker emits for grid fixtures (verified M4 =
 * 25/25 on paystub_complete) from the truth words that carry a {@code "cell"} marker.
 */
public abstract class AbstractExtractionIT extends AbstractClassificationIT {

    @Autowired protected ParserPort parserPort;
    @Autowired protected ExtractionSchemaLoader schemaLoader;

    @BeforeEach
    void extractionSetup() {
        // Schema rows may have been inserted/deleted by a previous test in the shared container.
        schemaLoader.invalidateAll();
    }

    protected StageOutcome runStage(UUID packageId, ProcessingStatus stage) {
        return parserPort.run(
                new ParserPort.StageRequest(UUID.randomUUID(), packageId, stage, 1, "it-idem"));
    }

    /** CLASSIFYING → SPLITTING → EXTRACTING, asserting each stage succeeds. */
    protected void runPipelineToExtraction(UUID packageId) {
        for (ProcessingStatus stage :
                List.of(
                        ProcessingStatus.CLASSIFYING,
                        ProcessingStatus.SPLITTING,
                        ProcessingStatus.EXTRACTING)) {
            StageOutcome outcome = runStage(packageId, stage);
            assertThat(outcome.success()).as("stage %s succeeds", stage).isTrue();
        }
    }

    /**
     * Synthesizes the worker's grid output for every truth page whose words carry {@code "cell"}:
     * one TABLE (box = union of all cells, attributes {@code {"rows":R,"cols":C,"ruled":…}}), one
     * TABLE_ROW per row ({@code {"row":r}}, box = union), one TABLE_CELL per cell word ({@code
     * {"row","col"}}, box = word box) with a {@code layout_element_span} link to the text_span
     * inserted by {@code insertFixturePages} — matched by (page, x, y), which is unique in the
     * fixtures by construction. Unruled: the worker's {@code UNRULED_CONFIDENCE} (0.75) on every
     * element of the tree, {@code "ruled": false}.
     */
    protected void insertFixtureLayout(UUID packageId, String fixtureName) {
        insertFixtureLayout(packageId, fixtureName, false);
    }

    /**
     * The ruling-aware variant. {@code ruled} mirrors what the live worker persists for a table
     * whose whitespace grid the vector rulings confirmed (worker {@code tables.py}:
     * {@code RULED_CONFIDENCE = 0.95} on the whole TABLE → ROW → CELL tree, versus
     * {@code UNRULED_CONFIDENCE = 0.75}; the flag itself rides in the TABLE's attributes). The
     * caller declares it from the fixture's own construction — {@code generate.py} draws rulings
     * for {@code ruled_table.pdf} and for nothing else — because the truth JSON records words, not
     * ink. A rotated fixture is ALWAYS seeded unruled: {@code rulings.py} skips any page with a
     * nonzero {@code /Rotate} entirely, so a rotated page can never reach 0.95 by construction.
     */
    protected void insertFixtureLayout(UUID packageId, String fixtureName, boolean ruled) {
        insertFixtureLayout(packageId, truth(fixtureName), ruled);
    }

    /**
     * The same seeding from an already-loaded truth document — what the evaluation harness uses,
     * since its truth may come from beside a corpus case rather than from {@code fixtures/truth}.
     */
    protected void insertFixtureLayout(UUID packageId, JsonNode truth, boolean ruled) {
        List<UUID> pageIds =
                jdbc.queryForList(
                        "SELECT id FROM page WHERE package_id = ? ORDER BY package_page_index",
                        UUID.class,
                        packageId);
        JsonNode truthPages = truth.get("pages");
        for (int pageIndex = 0; pageIndex < truthPages.size(); pageIndex++) {
            JsonNode words = truthPages.get(pageIndex).get("words");
            if (words == null) {
                continue;
            }
            // row → cell words, in truth order (the worker's reading order).
            Map<Integer, List<JsonNode>> byRow = new LinkedHashMap<>();
            for (JsonNode word : words) {
                if (word.has("cell")) {
                    byRow.computeIfAbsent(word.get("cell").get("row").asInt(), r -> new ArrayList<>())
                            .add(word);
                }
            }
            if (byRow.isEmpty()) {
                continue;
            }
            UUID pageId = pageIds.get(pageIndex);
            // The worker's own contract confidences (tables.py RULED_CONFIDENCE/UNRULED_CONFIDENCE),
            // applied to the whole tree the way _build_table does.
            String confidence = ruled ? "0.95" : "0.75";
            int cols =
                    byRow.values().stream()
                                    .flatMap(List::stream)
                                    .mapToInt(w -> w.get("cell").get("col").asInt())
                                    .max()
                                    .orElseThrow()
                            + 1;
            int ordinal = 0;
            UUID tableId =
                    insertLayoutElement(
                            pageId,
                            null,
                            "TABLE",
                            ordinal++,
                            union(byRow.values().stream().flatMap(List::stream).toList()),
                            confidence,
                            "{\"rows\":" + byRow.size() + ",\"cols\":" + cols
                                    + ",\"ruled\":" + ruled + "}",
                            null);
            for (Map.Entry<Integer, List<JsonNode>> row : byRow.entrySet()) {
                UUID rowId =
                        insertLayoutElement(
                                pageId,
                                tableId,
                                "TABLE_ROW",
                                ordinal++,
                                union(row.getValue()),
                                confidence,
                                "{\"row\":" + row.getKey() + "}",
                                null);
                for (JsonNode word : row.getValue()) {
                    UUID cellId =
                            insertLayoutElement(
                                    pageId,
                                    rowId,
                                    "TABLE_CELL",
                                    ordinal++,
                                    new BigDecimal[] {
                                        word.get("x").decimalValue(),
                                        word.get("y").decimalValue(),
                                        word.get("width").decimalValue(),
                                        word.get("height").decimalValue()
                                    },
                                    confidence,
                                    "{\"row\":" + word.get("cell").get("row").asInt()
                                            + ",\"col\":" + word.get("cell").get("col").asInt()
                                            + "}",
                                    // The live persister denormalizes linked span text onto the
                                    // element (LayoutElementService); one word, one cell here.
                                    word.get("text").asText());
                    Long spanId =
                            jdbc.queryForObject(
                                    "SELECT id FROM text_span WHERE page_id = ? AND x = ? AND y = ?",
                                    Long.class,
                                    pageId,
                                    word.get("x").decimalValue().setScale(2, RoundingMode.HALF_UP),
                                    word.get("y").decimalValue().setScale(2, RoundingMode.HALF_UP));
                    jdbc.update(
                            "INSERT INTO layout_element_span (layout_element_id, text_span_id,"
                                    + " org_id, ordinal) VALUES (?, ?, ?, 0)",
                            cellId,
                            spanId,
                            ORG_DEV);
                }
            }
        }
    }

    private UUID insertLayoutElement(
            UUID pageId,
            UUID parentId,
            String type,
            int ordinal,
            BigDecimal[] box,
            String confidence,
            String attributes,
            String text) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO layout_element (id, org_id, page_id, parent_element_id, element_type,
                    ordinal, x, y, width, height, confidence, detector, detector_version,
                    attributes, text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::numeric, 'clustering', 'it', ?::jsonb, ?)
                """,
                id,
                ORG_DEV,
                pageId,
                parentId,
                type,
                ordinal,
                box[0],
                box[1],
                box[2],
                box[3],
                confidence,
                attributes,
                text);
        return id;
    }

    /** Union box over truth words: [x, y, width, height]. */
    private static BigDecimal[] union(List<JsonNode> words) {
        BigDecimal minX = null;
        BigDecimal minY = null;
        BigDecimal maxRight = null;
        BigDecimal maxBottom = null;
        for (JsonNode word : words) {
            BigDecimal x = word.get("x").decimalValue();
            BigDecimal y = word.get("y").decimalValue();
            BigDecimal right = x.add(word.get("width").decimalValue());
            BigDecimal bottom = y.add(word.get("height").decimalValue());
            minX = minX == null ? x : minX.min(x);
            minY = minY == null ? y : minY.min(y);
            maxRight = maxRight == null ? right : maxRight.max(right);
            maxBottom = maxBottom == null ? bottom : maxBottom.max(bottom);
        }
        return new BigDecimal[] {minX, minY, maxRight.subtract(minX), maxBottom.subtract(minY)};
    }

    // ── extracted-field helpers ─────────────────────────────────────────────

    /** The package's single logical document id (fixtures split to one PAYSTUB). */
    protected UUID onlyDocumentOf(UUID packageId) {
        return jdbc.queryForObject(
                "SELECT id FROM logical_document WHERE package_id = ? ORDER BY ordinal",
                UUID.class,
                packageId);
    }

    /**
     * Current rows keyed by field name — for UNGROUPED schemas only, and it now says so out
     * loud. Before Spec 5a one field name meant one row, so a map keyed by name was the whole
     * truth. A grouped field has one row PER OCCURRENCE, and this map would keep whichever
     * sorted last and silently discard the rest: this spec's own failure class, reproduced
     * inside its test helper. It throws instead, and grouped tests use {@link
     * #currentOccurrences}.
     */
    protected Map<String, Map<String, Object>> currentFieldsByName(UUID logicalDocumentId) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT * FROM extracted_field WHERE logical_document_id = ? AND is_current"
                                + " ORDER BY field_name",
                        logicalDocumentId)) {
            String name = (String) row.get("field_name");
            if (byName.put(name, row) != null) {
                throw new IllegalStateException(
                        "field " + name + " has more than one current row — it is GROUPED;"
                                + " use currentOccurrences(documentId) instead");
            }
        }
        return byName;
    }

    /**
     * Current rows keyed by {@code fieldName#groupKey}, with an empty key for an ungrouped
     * row — the coordinate a grouped field actually has. Ordered by name then group key, so
     * the map's iteration order is the occurrence order the API promises.
     */
    protected Map<String, Map<String, Object>> currentOccurrences(UUID logicalDocumentId) {
        Map<String, Map<String, Object>> byOccurrence = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT * FROM extracted_field WHERE logical_document_id = ? AND is_current"
                                + " ORDER BY field_name, group_key NULLS FIRST",
                        logicalDocumentId)) {
            String key = (String) row.get("group_key");
            byOccurrence.put(row.get("field_name") + "#" + (key == null ? "" : key), row);
        }
        return byOccurrence;
    }

    protected List<Map<String, Object>> evidenceOf(UUID extractedFieldId, String role) {
        return jdbc.queryForList(
                "SELECT * FROM field_evidence WHERE extracted_field_id = ? AND role = ?"
                        + " ORDER BY ordinal",
                extractedFieldId,
                role);
    }

    /** packagePageIndex of the page an evidence row points at. */
    protected int packagePageIndexOf(UUID pageId) {
        Integer index =
                jdbc.queryForObject(
                        "SELECT package_page_index FROM page WHERE id = ?", Integer.class, pageId);
        return index == null ? -1 : index;
    }

    protected static BigDecimal scaled(Object value) {
        return ((BigDecimal) value).setScale(2, RoundingMode.HALF_UP);
    }

    /** True when the evidence row's box equals the truth word's box at scale 2. */
    protected static boolean boxMatches(Map<String, Object> evidence, JsonNode word) {
        return scaled(evidence.get("x"))
                        .compareTo(word.get("x").decimalValue().setScale(2, RoundingMode.HALF_UP))
                        == 0
                && scaled(evidence.get("y"))
                        .compareTo(word.get("y").decimalValue().setScale(2, RoundingMode.HALF_UP))
                        == 0
                && scaled(evidence.get("width"))
                        .compareTo(word.get("width").decimalValue().setScale(2, RoundingMode.HALF_UP))
                        == 0
                && scaled(evidence.get("height"))
                        .compareTo(word.get("height").decimalValue().setScale(2, RoundingMode.HALF_UP))
                        == 0;
    }

    // ── re-extraction job seeding ───────────────────────────────────────────

    /**
     * Seeds the processing_job (HUMAN_REVIEW_REQUIRED) and the stage rows a completed Spec-1 run
     * leaves — shared by {@code ReExtractIT} and {@code RegroupIT}. {@code runPipelineToExtraction}
     * drives the stages directly through {@code parserPort} and creates NO processing_job, so a
     * subsequent {@code reExtract} would 404; this replays the persisted pipeline. VALIDATING..
     * SPLITTING are SUCCEEDED/SKIPPED (isDone → the runner skips them, so the worker adapter never
     * HTTP-calls a dead port), EXTRACTING is SUCCEEDED (deleted then re-run); NORMALIZING/
     * AI_EXTRACTION/VALIDATING_DATA/AI_REVIEW are disabled or unimplemented skipped stages.
     */
    protected void seedCompletedJob(UUID packageId) {
        UUID jobId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO processing_job (id, org_id, package_id, idempotency_key, status)"
                        + " VALUES (?, ?, ?, ?, 'HUMAN_REVIEW_REQUIRED')",
                jobId,
                ORG_DEV,
                packageId,
                "re-extract-" + jobId);
        seedStage(jobId, "VALIDATING", "SUCCEEDED", null);
        seedStage(jobId, "NORMALIZING", "SKIPPED", "PHASE_2_NOT_IMPLEMENTED");
        seedStage(jobId, "RENDERING", "SUCCEEDED", null);
        seedStage(jobId, "TEXT_EXTRACTION", "SUCCEEDED", null);
        seedStage(jobId, "OCR_PROCESSING", "SUCCEEDED", null);
        seedStage(jobId, "PARSING", "SUCCEEDED", null);
        seedStage(jobId, "CLASSIFYING", "SUCCEEDED", null);
        seedStage(jobId, "SPLITTING", "SUCCEEDED", null);
        seedStage(jobId, "EXTRACTING", "SUCCEEDED", null);
        seedStage(jobId, "AI_EXTRACTION", "SKIPPED", "AI_DISABLED");
        seedStage(jobId, "VALIDATING_DATA", "SKIPPED", "SPEC_4_NOT_IMPLEMENTED");
        seedStage(jobId, "AI_REVIEW", "SKIPPED", "SPEC_5_NOT_IMPLEMENTED");
    }

    private void seedStage(UUID jobId, String stage, String status, String skipReason) {
        jdbc.update(
                "INSERT INTO processing_stage (id, org_id, job_id, stage, status, attempt,"
                        + " skip_reason) VALUES (?, ?, ?, ?, ?, 1, ?)",
                UUID.randomUUID(),
                ORG_DEV,
                jobId,
                stage,
                status,
                skipReason);
    }
}
