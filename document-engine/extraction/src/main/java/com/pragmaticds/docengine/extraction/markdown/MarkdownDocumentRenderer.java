package com.pragmaticds.docengine.extraction.markdown;

import com.pragmaticds.docengine.extraction.web.DocumentFieldsView.ConfidenceComponentsView;
import com.pragmaticds.docengine.extraction.web.DocumentFieldsView.EvidenceView;
import com.pragmaticds.docengine.extraction.web.DocumentFieldsView.FieldView;
import com.pragmaticds.docengine.extraction.web.DocumentFieldsView.NormalizedView;
import com.pragmaticds.docengine.extraction.web.EffectiveStatus;
import com.pragmaticds.docengine.extraction.web.TextProvenanceView;
import com.pragmaticds.docengine.platform.pii.MaskableValue;
import com.pragmaticds.docengine.platform.pii.MaskingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * DOCENGINE-MD-1: one document's extracted occurrences as deterministic Markdown.
 *
 * <h2>The determinism contract</h2>
 *
 * <p><b>Same input, same bytes — always.</b> This class is a pure static function of {@link
 * MarkdownDocumentSource}: no clock, no hostname, no locale-sensitive formatting, no iteration over
 * an unordered collection. Every ordering rule is stated below and every one of them is total, so
 * there is no tie left for a hash seed or a query planner to break. A rendering that differed
 * run-to-run could not be cached, diffed, or cited.
 *
 * <p><b>Ordering rules, in force:</b>
 *
 * <ol>
 *   <li>Sections in fixed order: front matter, title, ungrouped table, group clusters,
 *       region-not-read callouts, occurrence-detail appendix, footer.
 *   <li>The ungrouped table lists fields in READ-MODEL order, which is field name code-point
 *       ascending.
 *   <li>Clusters are partitioned by {@code (groupKind, exact ordered key sequence)} and ordered by
 *       their first member field name, code-point ascending. The key-sequence half of that
 *       partition is what keeps two unrelated tables apart; the kind half keeps a COLUMN group and
 *       a ROW group that happen to share a key alphabet apart.
 *   <li>Within a cluster: member fields code-point ascending, occurrence keys code-point
 *       ascending. Code-point order is why zero-padded ordinal keys matter ({@code 01 < 02 < 10})
 *       and why letters and ordinals never mix inside one cluster.
 *   <li>Region-not-read callouts: field name code-point ascending.
 *   <li>The appendix is the read model's own order VERBATIM — field name ascending, then group key
 *       ascending with a null key FIRST — so a reader diffing this rendering against the JSON walks
 *       both in lockstep.
 *   <li>LF line endings, no trailing spaces, exactly one trailing newline, no timestamps.
 * </ol>
 *
 * <h2>Orientation</h2>
 *
 * <p>A COLUMN group renders its occurrences as table COLUMNS (Part I's A/B/C reads across, exactly
 * as the form prints it); a ROW group renders them as table ROWS. That is the whole rule, and it is
 * the kind's own meaning: {@code groupKind} says how the FORM lays the occurrences out, so the
 * rendering just agrees with it. Design D8 reached the same answer through a data-type proxy
 * ("STRING-bearing clusters as rows, all-numeric as columns") because the kind was not on the wire
 * when D8 was written; D10 put it there, and a proxy for a fact you now have is a bug waiting to
 * happen — an all-numeric ROW group of 99 rows would render as 99 columns under the proxy.
 *
 * <h2>Masking</h2>
 *
 * <p>Values arrive from the read model already paired with their sensitivity ({@link
 * MaskableValue}), and every value in this file reaches the page through {@link #text} — the one
 * choke point, which applies the platform mask to every sensitive arm including the normalized
 * ones. Nothing here can unmask, and there is no path that formats a raw value directly.
 *
 * <h2>What this rendering is NOT</h2>
 *
 * <p>It is a projection of the READ MODEL, which overlays human corrections at read time — not of
 * the immutable canonical envelope. It therefore carries no envelope hash and makes no integrity
 * claim; the footer says so in words rather than printing a hash that would imply verifiability it
 * does not have. When the envelope next takes a coordinated version bump, this renderer moves to
 * it and gains the hash footer.
 */
public final class MarkdownDocumentRenderer {

    /**
     * The rendering contract version. Any change to these bytes bumps it and re-records the golden.
     *
     * <p>1.1.0 adds the {@code Text} column — where each value's characters came from (native text
     * layer vs OCR, with the engine named). Additive: no existing column moved, no existing cell
     * changed except the cluster cell's parenthetical, which gained a third term after the page.
     *
     * <p>1.2.0 makes review verdicts visible (design D11, read from {@code
     * FieldView.effectiveStatus} — never re-derived here). A REJECTED occurrence's text is
     * WITHHELD: its value cells render {@code — rejected (review)} and its appendix Value and
     * Normalized dash, while the machine facts (Method, Text, Confidence, Page) stay printed. A
     * machine-missing occurrence a human CORRECTed renders the human's value instead of the
     * missing cell, and the appendix Status column can now read {@code REJECTED} or {@code
     * CORRECTED}. A rendering with no review verdicts changes by exactly one byte-run: this
     * contract line — the legend sentence for the verdict words appears only when one can.
     */
    public static final String CONTRACT = "DOCENGINE-MD-1/1.2.0";

    /** A field the schema does not group (the read model's {@code groupKind} floor). */
    private static final String KIND_NONE = "NONE";

    private static final String KIND_COLUMN = "COLUMN";

    /**
     * The definitional test for a missing occurrence, matching the review UI's: method {@code NONE}
     * is what the engine writes for "looked for, not found" (confidence exactly 0, no evidence,
     * MANUAL_REVIEW_REQUIRED). A missing occurrence is a RESULT, never an absence — so it renders
     * as a visible cell, never as a blank or a zero.
     */
    private static final String METHOD_NONE = "NONE";

    private static final String MISSING_CELL = "— missing (review)";

    /**
     * What a REJECTED occurrence's value cell says instead of its text. The refused characters
     * appear NOWHERE in the rendering: this surface is a copy-paste/LLM artifact with no
     * machine-readable member a consumer could be bound by, so the only binding form of "do not
     * use this value" is absence — strike-through would still carry the characters.
     */
    private static final String REJECTED_CELL = "— rejected (review)";

    private static final String EMPTY_CELL = "—";

    /** The key column's rendering of a null key, so a reader can see there IS no key. */
    private static final String NULL_KEY = "∅";

    private MarkdownDocumentRenderer() {}

    /** The whole rendering. UTF-8 text, LF endings, exactly one trailing newline. */
    public static String render(MarkdownDocumentSource source) {
        StringBuilder out = new StringBuilder();
        List<FieldView> fields = source.fields().fields();

        frontMatter(out, source);

        out.append("# ")
                .append(nullSafe(source.fields().documentTypeCode()))
                .append(" — document ")
                .append(source.documentOrdinal())
                .append("\n");

        ungroupedSection(out, fields);
        clusterSections(out, fields);
        regionNotReadSection(out, fields);
        appendix(out, fields);
        footer(out, source);

        return out.toString();
    }

    // ── front matter ────────────────────────────────────────────────────────

    private static void frontMatter(StringBuilder out, MarkdownDocumentSource source) {
        out.append("---\n");
        out.append("generator: pds-document-engine\n");
        out.append("mdContract: ").append(CONTRACT).append("\n");
        // The provenance sentence, stated rather than implied. This rendering is identified by the
        // document id and the schema version that produced its rows — NOT by a content hash, because
        // the read model it projects is mutable (a correction changes it) and printing a hash here
        // would promise a verifiability that does not exist.
        out.append("source: \"read model, identified by documentId + schemaVersion;")
                .append(" includes human corrections; not envelope-hash-backed\"\n");
        out.append("packageId: ").append(source.packageId()).append("\n");
        out.append("documentId: ").append(source.fields().documentId()).append("\n");
        out.append("documentOrdinal: ").append(source.documentOrdinal()).append("\n");
        out.append("documentTypeCode: ")
                .append(nullSafe(source.fields().documentTypeCode()))
                .append("\n");
        out.append("schemaVersion: ").append(nullSafe(source.fields().schemaVersion())).append("\n");
        out.append("occurrences: ").append(source.fields().fields().size()).append("\n");
        out.append("pages: [");
        for (int i = 0; i < source.pages().size(); i++) {
            out.append(i == 0 ? "" : ", ").append(source.pages().get(i).pageNumber());
        }
        out.append("]\n");
        out.append("classification:\n");
        for (MarkdownDocumentSource.PageClassification page : source.pages()) {
            out.append("  - { page: ")
                    .append(page.pageNumber())
                    .append(", type: ")
                    .append(nullSafe(page.documentTypeCode()))
                    .append(", confidence: ")
                    .append(page.confidence() == null ? "null" : scale4(page.confidence()))
                    .append(", rulePackVersion: ")
                    .append(page.rulePackVersion() == null ? "null" : page.rulePackVersion())
                    .append(" }\n");
        }
        out.append("---\n\n");
    }

    // ── the ungrouped table ─────────────────────────────────────────────────

    private static void ungroupedSection(StringBuilder out, List<FieldView> fields) {
        List<FieldView> ungrouped =
                fields.stream().filter(field -> KIND_NONE.equals(kindOf(field))).toList();
        if (ungrouped.isEmpty()) {
            return;
        }
        out.append("\n## Document fields\n\n");
        out.append("| Field | Value | Text | Confidence | Page |\n");
        out.append("|---|---|---|---|---|\n");
        for (FieldView field : ungrouped) {
            out.append("| ")
                    .append(fieldLabel(field))
                    .append(" | ")
                    .append(valueCell(field))
                    .append(" | ")
                    .append(provenance(field))
                    .append(" | ")
                    .append(scale4(field.confidence()))
                    .append(" | ")
                    .append(pageOf(field) == null ? EMPTY_CELL : pageOf(field))
                    .append(" |\n");
        }
    }

    // ── group clusters ──────────────────────────────────────────────────────

    private static void clusterSections(StringBuilder out, List<FieldView> fields) {
        for (Cluster cluster : clustersOf(fields)) {
            out.append("\n## ")
                    .append(cluster.label())
                    .append(" ")
                    .append(cluster.keySummary())
                    .append(" — ")
                    .append(cluster.kind())
                    .append(" group\n\n");
            if (KIND_COLUMN.equals(cluster.kind())) {
                occurrencesAsColumns(out, cluster);
            } else {
                occurrencesAsRows(out, cluster);
            }
        }
    }

    /** COLUMN group: fields are rows, the printed keys are the columns — the form's own layout. */
    private static void occurrencesAsColumns(StringBuilder out, Cluster cluster) {
        out.append("| Field |");
        for (String key : cluster.keys()) {
            out.append(" ").append(key).append(" |");
        }
        out.append("\n|---|");
        out.append("---|".repeat(cluster.keys().size()));
        out.append("\n");
        for (String fieldName : cluster.fieldNames()) {
            out.append("| ").append(labelFor(cluster, fieldName)).append(" |");
            for (String key : cluster.keys()) {
                out.append(" ").append(cell(cluster.occurrence(fieldName, key))).append(" |");
            }
            out.append("\n");
        }
    }

    /** ROW group: one row per occurrence key, one column per member field. */
    private static void occurrencesAsRows(StringBuilder out, Cluster cluster) {
        out.append("| Key |");
        for (String fieldName : cluster.fieldNames()) {
            out.append(" ").append(labelFor(cluster, fieldName)).append(" |");
        }
        out.append("\n|---|");
        out.append("---|".repeat(cluster.fieldNames().size()));
        out.append("\n");
        for (String key : cluster.keys()) {
            out.append("| ").append(key).append(" |");
            for (String fieldName : cluster.fieldNames()) {
                out.append(" ").append(cell(cluster.occurrence(fieldName, key))).append(" |");
            }
            out.append("\n");
        }
    }

    // ── region-not-read callouts (design D7) ────────────────────────────────

    /**
     * A grouped field whose occurrence carries NO key means the engine could not locate the table
     * region at all — a statement about the TABLE, not a value of the field. It is excluded from
     * every cluster table AND from the ungrouped table, and says so in words instead. Rendering it
     * as a document-level field would be a lie of category: a reviewer would read "this field is
     * empty" where the truth is "this table was never read".
     *
     * <p>The callouts share one section rather than being threaded into a guessed-at cluster: a
     * name-prefix heuristic can attach the callout to the WRONG table, and a wrong attachment is
     * worse than an unattached one. The appendix carries the exact coordinate either way.
     */
    private static void regionNotReadSection(StringBuilder out, List<FieldView> fields) {
        List<FieldView> orphans = fields.stream().filter(MarkdownDocumentRenderer::isOrphan).toList();
        if (orphans.isEmpty()) {
            return;
        }
        out.append("\n## Region not read\n\n");
        for (FieldView field : orphans) {
            out.append("> **`")
                    .append(field.fieldName())
                    .append("`** — the engine located no readable ")
                    .append(kindOf(field))
                    .append(" group region for this field and recorded one explicitly-missing")
                    .append(" occurrence with no group key. Manual review required. This is a")
                    .append(" GROUPED field, not a document-level field.\n");
        }
    }

    // ── the flat appendix ───────────────────────────────────────────────────

    private static void appendix(StringBuilder out, List<FieldView> fields) {
        out.append("\n## Occurrence detail\n\n");
        out.append(
                "Read-model order: field name ascending, then group key ascending with a null key"
                        + " first. Key `∅` is a null key. Components are span · anchor ·"
                        + " normalizer; their product is the confidence. `Text` is where the"
                        + " value's characters came from — `NATIVE` from the PDF's own text layer,"
                        + " `OCR <engine>` recognised from pixels, `MIXED <engine>` when one value"
                        + " came from both, `UNKNOWN` when no text span backs it. It is NOT a"
                        + " confidence component. Pages are 1-based.");
        // The verdict legend spends bytes only when a verdict word can appear, which is what keeps
        // a no-decision rendering byte-identical below the contract line.
        if (fields.stream().anyMatch(field -> isRejected(field) || isCorrected(field))) {
            out.append(
                    " Status `REJECTED` means a reviewer refused the served value — its text is"
                            + " withheld from every cell of this rendering; `CORRECTED` means the"
                            + " value shown is a human's, not the machine's.");
        }
        out.append("\n\n");
        out.append(
                "| Field | Key | Kind | Status | Value | Normalized | Method | Text | Confidence |"
                        + " Validation | Page |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (FieldView field : fields) {
            // A rejected occurrence's Value/Normalized are withheld exactly like a missing one's:
            // the machine columns to the right keep stating what the machine did.
            boolean withheld = isMissing(field) || isRejected(field);
            out.append("| ")
                    .append(field.fieldName())
                    .append(" | ")
                    .append(field.groupKey() == null ? NULL_KEY : escape(field.groupKey()))
                    .append(" | ")
                    .append(kindOf(field))
                    .append(" | ")
                    .append(status(field))
                    .append(" | ")
                    .append(withheld ? EMPTY_CELL : orDash(text(field.displayedText())))
                    .append(" | ")
                    .append(withheld ? EMPTY_CELL : orDash(normalizedText(field.normalized())))
                    .append(" | ")
                    .append(nullSafe(field.extractionMethod()))
                    .append(" | ")
                    .append(provenance(field))
                    .append(" | ")
                    .append(confidenceWithComponents(field))
                    .append(" | ")
                    .append(nullSafe(field.validationStatus()))
                    .append(" | ")
                    .append(pageOf(field) == null ? EMPTY_CELL : pageOf(field))
                    .append(" |\n");
        }
    }

    private static void footer(StringBuilder out, MarkdownDocumentSource source) {
        out.append("\n---\n\n");
        out.append("Rendered by ")
                .append(CONTRACT)
                .append(" from the document read model — document `")
                .append(source.fields().documentId())
                .append("`, schema version ")
                .append(nullSafe(source.fields().schemaVersion()))
                .append(".\n");
        out.append(
                "That read model overlays human corrections, so this is the CURRENT REVIEWED"
                        + " state, not a frozen machine parse.\n");
        out.append(
                "There is no envelope hash here and no integrity claim: to cite an immutable parse,"
                        + " pin an engine-result revision instead.\n");
        out.append(
                "Sensitive values are masked and cannot be unmasked through this surface. Pages are"
                        + " 1-based here; evidence JSON is 0-based.\n");
    }

    // ── clustering ──────────────────────────────────────────────────────────

    /**
     * Grouped occurrences partitioned into the tables a reader sees.
     *
     * <p>Partition key is {@code (kind, exact ordered key sequence)}. The key sequence is what makes
     * Part I's A/B/C money grid one table and Part II's A–D entity table another; the kind keeps a
     * COLUMN group and a ROW group that coincidentally share an alphabet from merging. Partitioning
     * affects PRESENTATION only — every occurrence keeps its {@code (fieldName, groupKey)} identity
     * regardless, and the appendix lists all of them.
     */
    private static List<Cluster> clustersOf(List<FieldView> fields) {
        // fieldName → its keyed occurrences, in read-model order (key ascending).
        Map<String, List<FieldView>> keyedByName = new TreeMap<>();
        for (FieldView field : fields) {
            if (KIND_NONE.equals(kindOf(field)) || field.groupKey() == null) {
                continue;
            }
            keyedByName.computeIfAbsent(field.fieldName(), name -> new ArrayList<>()).add(field);
        }

        Map<String, Cluster> byPartition = new LinkedHashMap<>();
        for (Map.Entry<String, List<FieldView>> entry : keyedByName.entrySet()) {
            List<FieldView> occurrences = entry.getValue();
            List<String> keys = occurrences.stream().map(FieldView::groupKey).sorted().toList();
            String kind = kindOf(occurrences.get(0));
            String partition = kind + " " + String.join("", keys);
            byPartition
                    .computeIfAbsent(partition, ignored -> new Cluster(kind, keys))
                    .add(entry.getKey(), occurrences);
        }

        List<Cluster> clusters = new ArrayList<>(byPartition.values());
        // Ordered by first member field name: the members are a TreeMap's keys, so "first" is
        // code-point least, and the whole ordering is total.
        clusters.sort(Comparator.comparing(cluster -> cluster.fieldNames().get(0)));
        return clusters;
    }

    /** One rendered table: a kind, an ordered key set, and the fields that repeat over it. */
    private static final class Cluster {
        private final String kind;
        private final List<String> keys;
        private final Map<String, Map<String, FieldView>> byField = new TreeMap<>();
        private final Map<String, Boolean> sensitiveByField = new TreeMap<>();

        Cluster(String kind, List<String> keys) {
            this.kind = kind;
            this.keys = keys;
        }

        void add(String fieldName, List<FieldView> occurrences) {
            Map<String, FieldView> byKey = new TreeMap<>();
            boolean sensitive = false;
            for (FieldView occurrence : occurrences) {
                byKey.put(occurrence.groupKey(), occurrence);
                sensitive |= occurrence.sensitive();
            }
            byField.put(fieldName, byKey);
            sensitiveByField.put(fieldName, sensitive);
        }

        String kind() {
            return kind;
        }

        List<String> keys() {
            return keys;
        }

        List<String> fieldNames() {
            return List.copyOf(byField.keySet());
        }

        boolean sensitive(String fieldName) {
            return Boolean.TRUE.equals(sensitiveByField.get(fieldName));
        }

        /** The occurrence at a cell, or null — which renders as an explicit missing cell. */
        FieldView occurrence(String fieldName, String key) {
            return byField.getOrDefault(fieldName, Map.of()).get(key);
        }

        /**
         * The humanised leading camel-case words every member shares — "Partnership", "Estate Or
         * Trust" — or {@code "Group"} when the members share nothing. Derived, never authored: a
         * per-form title table would be per-form knowledge this renderer deliberately does not
         * carry, and would be wrong for every type that does not have an entry.
         */
        String label() {
            List<List<String>> wordLists = fieldNames().stream().map(Cluster::camelWords).toList();
            List<String> shared = new ArrayList<>(wordLists.get(0));
            for (List<String> words : wordLists) {
                while (!shared.isEmpty()
                        && (words.size() < shared.size()
                                || !words.subList(0, shared.size()).equals(shared))) {
                    shared.remove(shared.size() - 1);
                }
            }
            if (shared.isEmpty()) {
                return "Group";
            }
            StringBuilder label = new StringBuilder();
            for (String word : shared) {
                label.append(label.isEmpty() ? "" : " ")
                        .append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1));
            }
            return label.toString();
        }

        /** {@code A–D (4)}, or {@code 01 (1)} for a single-key group. */
        String keySummary() {
            if (keys.size() == 1) {
                return keys.get(0) + " (1)";
            }
            return keys.get(0) + "–" + keys.get(keys.size() - 1) + " (" + keys.size() + ")";
        }

        private static List<String> camelWords(String fieldName) {
            return List.of(fieldName.split("(?<=[a-z0-9])(?=[A-Z])"));
        }
    }

    // ── cells ───────────────────────────────────────────────────────────────

    /**
     * One occurrence in a cluster table. A cell with no occurrence at all is still MISSING, not
     * blank: a COLUMN group emits its full declared key set, so an absent cell can only mean the
     * read model did not carry that coordinate, and a blank would launder that into "nothing
     * there".
     */
    private static String cell(FieldView field) {
        if (field == null || isMissing(field)) {
            return MISSING_CELL;
        }
        if (isRejected(field)) {
            // No parenthetical either: a confidence beside a refused value would read as a reason
            // to trust it, and the machine facts stay available on the appendix row.
            return REJECTED_CELL;
        }
        String rendered = plainValue(field);
        Integer page = pageOf(field);
        // Provenance is printed on EVERY cell, including the ordinary NATIVE ones. Marking only the
        // exceptional case would read as an annotation a reviewer could miss the absence of — and
        // "no marker" would then have to mean both "native" and "this renderer predates the
        // column", which is the ambiguity the whole feature exists to remove.
        return rendered
                + " ("
                + scale4(field.confidence())
                + (page == null ? "" : ", p" + page)
                + ", "
                + provenance(field)
                + ")";
    }

    /** The ungrouped table's value cell: the verdict/missing state, or the value itself. */
    private static String valueCell(FieldView field) {
        if (isRejected(field)) {
            return REJECTED_CELL;
        }
        return isMissing(field) ? MISSING_CELL : plainValue(field);
    }

    /**
     * The value as a human reads it: the displayed text verbatim (sign glyphs included), plus the
     * normalized arm for anything that is not a STRING — so {@code ( 18,470 )} carries its
     * machine-unambiguous {@code -18470} beside it and a reader cannot mistake a loss for income.
     */
    private static String plainValue(FieldView field) {
        String displayed = orDash(text(field.displayedText()));
        if ("STRING".equals(field.dataType())) {
            return displayed;
        }
        String normalized = normalizedText(field.normalized());
        if (normalized == null || normalized.equals(text(field.displayedText()))) {
            return displayed;
        }
        return displayed + " = " + normalized;
    }

    private static String fieldLabel(FieldView field) {
        return "`" + field.fieldName() + "`" + (field.sensitive() ? " (sensitive, masked)" : "");
    }

    private static String labelFor(Cluster cluster, String fieldName) {
        return "`" + fieldName + "`" + (cluster.sensitive(fieldName) ? " (sensitive, masked)" : "");
    }

    private static String status(FieldView field) {
        if (isRejected(field)) {
            return "REJECTED";
        }
        if (isCorrected(field)) {
            return "CORRECTED";
        }
        if (!isMissing(field)) {
            return "FOUND";
        }
        return isOrphan(field) ? "MISSING — region not read" : "MISSING";
    }

    /** {@code 0.9000 (1 · 0.9 · 1)} — the product a human scans, the inputs an auditor needs. */
    private static String confidenceWithComponents(FieldView field) {
        ConfidenceComponentsView components = field.confidenceComponents();
        if (components == null) {
            return scale4(field.confidence()) + " (" + EMPTY_CELL + ")";
        }
        return scale4(field.confidence())
                + " ("
                + plain(components.spanConfidence())
                + " · "
                + plain(components.anchorStrength())
                + " · "
                + plain(components.normalizerCertainty())
                + ")";
    }

    // ── formatting primitives ───────────────────────────────────────────────

    /**
     * THE MASKING CHOKE POINT. Every value that reaches the page goes through here, and a sensitive
     * one is masked with the platform rule before it is ever formatted — including the normalized
     * arms, which are PII too for a sensitive MONEY or DATE field.
     */
    private static String text(MaskableValue value) {
        if (value == null || value.raw() == null) {
            return null;
        }
        String plain = plain(value.raw());
        return escape(value.sensitive() ? MaskingService.mask(plain) : plain);
    }

    /** The single populated normalized arm, masked; null when the occurrence has none. */
    private static String normalizedText(NormalizedView normalized) {
        if (normalized == null) {
            return null;
        }
        String rendered = text(normalized.text());
        if (rendered == null) {
            rendered = text(normalized.number());
        }
        if (rendered == null) {
            rendered = text(normalized.date());
        }
        return rendered;
    }

    /**
     * 1-based package page number of the occurrence's FIRST VALUE evidence box, or null when there
     * is none. The read model orders evidence VALUE before LABEL, so the first VALUE row is the
     * first row of that role.
     */
    private static Integer pageOf(FieldView field) {
        for (EvidenceView box : field.evidence()) {
            if ("VALUE".equals(box.role())) {
                return box.packagePageIndex() + 1;
            }
        }
        return null;
    }

    /**
     * "Missing" is a statement about the MACHINE's parse, so it holds only on a MACHINE row
     * (§8.5): a method-NONE occurrence a human then CORRECTed carries the human's value and must
     * render it, and a REJECTED one renders its own verdict cell, never the missing one.
     */
    private static boolean isMissing(FieldView field) {
        return METHOD_NONE.equals(field.extractionMethod())
                && !isCorrected(field)
                && !isRejected(field);
    }

    private static boolean isRejected(FieldView field) {
        return EffectiveStatus.REJECTED.equals(field.effectiveStatus());
    }

    private static boolean isCorrected(FieldView field) {
        return EffectiveStatus.CORRECTED.equals(field.effectiveStatus());
    }

    /** A grouped field carrying no key: the region-not-read case (design D7). */
    private static boolean isOrphan(FieldView field) {
        return !KIND_NONE.equals(kindOf(field)) && field.groupKey() == null;
    }

    private static String kindOf(FieldView field) {
        return field.groupKind() == null ? KIND_NONE : field.groupKind();
    }

    /**
     * Where the occurrence's VALUE characters came from, in {@link TextProvenanceView#label()}'s
     * wording — the SAME string the review UI's badge shows, so a reviewer reading the rendering
     * and a reviewer reading the screen are reading one fact spelled one way. A null arm renders
     * {@code UNKNOWN} rather than blank: "we cannot say" is a statement, and a blank cell in a
     * provenance column would read as "native", which is the assumption this column exists to stop
     * anyone making.
     */
    private static String provenance(FieldView field) {
        return field.textProvenance() == null
                ? TextProvenanceView.UNKNOWN
                : field.textProvenance().label();
    }

    /** Scale-4 fixed, so every confidence in the document is the same width. */
    private static String scale4(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(4, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * A value as its shortest exact decimal ({@code 950.00 → 950}), never scientific notation.
     * Matches the canonical envelope's number rule, so the two surfaces spell one number one way.
     */
    private static String plain(Object value) {
        if (value instanceof BigDecimal decimal) {
            BigDecimal stripped = decimal.stripTrailingZeros();
            return stripped.scale() < 0
                    ? stripped.setScale(0).toPlainString()
                    : stripped.toPlainString();
        }
        return String.valueOf(value);
    }

    /**
     * Table-cell safety: a literal {@code |} would end the cell, and a newline would end the row.
     * Both are escaped rather than dropped — a value's content is never silently altered.
     */
    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r\n", " ")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static String orDash(String value) {
        return value == null ? EMPTY_CELL : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "null" : value;
    }
}
