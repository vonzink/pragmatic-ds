package com.pragmaticds.docengine.extraction.extract;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.match.TextFold;
import com.pragmaticds.docengine.extraction.schema.CheckboxOption;
import com.pragmaticds.docengine.extraction.schema.DerivationSpec;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.GroupKind;
import com.pragmaticds.docengine.extraction.schema.GroupRegionSpec;
import com.pragmaticds.docengine.extraction.schema.GroupSpec;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.ValueScope;
import com.pragmaticds.docengine.extraction.schema.Window;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import com.pragmaticds.docengine.platform.regex.BoundedCharSequence;
import com.pragmaticds.docengine.platform.regex.RegexBudgetExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pure extraction core: ANCHOR_LABEL, LABEL_BELOW, LABEL_ABOVE, ROW_CELL, TABLE_CLUSTER,
 * REGEX and the two detector rungs, over projected pages.
 * No JPA, no Spring context required — {@code @Service} only so the persistence side can inject
 * it.
 *
 * <p>Contract (from {@link FieldExtractionEngine}): one {@link FieldOutcome} per schema field
 * OCCURRENCE, in schema order — one occurrence for an ungrouped field, one per declared key for a
 * COLUMN group, and one per ROW of a located region for a ROW group. Pages are scanned in
 * document order and the first page whose ladder succeeds wins
 * the occurrence; within a page, extractors run in spec order and the first success wins. A
 * captured value that fails normalization fails its RUNG — the ladder moves on. Nothing found
 * anywhere is the MISSING outcome, and a missing occurrence keeps its group key so an empty column
 * stays distinguishable from an unread one (design D5).
 *
 * <p>A ROW group is the one exception to "per field": its page and its row ORIGIN are resolved
 * once for the whole group, before any of its fields runs, because {@code groupKey} is the key
 * consumers join across fields on (see {@link RowFrame}).
 *
 * <p>All coordinate arithmetic is BigDecimal end to end; no document content reaches a log line
 * or an exception.
 */
@Service
public class DefaultFieldExtractionEngine implements FieldExtractionEngine {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultFieldExtractionEngine.class);

    /** The {@code extractor_version} the persistence side stores on every extracted field. */
    public static final String VERSION = "engine/1.0.0";

    /** A derived field's displayed magnitude: US grouping, two decimals, absolute value. */
    private static final java.text.DecimalFormat MONEY_DISPLAY =
            new java.text.DecimalFormat("#,##0.00",
                    java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));

    /** Derived confidence is this fraction of the weakest input's overall confidence. */
    private static final BigDecimal DERIVED_STRENGTH = new BigDecimal("0.9");
    /** Bounds the regex budget multiplication: a rung tries at most this many label occurrences. */
    static final int MAX_LABEL_OCCURRENCES = 32;

    /** Tolerance when comparing a span's x to the label's right edge (LINE_RIGHT). */
    private static final BigDecimal EDGE_EPSILON = new BigDecimal("0.01");

    /** Exact halving: {@code x / 2} always terminates in base 10, so no scale is needed. */
    private static final BigDecimal TWO = new BigDecimal(2);

    /** A page prepared once per extract() call: joined text and visual lines are field-agnostic. */
    private record PreparedPage(PageContent page, SpanText text, List<List<SpanRef>> lines) {

        static PreparedPage of(PageContent page) {
            // The page-global text a LABEL is looked up in is assembled ROW BY ROW, not from the
            // parser's single page-wide sequence: on a dense masthead that sequence reads two
            // printed rows through each other and splices every caption on both of them apart.
            // See VisualLines#inRowOrder — it resequences, never re-reads.
            return new PreparedPage(
                    page,
                    SpanText.of(VisualLines.inRowOrder(page.spans())),
                    VisualLines.group(page.spans()));
        }
    }

    @Override
    public List<FieldOutcome> extract(SchemaDefinition schema, List<PageContent> pages) {
        List<PreparedPage> prepared = pages.stream().map(PreparedPage::of).toList();
        // ROW groups are resolved ONCE, before any field runs: every field of one group must
        // count its rows from the same origin, and a per-field resolution cannot know that.
        Map<GroupRegionSpec, RowFrame> frames = rowFrames(schema, prepared);
        List<FieldOutcome> outcomes = new ArrayList<>();
        for (FieldSpec field : schema.fields()) {
            try {
                outcomes.addAll(extractField(field, prepared, frames));
            } catch (RegexBudgetExceededException e) {
                // A pattern that could not finish costs ITS field and nothing else. Failing the
                // document would let one pathological pattern in one tenant's authored schema
                // suppress every other field on the page — the opposite of what bounding it is
                // for. A missing outcome is the honest answer and the one the reviewer already
                // knows how to read.
                //
                // A COLUMN group would normally owe one missing occurrence per declared key
                // (design D5); on this path it yields a single missing outcome instead, because
                // the exception unwound before the keys were walked. Exceptional, and better than
                // reconstructing group state inside a catch.
                log.warn(
                        "regex budget exhausted, field skipped: field={} budget={} subjectChars={}",
                        field.name(),
                        e.budget(),
                        e.subjectLength());
                outcomes.add(FieldOutcome.missing(field));
            }
        }
        return derive(schema, outcomes);
    }

    /**
     * Fields with a {@link DerivationSpec} whose rungs captured nothing are computed from the
     * other ungrouped outcomes: {@code sum(plus) - sum(minus)}. A captured rung is never
     * overridden; any input without a normalized number leaves the field MISSING.
     */
    private static List<FieldOutcome> derive(SchemaDefinition schema, List<FieldOutcome> outcomes) {
        Map<String, FieldOutcome> byName = new HashMap<>();
        for (FieldOutcome outcome : outcomes) {
            if (outcome.groupKey() == null) {
                byName.putIfAbsent(outcome.field().name(), outcome);
            }
        }
        List<FieldOutcome> result = new ArrayList<>(outcomes.size());
        for (FieldOutcome outcome : outcomes) {
            DerivationSpec derivation = outcome.field().derivation();
            if (derivation == null || outcome.found() || outcome.groupKey() != null) {
                result.add(outcome);
                continue;
            }
            result.add(derived(outcome.field(), derivation, byName).orElse(outcome));
        }
        return List.copyOf(result);
    }

    private static Optional<FieldOutcome> derived(
            FieldSpec field, DerivationSpec derivation, Map<String, FieldOutcome> byName) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal weakest = BigDecimal.ONE;
        StringBuilder arithmetic = new StringBuilder();
        for (String name : derivation.plus()) {
            FieldOutcome input = byName.get(name);
            if (input == null || !input.found() || input.normalized() == null
                    || input.normalized().number() == null) {
                return Optional.empty();
            }
            total = total.add(input.normalized().number());
            weakest = weakest.min(input.confidence().overall());
            arithmetic.append(arithmetic.isEmpty() ? "" : " + ")
                    .append(name).append('=').append(input.normalized().number().toPlainString());
        }
        for (String name : derivation.minus()) {
            FieldOutcome input = byName.get(name);
            if (input == null || !input.found() || input.normalized() == null
                    || input.normalized().number() == null) {
                return Optional.empty();
            }
            total = total.subtract(input.normalized().number());
            weakest = weakest.min(input.confidence().overall());
            arithmetic.append(" - ").append(name).append('=').append(input.normalized().number().toPlainString());
        }
        if (total.signum() < 0) {
            // The inputs contradict each other (a negative withdrawal means one was misread):
            // the field stays MISSING and a reviewer must look.
            return Optional.empty();
        }
        BigDecimal magnitude = total.setScale(2, RoundingMode.HALF_UP);
        String displayed = ((java.text.DecimalFormat) MONEY_DISPLAY.clone()).format(magnitude);
        return Optional.of(new FieldOutcome(
                field,
                ExtractionMethod.DERIVED,
                DERIVED_STRENGTH.doubleValue(),
                null,
                displayed,
                arithmetic.toString(),
                new NormalizedValue(null, magnitude, null, BigDecimal.ONE),
                List.of(),
                List.of(),
                new ConfidenceBreakdown(weakest, DERIVED_STRENGTH, BigDecimal.ONE),
                null));
    }

    /**
     * One field's outcomes: exactly ONE for an ungrouped field — byte-identically to what this
     * engine did before Spec 5a — and one PER DECLARED KEY for a COLUMN group. Never fewer: the
     * keys come from the schema, so a column the form leaves blank still owes the reviewer a
     * missing occurrence rather than silence (design D5).
     *
     * <p>A ROW group's field is the one arm that is NOT self-contained: it reads the frame its
     * whole group agreed on. Nothing else on this call path sees {@code frames} — the ungrouped
     * and COLUMN arms are exactly what they were.
     */
    private List<FieldOutcome> extractField(
            FieldSpec field, List<PreparedPage> pages, Map<GroupRegionSpec, RowFrame> frames) {
        if (field.group() != null && field.group().kind() == GroupKind.COLUMN) {
            return extractColumnGroup(field, pages);
        }
        if (field.group() != null && field.group().kind() == GroupKind.ROW) {
            GroupRegionSpec region = field.group().region();
            return extractRowGroup(field, region == null ? null : frames.get(region));
        }
        return List.of(extractUngrouped(field, pages));
    }

    private FieldOutcome extractUngrouped(FieldSpec field, List<PreparedPage> pages) {
        for (PreparedPage page : pages) {
            for (ExtractorSpec spec : field.extractors()) {
                Optional<FieldOutcome> outcome = tryRung(field, spec, page, null, null);
                if (outcome.isPresent()) {
                    return outcome.get();
                }
            }
        }
        return FieldOutcome.missing(field);
    }

    // ── COLUMN groups ────────────────────────────────────────────────────────

    /**
     * The rung runs ONCE PER GROUP KEY. Each key is located by its PRINTED column header, and the
     * value must fall inside THAT KEY'S BAND — the band is applied after the rung's own scope has
     * chosen its candidate spans, so an occurrence can only ever be filled from its own column.
     *
     * <p>The band is the invariant; the LINE is not. Only {@code scope: LINE} and
     * {@code LINE_RIGHT} confine the value to the label's own visual line — under
     * {@code scope: PAGE} the candidates are every span on the page and the band alone decides
     * attribution. Saying "on the label's own line" as though it held for all three would
     * describe a guarantee the {@code PAGE} arm does not make.
     */
    private List<FieldOutcome> extractColumnGroup(FieldSpec field, List<PreparedPage> pages) {
        List<FieldOutcome> outcomes = new ArrayList<>();
        for (String key : field.group().keys()) {
            outcomes.add(extractColumnOccurrence(field, pages, key));
        }
        return List.copyOf(outcomes);
    }

    private FieldOutcome extractColumnOccurrence(
            FieldSpec field, List<PreparedPage> pages, String key) {
        for (PreparedPage page : pages) {
            Optional<ColumnBand> band = columnBand(field.group(), key, page);
            if (band.isEmpty()) {
                continue; // this page does not print the group's header row
            }
            for (ExtractorSpec spec : field.extractors()) {
                Optional<FieldOutcome> outcome = tryRung(field, spec, page, band.get(), key);
                if (outcome.isPresent()) {
                    return outcome.get();
                }
            }
        }
        return FieldOutcome.missing(field, key);
    }

    /**
     * One column's horizontal band, half-open {@code [min, max)}: a span belongs to the column
     * whose band contains its box's CENTER. Half-open and center-based so a span can never be
     * claimed by two columns at once — the same reason {@link VisualLines} groups on centers
     * rather than edges.
     */
    private record ColumnBand(String key, BigDecimal min, BigDecimal max) {

        boolean contains(Box box) {
            BigDecimal center = box.x().add(box.width().divide(TWO));
            return center.compareTo(min) >= 0 && center.compareTo(max) < 0;
        }
    }

    private static Optional<ColumnBand> columnBand(
            GroupSpec group, String key, PreparedPage page) {
        return columnBands(group, page)
                .flatMap(
                        bands ->
                                bands.stream()
                                        .filter(band -> band.key().equals(key))
                                        .findFirst());
    }

    /**
     * The group's column bands, measured from the PRINTED key row: locate the header anchor and
     * first inspect its visual line. When that line is not a complete valid key row, the
     * immediately following visual line may supply the keys only when it is the page's sole
     * complete, exact, strictly left-to-right key row. Boundaries are the midpoints between
     * adjacent key centers; the outer edges lie half the nearest pitch beyond the outermost keys.
     * Nothing here is a fixed offset, so a differently scaled scan yields the same attribution
     * (design risk table).
     *
     * <p>Empty — meaning every key on this page is missing rather than guessed — when the header
     * is not on the page, when neither the header line nor its sole-exact adjacent line supplies
     * the keys, when the located keys are not in the declared left-to-right order (something
     * other than the key row matched), or when the group declares fewer than two keys. A column
     * that cannot be BOUNDED must never fall back to an unbounded scope: that is precisely how a
     * confident wrong-column value is manufactured.
     */
    private static Optional<List<ColumnBand>> columnBands(GroupSpec group, PreparedPage page) {
        List<String> keys = group.keys();
        if (keys == null || keys.size() < 2 || group.header() == null) {
            return Optional.empty();
        }
        Optional<SpanText.Range> headerRange =
                page.text().findFirst(SpanText.labelPattern(group.header()));
        if (headerRange.isEmpty()) {
            return Optional.empty();
        }
        List<SpanRef> headerSpans = page.text().overlapping(headerRange.get());
        if (headerSpans.isEmpty()) {
            return Optional.empty();
        }
        List<List<SpanRef>> lines = page.lines();
        List<SpanRef> headerLine = lineContaining(lines, headerSpans.get(0));

        Optional<List<BigDecimal>> centers = orderedKeyCenters(keys, headerLine);
        if (centers.isEmpty()) {
            // The real-form fallback: the caption and its keys occupy ADJACENT visual lines.
            // Fail-closed by construction — the next line only, it must be an EXACT key row,
            // and it must be the page's ONLY exact key row, so a second key band elsewhere on
            // the page (another form's columns) leaves every occurrence missing rather than
            // banding the wrong row.
            int headerLineIndex = lines.indexOf(headerLine);
            if (headerLineIndex < 0 || headerLineIndex + 1 >= lines.size()) {
                return Optional.empty();
            }
            List<SpanRef> adjacentLine = lines.get(headerLineIndex + 1);
            centers = exactOrderedKeyCenters(keys, adjacentLine);
            if (centers.isEmpty()
                    || lines.stream()
                                    .filter(line -> exactOrderedKeyCenters(keys, line).isPresent())
                                    .count()
                            != 1) {
                return Optional.empty();
            }
        }

        return Optional.of(columnBands(keys, centers.get()));
    }

    private static Optional<List<BigDecimal>> orderedKeyCenters(
            List<String> keys, List<SpanRef> line) {
        List<BigDecimal> centers = new ArrayList<>();
        for (String key : keys) {
            SpanRef keySpan = null;
            // The declared key against the PRINTED span, both folded — the schema's
            // "(a)" must find a header a form set with typographic parentheses' neighbours,
            // for the same reason every other authored string in this engine is folded. Still
            // EXACT equality on the trimmed span: a column key is an identity, not a phrase,
            // and containment would let "A" claim a header cell reading "AMOUNT".
            for (SpanRef span : line) {
                if (TextFold.fold(span.text().trim()).equals(TextFold.fold(key))) {
                    keySpan = span;
                    break;
                }
            }
            if (keySpan == null) {
                return Optional.empty();
            }
            centers.add(center(keySpan.box()));
        }
        for (int i = 1; i < centers.size(); i++) {
            if (centers.get(i - 1).compareTo(centers.get(i)) >= 0) {
                return Optional.empty();
            }
        }

        return Optional.of(List.copyOf(centers));
    }

    /**
     * The line read as an EXACT key row: it must carry every declared key — compared through the
     * {@link TextFold} seam on BOTH sides, exactly as {@link #orderedKeyCenters} compares — in
     * the declared left-to-right order. Stricter than the header-line walk on purpose: an
     * adjacent line has no anchoring caption of its own, so exactness is the only thing standing
     * between the fallback and banding an arbitrary line that happens to mention a key.
     */
    private static Optional<List<BigDecimal>> exactOrderedKeyCenters(
            List<String> keys, List<SpanRef> line) {
        List<String> foldedKeys = new ArrayList<>();
        for (String key : keys) {
            foldedKeys.add(TextFold.fold(key));
        }
        List<SpanRef> keySpans =
                line.stream()
                        .filter(span -> foldedKeys.contains(TextFold.fold(span.text().trim())))
                        .toList();
        if (keySpans.size() != keys.size()) {
            return Optional.empty();
        }
        for (int i = 0; i < keys.size(); i++) {
            if (!TextFold.fold(keySpans.get(i).text().trim()).equals(foldedKeys.get(i))) {
                return Optional.empty();
            }
        }
        return orderedKeyCenters(keys, keySpans);
    }

    private static List<ColumnBand> columnBands(List<String> keys, List<BigDecimal> centers) {
        List<ColumnBand> bands = new ArrayList<>();
        for (int i = 0; i < centers.size(); i++) {
            BigDecimal min =
                    i == 0
                            ? centers.get(0).subtract(half(centers.get(1).subtract(centers.get(0))))
                            : half(centers.get(i - 1).add(centers.get(i)));
            BigDecimal max =
                    i == centers.size() - 1
                            ? centers.get(i).add(half(centers.get(i).subtract(centers.get(i - 1))))
                            : half(centers.get(i).add(centers.get(i + 1)));
            bands.add(new ColumnBand(keys.get(i), min, max));
        }
        return List.copyOf(bands);
    }

    private static BigDecimal center(Box box) {
        return box.x().add(half(box.width()));
    }

    private static BigDecimal half(BigDecimal value) {
        return value.divide(TWO);
    }

    /** The scope's spans inside the column band, in scope order. No band ⇒ unchanged. */
    private static List<SpanRef> confine(List<SpanRef> spans, ColumnBand band) {
        if (band == null) {
            return spans;
        }
        List<SpanRef> inside = new ArrayList<>();
        for (SpanRef span : spans) {
            if (band.contains(span.box())) {
                inside.add(span);
            }
        }
        return List.copyOf(inside);
    }

    private Optional<FieldOutcome> tryRung(
            FieldSpec field,
            ExtractorSpec spec,
            PreparedPage page,
            ColumnBand band,
            String groupKey) {
        if (band != null
                && spec.method() != ExtractionMethod.ANCHOR_LABEL
                && spec.method() != ExtractionMethod.LABEL_BELOW
                && spec.method() != ExtractionMethod.LABEL_ABOVE) {
            // Under a COLUMN group only the LABEL-anchored rungs run. A page-wide REGEX, a
            // detector rung, or a TABLE_CLUSTER cell has no column to be confined to, so it would
            // answer EVERY key with the same value — three properties reported as three copies of
            // one, at full confidence, each with its own evidence box. The rung fails and the
            // occurrence goes missing, which is the honest answer.
            return Optional.empty();
        }
        return switch (spec.method()) {
            case ANCHOR_LABEL -> anchorLabel(field, spec, page, band, groupKey);
            case LABEL_BELOW -> labelBelow(field, spec, page, band, groupKey);
            case LABEL_ABOVE -> labelAbove(field, spec, page, band, groupKey);
            case TABLE_CLUSTER -> tableCluster(field, spec, page);
            case REGEX -> regex(field, spec, page);
            case CHECKBOX_STATE -> checkboxState(field, spec, page);
            case SIGNATURE_PRESENCE -> signaturePresence(field, spec, page);
            // FORM_FIELD, OCR_LINE, LLM, HUMAN arrive in later phases: an unimplemented rung
            // fails and the ladder moves on — never an error.
            default -> Optional.empty();
        };
    }

    // ── ANCHOR_LABEL ─────────────────────────────────────────────────────────

    /**
     * Every occurrence of the label is tried in reading order; the first whose scope captures
     * wins. A header row that prints the caption with nothing beside it no longer starves the
     * real line below it (ANB Bank, 2026-09-22).
     */
    private Optional<FieldOutcome> anchorLabel(
            FieldSpec field,
            ExtractorSpec spec,
            PreparedPage page,
            ColumnBand band,
            String groupKey) {
        int lineOffset = spec.value().lineOffset();
        if (lineOffset > 0
                && (band == null
                        || spec.value().scope() != ValueScope.LINE
                        || spec.value().occurrence() != 0)) {
            return Optional.empty();
        }
        List<SpanText.Range> occurrences = page.text().findAll(SpanText.labelPattern(spec.label()));
        // PAGE scope with no line offset scopes the same page spans whatever the occurrence, so
        // a second attempt can only repeat the first.
        int attempts = spec.value().scope() == ValueScope.PAGE && lineOffset == 0
                ? Math.min(1, occurrences.size())
                : Math.min(MAX_LABEL_OCCURRENCES, occurrences.size());
        for (SpanText.Range labelRange : occurrences.subList(0, attempts)) {
            Optional<FieldOutcome> outcome =
                    anchorLabelAt(field, spec, page, band, groupKey, labelRange, lineOffset);
            if (outcome.isPresent()) {
                return outcome;
            }
        }
        return Optional.empty();
    }

    /** One label occurrence: the existing scope / band / confine / capture logic, unchanged. */
    private Optional<FieldOutcome> anchorLabelAt(
            FieldSpec field,
            ExtractorSpec spec,
            PreparedPage page,
            ColumnBand band,
            String groupKey,
            SpanText.Range labelRange,
            int lineOffset) {
        List<SpanRef> labelSpans = page.text().overlapping(labelRange);
        if (labelSpans.isEmpty()) {
            // A zero-width or joiner-only label match maps to no span. There is no line to
            // scope from and no box to cite: the rung fails and the ladder moves on. Reading
            // labelSpans.get(0) here threw out of the engine and failed the whole stage.
            return Optional.empty();
        }
        List<SpanRef> line = lineContaining(page.lines(), labelSpans.get(0));
        List<SpanRef> scopeSpans;
        if (lineOffset > 0) {
            int labelLineIndex = lineIndexContaining(page.lines(), labelSpans.get(0));
            int targetIndex = labelLineIndex + lineOffset;
            if (labelLineIndex < 0 || targetIndex >= page.lines().size()) {
                return Optional.empty();
            }
            scopeSpans = page.lines().get(targetIndex);
        } else {
            scopeSpans =
                    switch (spec.value().scope()) {
                        case LINE -> line;
                        case LINE_RIGHT -> rightOfLabel(line, labelSpans);
                        case PAGE -> page.page().spans();
                    };
        }
        List<SpanRef> confined = confine(scopeSpans, band);
        if (confined.isEmpty()) {
            // Under a column group this is THE EMPTY COLUMN: the label's own line carries nothing
            // inside this key's band. The rung fails and the occurrence goes MISSING rather than
            // reaching sideways into a neighbouring column — a wrong-column value is confident,
            // evidence-backed and wrong, which is worse than missing. Ungrouped, `confined` is
            // `scopeSpans` and an empty scope already failed inside capture(), so this early exit
            // changes nothing for a field with no group.
            return Optional.empty();
        }
        List<EvidenceRef> labelEvidence = spanEvidence(labelSpans, null);
        SpanText confinedText = SpanText.of(confined);
        if (lineOffset > 0) {
            // The offset line must carry EXACTLY ONE value match — a second money-like token
            // means attribution is ambiguous and the rung fails closed. The pattern is compiled
            // through SpanText.valuePattern (the TextFold seam), never raw Pattern.compile, so
            // this guard and capture() below cannot disagree about what matches.
            Pattern valuePattern = SpanText.valuePattern(spec.value().pattern());
            if (confinedText.findOccurrence(valuePattern, 0).isEmpty()
                    || confinedText.findOccurrence(valuePattern, 1).isPresent()) {
                return Optional.empty();
            }
        }
        return capture(field, spec, page, confinedText, labelEvidence, null, groupKey);
    }

    /** The visual line holding the label's FIRST span (identity, not equality — spans may repeat). */
    private static List<SpanRef> lineContaining(List<List<SpanRef>> lines, SpanRef span) {
        for (List<SpanRef> line : lines) {
            for (SpanRef candidate : line) {
                if (candidate == span) {
                    return line;
                }
            }
        }
        return List.of(span);
    }

    private static int lineIndexContaining(List<List<SpanRef>> lines, SpanRef span) {
        for (int i = 0; i < lines.size(); i++) {
            if (containsIdentity(lines.get(i), span)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The line's spans strictly right of the label: x at or past the label's right edge (max of
     * {@code x + width} over the label spans on this line, less a small epsilon), label spans
     * themselves excluded. Already in x order because the line is.
     */
    private static List<SpanRef> rightOfLabel(List<SpanRef> line, List<SpanRef> labelSpans) {
        BigDecimal rightEdge = null;
        for (SpanRef label : labelSpans) {
            if (!containsIdentity(line, label)) {
                continue;
            }
            BigDecimal edge = label.box().x().add(label.box().width());
            rightEdge = rightEdge == null ? edge : rightEdge.max(edge);
        }
        if (rightEdge == null) {
            return List.of();
        }
        BigDecimal cutoff = rightEdge.subtract(EDGE_EPSILON);
        List<SpanRef> right = new ArrayList<>();
        for (SpanRef span : line) {
            if (containsIdentity(labelSpans, span)) {
                continue;
            }
            if (span.box().x().compareTo(cutoff) >= 0) {
                right.add(span);
            }
        }
        return List.copyOf(right);
    }

    private static boolean containsIdentity(List<SpanRef> spans, SpanRef span) {
        for (SpanRef candidate : spans) {
            if (candidate == span) {
                return true;
            }
        }
        return false;
    }

    // ── LABEL_BELOW ──────────────────────────────────────────────────────────

    /**
     * The cell bounds a rung inherits when its schema omits them. Mirrors {@code
     * ExtractionSchemaLoader}'s defaults so a hand-built spec behaves like a loaded one.
     */
    private static final BigDecimal DEFAULT_MAX_DROP_PT = new BigDecimal("24.0");

    private static final BigDecimal DEFAULT_CELL_OVERLAP = new BigDecimal("0.5");

    /**
     * LABEL_ABOVE's vertical reach when its schema omits it — {@link #DEFAULT_MAX_DROP_PT}'s
     * mirror, and the same number on purpose.
     */
    private static final BigDecimal DEFAULT_MAX_RISE_PT = new BigDecimal("24.0");

    /**
     * The box-grid rung: the label captions a cell and the value sits on the next line INSIDE
     * that cell. Same anchor matcher as {@link #anchorLabel} — case-insensitive over the
     * ORIGINAL text, never a lowered copy, whose length can drift and shift every later offset.
     *
     * <p>Canonical space is top-left with y increasing DOWNWARD, so a candidate line is one
     * whose TOP edge lies at or below the label's BOTTOM edge and no more than {@code
     * maxDropPt} past it. That test also excludes the label's own line for free: a line
     * containing the label starts at or above the label's top, which is above its bottom.
     *
     * <p><b>The cell's x-window is NOT the caption's own width.</b> On a real box-grid form
     * the captions partition the row: a value owns the space from its caption's left edge to
     * the NEXT printed caption's left edge (Schedule E's name box runs from "Name(s) shown on
     * return" at x&nbsp;36 all the way to "Your social security number" at x&nbsp;457 — the
     * caption itself ends at x&nbsp;114, and a filed joint name extends far past it). So the
     * window's right edge is the left edge of the first span on the caption's OWN visual line
     * that starts at or past the label's right edge; when the caption is the last thing on its
     * line there is no printed boundary and the window stays the label's own extent —
     * fail-closed, because widening without printed evidence is how a same-row neighbour's
     * value would be admitted. Both edges derive from printed captions, never fixed offsets
     * (Spec 4's rule).
     *
     * <p>Within those lines, a span joins the cell when at least {@code cellOverlap} of ITS
     * OWN width lies inside that window — {@link #ownsSpan}. Ownership is measured against
     * the SPAN, never the narrower of the two boxes: a real W-2 printed its employer name as
     * one wide run that merely CROSSED the employee-name caption's column, and the
     * narrower-box test admitted it (the crossing covered the caption's width) — a
     * person-shaped wrong value at 0.9. A run mostly inside another caption's zone belongs to
     * that zone. The two rules pull in opposite directions and are settled together: the
     * WIDENED window is what lets a legitimately wide value pass the strict ownership test in
     * its own cell, while ownership is what keeps the widening from ever reaching into the
     * neighbour's — the same wide span is refused by the cell it merely crosses and kept by
     * the cell that holds its majority.
     *
     * <p>Admission is PER SPAN, and the cell's lines are assembled from the ADMITTED spans
     * alone — never taken from the page-global {@link VisualLines} grouping. The page-global
     * lines chain on center proximity, and one tall foreign run can bridge two printed rows:
     * on a real W-2, box 12a's vertical "Code" caption (a rotated run 25&nbsp;pt tall at
     * x&nbsp;341) merged the box&nbsp;e caption row and the REAL employee-name row into one
     * visual line whose top was the caption's own, and a drop gate that read that line's top
     * discarded the name as "the label's own line" — the address row below answered instead,
     * person-shaped street words at 0.9. So each span is tested on its OWN box: its top must
     * lie at or below the label's bottom edge and no more than {@code maxDropPt} past it
     * (which also excludes every caption span for free), it must pass {@link #ownsSpan}, and
     * only then do the admitted spans regroup into the cell's own lines.
     *
     * <p><b>The first of those lines OWNS the cell.</b> The value match resolves on that ONE
     * line, never across lines and never below them. Both halves are load-bearing. Across:
     * a scope that joined the cell's lines handed the value pattern a seam nobody printed —
     * on a real Schedule E the joint name under "Name(s) shown on return" came back with the
     * NEXT line's capitalized words glued on, an 0.9-confidence value whose tail exists as
     * contiguous text nowhere on the page. Below: skipping a matchless line to offer the one
     * beneath it is how a SHAPE MISMATCH on the true value line becomes a confident wrong
     * value from a lower line — the cell legitimately admits two text rows ({@code maxDropPt}
     * 24), so the row below the value is routinely a different printed fact (the address
     * under a name, the next caption's continuation), and whatever in it happens to match the
     * pattern is wrong by construction. If the owning line does not carry match #occurrence,
     * the rung FAILS and lower lines are never consulted: missing-over-wrong is the governing
     * rule (design D5). The owning line goes to {@link #capture} in reading order (spans
     * left-to-right), so confidence and evidence are computed by exactly the same code every
     * other rung uses.
     *
     * <p>No admitted span at all ⇒ the rung FAILS and the ladder moves on. That is the whole
     * point of the bounds: on a W-2 box 3 sits directly beneath box 1, and a rung that reached
     * into the wrong cell would report a confident wrong value with a plausible evidence box —
     * strictly worse than the missing field it replaced (design D5).
     *
     * <p>Deliberately NO interaction with {@code lineOffset} (the V14 relative-line selector):
     * the schema loader pins a nonzero offset to ANCHOR_LABEL, so a LABEL_BELOW rung never
     * carries one and the first-line rule can never fight an explicitly authored offset.
     *
     * <p>Deliberately independent of ruling detection (design D2): many lender forms draw boxes
     * with no lines at all, and the worker skips rulings on rotated pages. {@code TABLE_CLUSTER}
     * remains the rung for genuinely ruled grids.
     */
    private Optional<FieldOutcome> labelBelow(
            FieldSpec field,
            ExtractorSpec spec,
            PreparedPage page,
            ColumnBand band,
            String groupKey) {
        Optional<SpanText.Range> labelRange =
                page.text().findFirst(SpanText.labelPattern(spec.label()));
        if (labelRange.isEmpty()) {
            return Optional.empty();
        }
        List<SpanRef> labelSpans = page.text().overlapping(labelRange.get());
        if (labelSpans.isEmpty()) {
            // A zero-width or joiner-only label match maps to no span: no box to measure a cell
            // from and none to cite. The rung fails and the ladder moves on.
            return Optional.empty();
        }
        Box labelBox = unionBox(labelSpans);
        BigDecimal labelBottom = labelBox.y().add(labelBox.height());
        Box cellBox = cellWindow(labelBox, labelSpans, page);
        BigDecimal maxDrop =
                spec.maxDropPt() == null
                        ? DEFAULT_MAX_DROP_PT
                        : BigDecimal.valueOf(spec.maxDropPt());
        BigDecimal overlapFraction =
                spec.cellOverlap() == null
                        ? DEFAULT_CELL_OVERLAP
                        : BigDecimal.valueOf(spec.cellOverlap());

        // Admission is PER SPAN against the cell's own geometry — see the class comment. The
        // page-global visual lines are deliberately not consulted: one tall foreign run (a
        // rotated caption) can merge the caption row and the true value row into a single
        // page-global line, and a gate reading that line's top would discard the value as
        // "the label's own line".
        List<SpanRef> admitted = new ArrayList<>();
        for (SpanRef span : page.page().spans()) {
            BigDecimal top = span.box().y();
            if (top.compareTo(labelBottom) < 0) {
                continue; // the caption itself, or anything above the cell
            }
            if (top.subtract(labelBottom).compareTo(maxDrop) > 0) {
                continue; // too far below to still be this label's cell
            }
            if (ownsSpan(cellBox, span.box(), overlapFraction)) {
                admitted.add(span);
            }
        }
        List<SpanRef> confined = confine(List.copyOf(admitted), band);
        if (confined.isEmpty()) {
            return Optional.empty();
        }
        // The admitted spans regroup into the CELL'S OWN lines, and the first line owns the
        // cell: either it carries match #occurrence or the rung fails. Lower lines are never
        // consulted — skip-down is how a shape mismatch on the true value line becomes a
        // confident wrong value from the printed fact below it (see the class comment).
        List<SpanRef> owningLine = VisualLines.group(confined).get(0);
        List<SpanRef> anchoringSpans = new ArrayList<>(labelSpans);
        List<SpanRef> composed = new ArrayList<>(owningLine);
        if (spec.joinCells() != null) {
            // The adjacent cells on the caption's OWN row, read on the owning line's own row.
            // Each join is the FIRST match rightward on the label's row — the spans to the
            // right of the label on its own visual line — so a Form 1040's spouse row can
            // never borrow the taxpayer row's "Last name" cell; an empty joined cell
            // contributes nothing.
            List<SpanRef> captionRow =
                    rightOfLabel(lineContaining(page.lines(), labelSpans.get(0)), labelSpans);
            for (LabelSpec join : spec.joinCells()) {
                joinedCell(join, captionRow, page, band, owningLine, maxDrop, overlapFraction)
                        .ifPresent(
                                cell -> {
                                    composed.addAll(cell.valueSpans());
                                    anchoringSpans.addAll(cell.labelSpans());
                                });
            }
            composed.sort(Comparator.comparing(span -> span.box().x()));
        }
        List<EvidenceRef> labelEvidence = spanEvidence(List.copyOf(anchoringSpans), null);
        // Compiled through SpanText.valuePattern (the TextFold seam), never raw Pattern.compile,
        // so this owning-line guard and capture() below cannot disagree about what matches.
        Pattern valuePattern = SpanText.valuePattern(spec.value().pattern());
        SpanText owningText = SpanText.of(List.copyOf(composed));
        if (owningText.findOccurrence(valuePattern, spec.value().occurrence()).isEmpty()) {
            return Optional.empty(); // the owning line has no value: MISSING over wrong
        }
        // A normalization failure inside capture() fails the RUNG, exactly as before: letting
        // a lower line answer instead would be a new way to guess.
        return capture(field, spec, page, owningText, labelEvidence, null, groupKey);
    }

    /** One joined cell: the caption spans that located it and its first value line's spans. */
    private record JoinedCell(List<SpanRef> labelSpans, List<SpanRef> valueSpans) {}

    /**
     * The joined cell's contribution to a LABEL_BELOW value: the join caption located among the
     * spans to the RIGHT of the anchoring label on its own visual line, its cell derived exactly
     * as the anchoring cell was ({@link #cellWindow}, the same drop and the same ownership
     * fraction, measured from the join caption's own bottom edge), and its FIRST value line
     * taken only when it is the same printed row as the anchoring cell's owning line — a value
     * one row down under the joined caption belongs to that row's field, not to this one.
     *
     * <p>Measured on the real forms that motivated it (2026-09-14): a filled Form W-2 prints the
     * surname 2 pt left of the "Last name" caption on the same row as the first name; a Form
     * 1040 prints "Last name" on BOTH identity rows, so the row binding is what keeps the
     * spouse's rung on the spouse's row. Empty ⇒ nothing is joined and the anchoring cell's own
     * text is what the value pattern sees; the rung never fails because a join is empty.
     */
    private static Optional<JoinedCell> joinedCell(
            LabelSpec join,
            List<SpanRef> captionRow,
            PreparedPage page,
            ColumnBand band,
            List<SpanRef> owningLine,
            BigDecimal maxDrop,
            BigDecimal overlapFraction) {
        if (captionRow.isEmpty()) {
            return Optional.empty();
        }
        SpanText rowText = SpanText.of(captionRow);
        Optional<SpanText.Range> joinRange = rowText.findFirst(SpanText.labelPattern(join));
        if (joinRange.isEmpty()) {
            return Optional.empty();
        }
        List<SpanRef> joinSpans = rowText.overlapping(joinRange.get());
        if (joinSpans.isEmpty()) {
            return Optional.empty();
        }
        Box joinBox = unionBox(joinSpans);
        BigDecimal joinBottom = joinBox.y().add(joinBox.height());
        Box joinCell = cellWindow(joinBox, joinSpans, page);
        List<SpanRef> admitted = new ArrayList<>();
        for (SpanRef span : page.page().spans()) {
            BigDecimal top = span.box().y();
            if (top.compareTo(joinBottom) < 0 || top.subtract(joinBottom).compareTo(maxDrop) > 0) {
                continue;
            }
            if (ownsSpan(joinCell, span.box(), overlapFraction)) {
                admitted.add(span);
            }
        }
        List<SpanRef> confined = confine(List.copyOf(admitted), band);
        if (confined.isEmpty()) {
            return Optional.empty();
        }
        List<SpanRef> firstLine = VisualLines.group(confined).get(0);
        if (!sameRow(unionBox(owningLine), unionBox(firstLine))) {
            return Optional.empty();
        }
        return Optional.of(new JoinedCell(joinSpans, firstLine));
    }

    /**
     * Two boxes print on the same row when their vertical centers lie within half the smaller
     * height of each other — {@code SpanJoin}'s own row test, restated on union boxes so the
     * join and the separator it will be joined with cannot disagree about what a row is.
     */
    private static boolean sameRow(Box a, Box b) {
        BigDecimal em = a.height().min(b.height());
        if (em.signum() <= 0) {
            return false;
        }
        BigDecimal centerA = a.y().add(half(a.height()));
        BigDecimal centerB = b.y().add(half(b.height()));
        return centerA.subtract(centerB).abs().compareTo(half(em)) <= 0;
    }

    /**
     * A span this many ems (the smaller of the two heights, as {@code SpanJoin} measures it) or
     * less past the end of the caption's run is still the SAME caption, not the next one.
     *
     * <p>The label a rung names is routinely a PREFIX of the caption a form prints: the IRS
     * literal "Medicare wages" sits inside "5 Medicare wages and tips", "State wages" inside
     * "16 State wages, tips, etc.". The words that follow the match are the caption's own tail,
     * and treating the first of them as "the next caption" collapses the cell to the width of
     * the matched words. A value the form sets flush right in its box then falls outside the
     * cell and is never admitted — which is how a real ADP W-2 lost boxes 5 and 16 while box 3,
     * whose literal happens to run to the caption's end, survived (2026-09-05). Left-aligned
     * values overlap the collapsed cell by accident of position, which is why the box-grid
     * fixtures never exposed it.
     *
     * <p>Words within a caption sit 0.3–0.5 em apart; the next box's caption on a grid begins
     * several ems on. One em separates the two with margin on both sides, and it is the same
     * unit {@code SpanJoin} uses for its 0.10-em fuse threshold, so the two rules cannot drift
     * into different notions of what a gap is.
     */
    private static final BigDecimal RUN_GAP_EM = BigDecimal.ONE;

    /**
     * The LABEL_BELOW cell's x-window: from the caption's left edge to the left edge of the
     * next caption on the caption's own visual line, or the caption's own right edge when
     * nothing prints to its right there (fail-closed — no printed boundary, no widening; see
     * {@link #labelBelow}'s class comment for why both halves are load-bearing). "Next
     * caption" is the first non-label span past the end of the caption's RUN: the matched
     * words plus whatever continues them within {@link #RUN_GAP_EM} — on a box-grid form the
     * only thing printed beside a caption ON THE CAPTION ROW is the next box's caption, and
     * it begins well beyond a word gap. The same {@link #EDGE_EPSILON} tolerance as {@link
     * #rightOfLabel} covers kerning overlap, and the boundary is clamped to the run's right
     * edge so an abutting neighbour can never NARROW the cell below the caption's own extent.
     * The window's top and height are the label's own — only {@link #ownsSpan}'s horizontal
     * test reads them.
     */
    private static Box cellWindow(Box labelBox, List<SpanRef> labelSpans, PreparedPage page) {
        BigDecimal labelRight = labelBox.x().add(labelBox.width());
        BigDecimal cutoff = labelRight.subtract(EDGE_EPSILON);
        List<SpanRef> rightward =
                lineContaining(page.lines(), labelSpans.get(0)).stream()
                        .filter(span -> !containsIdentity(labelSpans, span))
                        .filter(span -> span.box().x().compareTo(cutoff) >= 0)
                        .sorted((a, b) -> a.box().x().compareTo(b.box().x()))
                        .toList();
        BigDecimal runRight = labelRight;
        BigDecimal nextCaptionX = null;
        for (SpanRef span : rightward) {
            BigDecimal em = span.box().height().min(labelBox.height());
            BigDecimal gap = span.box().x().subtract(runRight);
            if (em.signum() > 0 && gap.compareTo(RUN_GAP_EM.multiply(em)) <= 0) {
                // The caption's own tail: extend the run and keep walking.
                runRight = runRight.max(span.box().x().add(span.box().width()));
                continue;
            }
            nextCaptionX = span.box().x();
            break;
        }
        BigDecimal right = nextCaptionX == null ? runRight : nextCaptionX.max(runRight);
        return new Box(labelBox.x(), labelBox.y(), right.subtract(labelBox.x()), labelBox.height());
    }

    /**
     * The OWNERSHIP test: at least {@code fraction} of the SPAN'S OWN width lies inside the
     * cell window. Always the span's width — never the narrower of the two boxes, which is
     * what let a wide foreign run that merely CROSSED a caption's column into its cell (it
     * covered the caption's width; most of it lay in the neighbouring box). A caption
     * routinely three times the width of the amount beneath it still admits that amount —
     * the amount's own width is the measure, and it sits inside the window. Touching edges
     * are not an overlap.
     */
    private static boolean ownsSpan(Box cell, Box span, BigDecimal fraction) {
        BigDecimal left = cell.x().max(span.x());
        BigDecimal right = cell.x().add(cell.width()).min(span.x().add(span.width()));
        BigDecimal overlap = right.subtract(left);
        if (overlap.signum() <= 0) {
            return false;
        }
        if (span.width().signum() <= 0) {
            return false;
        }
        return overlap.compareTo(span.width().multiply(fraction)) >= 0;
    }

    // ── LABEL_ABOVE ──────────────────────────────────────────────────────────

    /**
     * The tile rung — {@link #labelBelow}'s vertical mirror: the value sits on the line directly
     * ABOVE its caption. That is how a bank's online activity print-out states a balance — the
     * amount in a larger face with its caption beneath it ("$4,812.33" over "Present balance"),
     * several such tiles across one row — and how dashboard-style summaries generally read.
     * Measured on the real print-out that motivated it (2026-09-02, the five-page checking
     * print-out V38/V39 were written for): the amount's bottom edge sits 3.7 pt above its
     * caption's top edge, the caption row's next tile begins 50 pt right of this caption's right
     * edge, and that next tile's own amount starts 0.4 pt inside this tile's window and runs 65
     * pt outside it. On that document the V39 ANCHOR_LABEL rung looked RIGHT along the caption
     * row, found the next tile's caption words, matched no amount and left the one field the
     * genre actually states MISSING — with the classification anchor for the very same caption
     * lit.
     *
     * <p>Everything horizontal is LABEL_BELOW's, reused rather than restated: the cell's x-window
     * runs from the caption's left edge to the next caption's left edge on the caption row
     * ({@link #cellWindow}), and a span joins the cell only when the majority of ITS OWN width
     * lies inside that window ({@link #ownsSpan}). That ownership test is exactly what keeps the
     * next tile's amount out.
     *
     * <p>Two rules mirror. The vertical gate reads the other way: a span is admitted when its
     * BOTTOM edge lies at or above the caption's TOP edge and no more than {@code maxRisePt}
     * above it — which excludes every caption span for free, since a span's own bottom is below
     * its top. And the LAST of the cell's own lines owns the cell, not the first: it is the line
     * nearest the caption, and the lines above it are never consulted, for the reason
     * LABEL_BELOW never consults the lines below its owning line — a cell that admits 24 pt can
     * hold two printed rows, and the farther one is a different fact (the account caption over a
     * tile), so whatever in it matches the pattern is wrong by construction. No match on the
     * owning line fails the rung: missing over wrong (design D5).
     *
     * <p>Deliberately NOT expressed as a negative {@code lineOffset} on ANCHOR_LABEL. That
     * selector takes a whole page-global visual line, and the line above a tile caption carries
     * EVERY tile's amount — three balances on the measured print-out, of which occurrence 0 is
     * right only while this tile happens to be leftmost. The cell window is what makes the
     * reading position-independent.
     */
    private Optional<FieldOutcome> labelAbove(
            FieldSpec field,
            ExtractorSpec spec,
            PreparedPage page,
            ColumnBand band,
            String groupKey) {
        Optional<SpanText.Range> labelRange =
                page.text().findFirst(SpanText.labelPattern(spec.label()));
        if (labelRange.isEmpty()) {
            return Optional.empty();
        }
        List<SpanRef> labelSpans = page.text().overlapping(labelRange.get());
        if (labelSpans.isEmpty()) {
            return Optional.empty(); // a joiner-only match: no box to measure a cell from
        }
        Box labelBox = unionBox(labelSpans);
        BigDecimal labelTop = labelBox.y();
        Box cellBox = cellWindow(labelBox, labelSpans, page);
        BigDecimal maxRise =
                spec.maxRisePt() == null
                        ? DEFAULT_MAX_RISE_PT
                        : BigDecimal.valueOf(spec.maxRisePt());
        BigDecimal overlapFraction =
                spec.cellOverlap() == null
                        ? DEFAULT_CELL_OVERLAP
                        : BigDecimal.valueOf(spec.cellOverlap());

        // Admission is PER SPAN against the cell's own geometry, exactly as labelBelow and for
        // the same reason: a page-global visual line can be bridged by one tall foreign run.
        List<SpanRef> admitted = new ArrayList<>();
        for (SpanRef span : page.page().spans()) {
            BigDecimal bottom = span.box().y().add(span.box().height());
            if (bottom.compareTo(labelTop) > 0) {
                continue; // the caption itself, or anything on or below its row
            }
            if (labelTop.subtract(bottom).compareTo(maxRise) > 0) {
                continue; // too far above to still be this caption's tile
            }
            if (ownsSpan(cellBox, span.box(), overlapFraction)) {
                admitted.add(span);
            }
        }
        List<SpanRef> confined = confine(List.copyOf(admitted), band);
        if (confined.isEmpty()) {
            return Optional.empty();
        }
        // The admitted spans regroup into the tile's own lines and the LAST — nearest the
        // caption — owns it. Higher lines are never consulted (see the class comment).
        List<List<SpanRef>> cellLines = VisualLines.group(confined);
        List<SpanRef> owningLine = cellLines.get(cellLines.size() - 1);
        List<EvidenceRef> labelEvidence = spanEvidence(labelSpans, null);
        Pattern valuePattern = SpanText.valuePattern(spec.value().pattern());
        SpanText owningText = SpanText.of(owningLine);
        if (owningText.findOccurrence(valuePattern, spec.value().occurrence()).isEmpty()) {
            return Optional.empty(); // the owning line has no value: MISSING over wrong
        }
        return capture(field, spec, page, owningText, labelEvidence, null, groupKey);
    }

    /**
     * ROW_CELL's "same column" test: the two boxes' horizontal extents overlap by at least
     * {@code fraction} of the NARROWER of the two. Narrower, not wider, because a caption is
     * routinely three times the width of the amount beneath it — measuring against the caption
     * would reject every real value. Touching edges are not an overlap.
     *
     * <p>DELIBERATELY not {@link #ownsSpan}: a LABEL_BELOW cell can derive its true right
     * boundary from the next caption on its caption row, so it can afford to demand a span's
     * majority; a ROW_CELL column knows only its header anchor's own extent — the data below
     * a short header like "(a) Name" is routinely WIDER than the header box, and demanding
     * the span's majority inside the header's extent would empty every such column. The two
     * rungs answered one question with one test until the window/ownership split (the wide
     * foreign-run defect); they now answer two different questions, and this note is the
     * record of why.
     */
    private static boolean sharesCell(Box label, Box span, BigDecimal fraction) {
        BigDecimal left = label.x().max(span.x());
        BigDecimal right = label.x().add(label.width()).min(span.x().add(span.width()));
        BigDecimal overlap = right.subtract(left);
        if (overlap.signum() <= 0) {
            return false;
        }
        BigDecimal narrower = label.width().min(span.width());
        if (narrower.signum() <= 0) {
            return false;
        }
        return overlap.compareTo(narrower.multiply(fraction)) >= 0;
    }

    // ── TABLE_CLUSTER ────────────────────────────────────────────────────────

    private Optional<FieldOutcome> tableCluster(
            FieldSpec field, ExtractorSpec spec, PreparedPage page) {
        for (LayoutNode table : page.page().tables()) {
            if (table.type() != LayoutElementType.TABLE) {
                continue;
            }
            Optional<FieldOutcome> outcome = tryTable(field, spec, page, table);
            if (outcome.isPresent()) {
                return outcome;
            }
        }
        return Optional.empty();
    }

    private Optional<FieldOutcome> tryTable(
            FieldSpec field, ExtractorSpec spec, PreparedPage page, LayoutNode table) {
        List<LayoutNode> rows =
                table.children().stream()
                        .filter(child -> child.type() == LayoutElementType.TABLE_ROW)
                        .filter(child -> child.row() != null)
                        .toList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        // The header cell: a cell in the TOPMOST row whose text matches the column header.
        int topRow = rows.stream().mapToInt(LayoutNode::row).min().orElseThrow();
        Optional<LayoutNode> headerCell =
                rows.stream()
                        .filter(row -> row.row() == topRow)
                        .flatMap(row -> cells(row).stream())
                        .filter(cell -> cell.col() != null)
                        .filter(cell -> matches(spec.table().columnHeader(), cellText(cell)))
                        .findFirst();
        if (headerCell.isEmpty()) {
            return Optional.empty();
        }
        Integer column = headerCell.get().col();

        // The label row: the row whose col-0 cell matches the row label.
        LayoutNode rowLabelCell = null;
        LayoutNode labelRow = null;
        for (LayoutNode row : rows) {
            Optional<LayoutNode> candidate =
                    cells(row).stream()
                            .filter(cell -> Integer.valueOf(0).equals(cell.col()))
                            .filter(cell -> matches(spec.table().rowLabel(), cellText(cell)))
                            .findFirst();
            if (candidate.isPresent()) {
                labelRow = row;
                rowLabelCell = candidate.get();
                break;
            }
        }
        if (labelRow == null) {
            return Optional.empty();
        }

        // The value cell: where the label row crosses the header column.
        Optional<LayoutNode> valueCell =
                cells(labelRow).stream().filter(cell -> column.equals(cell.col())).findFirst();
        if (valueCell.isEmpty()) {
            return Optional.empty();
        }

        // The label IS evidence: header cell first, then the row-label cell, each stamped with
        // its OWN cell's element id.
        List<EvidenceRef> labelEvidence = new ArrayList<>();
        labelEvidence.addAll(spanEvidence(headerCell.get().spans(), headerCell.get().id()));
        labelEvidence.addAll(spanEvidence(rowLabelCell.spans(), rowLabelCell.id()));

        return capture(
                field,
                spec,
                page,
                SpanText.of(valueCell.get().spans()),
                List.copyOf(labelEvidence),
                valueCell.get().id(),
                null);
    }

    private static List<LayoutNode> cells(LayoutNode row) {
        return row.children().stream()
                .filter(child -> child.type() == LayoutElementType.TABLE_CELL)
                .toList();
    }

    /**
     * A cell's text EXACTLY as printed. Its only consumer is {@link #matches}, which folds before
     * comparing — a value read out of a cell goes through {@link #capture} on a real {@link
     * SpanText} instead, so it keeps its offsets and its evidence spans.
     */
    private static String cellText(LayoutNode cell) {
        return SpanText.of(cell.spans()).text();
    }

    /**
     * Literal = case-insensitive containment; regex = structurally as authored. BOTH sides pass
     * through the {@link TextFold} seam, exactly as {@link SpanText} does for every other rung: a
     * detected grid whose header cell prints {@code Employer’s} must answer a schema authored with
     * {@code Employer's}. No offset survives this call — only a yes/no — so the fold is applied to
     * the cell text directly here.
     */
    private static boolean matches(LabelSpec label, String text) {
        return SpanText.labelPattern(label)
                .matcher(BoundedCharSequence.over(TextFold.fold(text)))
                .find();
    }

    // ── REGEX ────────────────────────────────────────────────────────────────

    private Optional<FieldOutcome> regex(FieldSpec field, ExtractorSpec spec, PreparedPage page) {
        // No label: the scope is the page's full joined reading-order text, evidence value-only.
        return capture(field, spec, page, page.text(), List.of(), null, null);
    }

    // ── CHECKBOX_STATE ───────────────────────────────────────────────────────

    /** One option's label located on the page, bound to its nearest in-reach checkbox. */
    private record MappedOption(
            CheckboxOption option, List<SpanRef> labelSpans, DetectionRef checkbox) {}

    /**
     * Each option's label anchor binds to the NEAREST checkbox detection whose center lies
     * within {@code proximityPt} of the label box's edge. Exactly one checked binding wins the
     * field; zero, or more than one — including one checked box claimed by two labels — fails
     * the rung: a filer whose ink cannot be attributed to one option is a review case, not a
     * guess (D6). The detection's confidence occupies the spanConfidence slot; the VALUE
     * evidence is the checkbox ELEMENT (box + element id, no span).
     */
    private Optional<FieldOutcome> checkboxState(
            FieldSpec field, ExtractorSpec spec, PreparedPage page) {
        List<DetectionRef> checkboxes =
                page.page().detections().stream()
                        .filter(detection -> detection.type() == LayoutElementType.CHECKBOX)
                        .toList();
        if (checkboxes.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal cap = BigDecimal.valueOf(spec.proximityPt());
        BigDecimal capSquared = cap.multiply(cap);
        List<MappedOption> mapped = new ArrayList<>();
        for (CheckboxOption option : spec.options()) {
            Optional<SpanText.Range> range =
                    page.text().findFirst(SpanText.labelPattern(option.label()));
            if (range.isEmpty()) {
                continue;
            }
            List<SpanRef> labelSpans = page.text().overlapping(range.get());
            if (labelSpans.isEmpty()) {
                continue;
            }
            Box labelBox = unionBox(labelSpans);
            DetectionRef nearest = null;
            BigDecimal nearestSquared = null;
            for (DetectionRef checkbox : checkboxes) {
                BigDecimal distanceSquared = edgeDistanceSquared(labelBox, checkbox.box());
                if (nearest == null || distanceSquared.compareTo(nearestSquared) < 0) {
                    nearest = checkbox;
                    nearestSquared = distanceSquared;
                }
            }
            if (nearestSquared.compareTo(capSquared) > 0) {
                continue; // even the nearest checkbox is out of reach: option stays unmapped
            }
            mapped.add(new MappedOption(option, labelSpans, nearest));
        }
        List<MappedOption> checked =
                mapped.stream()
                        .filter(candidate -> isMarked(candidate.checkbox(), page))
                        .toList();
        if (checked.size() != 1) {
            return Optional.empty();
        }
        MappedOption winner = checked.get(0);
        String value = winner.option().value();
        ConfidenceBreakdown confidence =
                new ConfidenceBreakdown(
                        winner.checkbox().confidence(),
                        BigDecimal.valueOf(spec.strength()),
                        BigDecimal.ONE);
        return Optional.of(
                new FieldOutcome(
                        field,
                        spec.method(),
                        spec.strength(),
                        page.page().pageId(),
                        value,
                        value,
                        new NormalizedValue(value, null, null, BigDecimal.ONE),
                        List.of(
                                new EvidenceRef(
                                        null,
                                        winner.checkbox().elementId(),
                                        winner.checkbox().box(),
                                        winner.checkbox().confidence())),
                        spanEvidence(winner.labelSpans(), null),
                        confidence));
    }

    /**
     * The glyphs preparer software PRINTS inside a box to mark it — text in the page's own
     * layer, not ink the pixel detector measures. Single characters only: a word that merely
     * starts with an X is a word.
     */
    private static final Set<String> MARK_GLYPHS = Set.of("X", "x", "✓", "✔", "✗", "✘", "☒");

    /**
     * A box is marked when the detector read it as checked OR a printed mark glyph's center lies
     * inside it. Measured on a real 2023 Form 1040 (2026-09-14): the filing-status mark is a
     * 6 pt "X" span inside a 13 pt drawn box, and the detector's inner-60% fill measured 0.14
     * against its 0.15 threshold — the page says the box is marked in its own text layer, and
     * reading that is not a guess. A glyph inside NO box marks nothing; the D6 rule that exactly
     * one option may be marked is applied after this test, so two marks still fail the rung.
     */
    private static boolean isMarked(DetectionRef checkbox, PreparedPage page) {
        if (Boolean.TRUE.equals(checkbox.checked())) {
            return true;
        }
        for (SpanRef span : page.page().spans()) {
            if (MARK_GLYPHS.contains(span.text()) && containsCenter(checkbox.box(), span.box())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCenter(Box outer, Box inner) {
        BigDecimal cx = inner.x().add(half(inner.width()));
        BigDecimal cy = inner.y().add(half(inner.height()));
        return cx.compareTo(outer.x()) >= 0
                && cx.compareTo(outer.x().add(outer.width())) <= 0
                && cy.compareTo(outer.y()) >= 0
                && cy.compareTo(outer.y().add(outer.height())) <= 0;
    }

    /** Union box over spans — the label's overall footprint. */
    private static Box unionBox(List<SpanRef> spans) {
        BigDecimal minX = null;
        BigDecimal minY = null;
        BigDecimal maxRight = null;
        BigDecimal maxBottom = null;
        for (SpanRef span : spans) {
            BigDecimal right = span.box().x().add(span.box().width());
            BigDecimal bottom = span.box().y().add(span.box().height());
            minX = minX == null ? span.box().x() : minX.min(span.box().x());
            minY = minY == null ? span.box().y() : minY.min(span.box().y());
            maxRight = maxRight == null ? right : maxRight.max(right);
            maxBottom = maxBottom == null ? bottom : maxBottom.max(bottom);
        }
        return new Box(minX, minY, maxRight.subtract(minX), maxBottom.subtract(minY));
    }

    /**
     * Squared distance from the detection box's CENTER to the label box's nearest edge (zero
     * when the center lies inside the label box). Compared against the squared cap so the
     * arithmetic stays exact BigDecimal end to end — no square root anywhere.
     */
    private static BigDecimal edgeDistanceSquared(Box label, Box detection) {
        BigDecimal two = new BigDecimal(2);
        BigDecimal centerX = detection.x().add(detection.width().divide(two));
        BigDecimal centerY = detection.y().add(detection.height().divide(two));
        BigDecimal dx = axisGap(label.x(), label.x().add(label.width()), centerX);
        BigDecimal dy = axisGap(label.y(), label.y().add(label.height()), centerY);
        return dx.multiply(dx).add(dy.multiply(dy));
    }

    /** Distance from a coordinate to the closed interval [min, max] along one axis. */
    private static BigDecimal axisGap(BigDecimal min, BigDecimal max, BigDecimal center) {
        if (center.compareTo(min) < 0) {
            return min.subtract(center);
        }
        if (center.compareTo(max) > 0) {
            return center.subtract(max);
        }
        return BigDecimal.ZERO;
    }

    // ── SIGNATURE_PRESENCE ───────────────────────────────────────────────────

    /**
     * Region label found → the answer is ALWAYS one of SIGNED/UNSIGNED (absence is an answer,
     * D5): SIGNED when any SIGNATURE detection overlaps the window grown from the label box,
     * else UNSIGNED — found, with label evidence only: the ONE outcome whose value evidence is
     * legally empty (see FieldOutcome). Region label NOT found → the rung fails and the ladder
     * decides, usually MISSING: "could not find the signature block" must stay distinguishable
     * from "unsigned", because the riskier state must never hide inside the other.
     */
    private Optional<FieldOutcome> signaturePresence(
            FieldSpec field, ExtractorSpec spec, PreparedPage page) {
        Optional<SpanText.Range> labelRange =
                page.text().findFirst(SpanText.labelPattern(spec.region().label()));
        if (labelRange.isEmpty()) {
            return Optional.empty();
        }
        List<SpanRef> labelSpans = page.text().overlapping(labelRange.get());
        if (labelSpans.isEmpty()) {
            return Optional.empty();
        }
        Box window = expand(unionBox(labelSpans), spec.region().windowPt());
        Optional<DetectionRef> signature =
                page.page().detections().stream()
                        .filter(detection -> detection.type() == LayoutElementType.SIGNATURE)
                        .filter(detection -> intersects(window, detection.box()))
                        .findFirst();
        List<EvidenceRef> labelEvidence = spanEvidence(labelSpans, null);
        if (signature.isPresent()) {
            DetectionRef detection = signature.get();
            return Optional.of(
                    signatureOutcome(
                            field,
                            spec,
                            page,
                            "SIGNED",
                            List.of(
                                    new EvidenceRef(
                                            null,
                                            detection.elementId(),
                                            detection.box(),
                                            detection.confidence())),
                            labelEvidence,
                            detection.confidence()));
        }
        // UNSIGNED: the absence claim is exactly as good as the region localization — the
        // label spans' weakest confidence rides the spanConfidence slot.
        BigDecimal labelConfidence =
                labelSpans.stream()
                        .map(SpanRef::confidence)
                        .min(Comparator.naturalOrder())
                        .orElse(BigDecimal.ZERO);
        return Optional.of(
                signatureOutcome(
                        field, spec, page, "UNSIGNED", List.of(), labelEvidence, labelConfidence));
    }

    private static FieldOutcome signatureOutcome(
            FieldSpec field,
            ExtractorSpec spec,
            PreparedPage page,
            String value,
            List<EvidenceRef> valueEvidence,
            List<EvidenceRef> labelEvidence,
            BigDecimal spanConfidence) {
        return new FieldOutcome(
                field,
                spec.method(),
                spec.strength(),
                page.page().pageId(),
                value,
                value,
                new NormalizedValue(value, null, null, BigDecimal.ONE),
                valueEvidence,
                labelEvidence,
                new ConfidenceBreakdown(
                        spanConfidence, BigDecimal.valueOf(spec.strength()), BigDecimal.ONE));
    }

    /**
     * The search region: the label box grown per side. Canonical space is top-left origin with
     * y increasing DOWNWARD, so "above" grows toward smaller y and "below" toward larger y.
     */
    private static Box expand(Box label, Window window) {
        BigDecimal left = BigDecimal.valueOf(window.left());
        BigDecimal right = BigDecimal.valueOf(window.right());
        BigDecimal above = BigDecimal.valueOf(window.above());
        BigDecimal below = BigDecimal.valueOf(window.below());
        return new Box(
                label.x().subtract(left),
                label.y().subtract(above),
                label.width().add(left).add(right),
                label.height().add(above).add(below));
    }

    /** Positive-area overlap — a box merely touching the window's edge is not inside it. */
    private static boolean intersects(Box a, Box b) {
        return a.x().add(a.width()).compareTo(b.x()) > 0
                && b.x().add(b.width()).compareTo(a.x()) > 0
                && a.y().add(a.height()).compareTo(b.y()) > 0
                && b.y().add(b.height()).compareTo(a.y()) > 0;
    }

    // ── shared capture tail ──────────────────────────────────────────────────

    /**
     * Runs the value pattern over the scope, takes match #occurrence, normalizes, and builds the
     * outcome. Any miss — no such occurrence, or a normalization failure — fails the rung.
     */
    private Optional<FieldOutcome> capture(
            FieldSpec field,
            ExtractorSpec spec,
            PreparedPage page,
            SpanText scope,
            List<EvidenceRef> labelEvidence,
            UUID valueElementId,
            String groupKey) {
        Optional<SpanText.Range> range =
                scope.findOccurrence(
                        SpanText.valuePattern(spec.value().pattern()), spec.value().occurrence());
        if (range.isEmpty()) {
            return Optional.empty();
        }
        String displayed = scope.text().substring(range.get().start(), range.get().end());
        List<SpanRef> valueSpans = scope.overlapping(range.get());
        if (valueSpans.isEmpty()) {
            // No span underlies this match — a zero-width match, or one landing entirely on a
            // joining space. A value that traces to no box is not a value (invariant 1, Phase 5
            // acceptance criterion 2): fail the rung so a later rung, or MISSING, decides.
            return Optional.empty();
        }

        if (spec.value().joinNextLine() != null
                && (spec.method() == ExtractionMethod.ANCHOR_LABEL || spec.method() == ExtractionMethod.REGEX)
                && groupKey == null) {
            Optional<Joined> joined = joinNextLine(page, valueSpans, spec.value().joinNextLine());
            if (joined.isPresent()) {
                displayed = displayed + " " + joined.get().text();
                List<SpanRef> both = new ArrayList<>(valueSpans);
                both.addAll(joined.get().spans());
                valueSpans = List.copyOf(both);
            }
        }

        Optional<NormalizedValue> normalized = Normalizers.normalize(field.normalizer(), displayed);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal spanConfidence =
                valueSpans.stream()
                        .map(SpanRef::confidence)
                        .min(Comparator.naturalOrder())
                        .orElse(BigDecimal.ZERO);
        ConfidenceBreakdown confidence =
                new ConfidenceBreakdown(
                        spanConfidence,
                        BigDecimal.valueOf(spec.strength()),
                        normalized.get().certainty());

        return Optional.of(
                new FieldOutcome(
                        field,
                        spec.method(),
                        spec.strength(),
                        page.page().pageId(),
                        displayed,
                        displayed,
                        normalized.get(),
                        spanEvidence(valueSpans, valueElementId),
                        labelEvidence,
                        confidence,
                        groupKey));
    }

    private record Joined(String text, List<SpanRef> spans) {}

    /**
     * The visual line after the one holding the value's last span, tested WHOLE against the join
     * pattern; the WHOLE MATCH is appended, never just group 1. One line, never more (bank
     * statement field rules, task 4, spec §5) — a third co-holder printed two lines down is not
     * this rung's problem, so {@code joinNextLine} is looked for once and never recurses onto its
     * own result.
     *
     * <p>The whole match, not group 1, is what gets appended: a joint holder's line prints as
     * {@code OR DIEGO R LOPEZ}, and the "OR" is part of the printed value the field must hold and
     * cite evidence for, exactly as displayed on the page — a schema author who wants the name
     * alone without its connector authors a pattern that does not capture the connector at all
     * (e.g. a lookbehind), rather than relying on this method to drop what it matched. A group in
     * the join pattern, when present, is free to exist for the author's own readability or a
     * future caller; this method never consults it.
     *
     * <p>Matched the same way every other value rung matches a document line: the AUTHORED
     * pattern folded through {@link TextFold} (via {@link SpanText#valuePattern}) against the
     * DOCUMENT line folded the same way — exactly {@link #matches(LabelSpec, String)}'s approach
     * for a detected grid's header cells, never a bare {@link Pattern#compile} against the page's
     * raw characters, or a schema authored with a typographic apostrophe would silently fail to
     * join a line the page printed with a curly one. {@link TextFold#fold(String)} is guaranteed
     * 1 char in, 1 char out (see its Javadoc), so a {@link Matcher} offset found against the
     * folded copy indexes {@code next.text()} identically; the displayed text is sliced from, and
     * the evidence spans resolved against, {@code next.text()} — the document's OWN characters —
     * never the folded copy, exactly as {@link #capture} itself never reports a folded value.
     */
    private static Optional<Joined> joinNextLine(PreparedPage page, List<SpanRef> valueSpans, String join) {
        int index = lineIndexContaining(page.lines(), valueSpans.get(valueSpans.size() - 1));
        if (index < 0 || index + 1 >= page.lines().size()) {
            return Optional.empty();
        }
        SpanText next = SpanText.of(page.lines().get(index + 1));
        Matcher matcher =
                SpanText.valuePattern(join)
                        .matcher(BoundedCharSequence.over(TextFold.fold(next.text())));
        if (!matcher.find()) {
            return Optional.empty();
        }
        int start = matcher.start();
        int end = matcher.end();
        SpanText.Range range = new SpanText.Range(start, end);
        return Optional.of(new Joined(next.text().substring(start, end), next.overlapping(range)));
    }

    // ── ROW groups ───────────────────────────────────────────────────────────

    /**
     * A ROW group's SHARED frame: the ONE page the whole group reads, and the y bounds every one
     * of its fields counts rows from. {@code rowsTop} is the group's row ORIGIN — the ordinal in
     * {@code groupKey} is a count of visual lines from it, so two fields counting from different
     * origins would key the same printed row differently.
     *
     * <p>{@code regionTop} rides along because each field still locates its OWN column header
     * inside the region; only WHERE THE ROWS BEGIN is shared.
     *
     * <p>A LABELED group ({@code rowLabels} declared) shares the PAGE and the region bounds but
     * not {@code rowsTop}: the shared origin exists solely to keep counted ordinals aligned
     * across fields, and a labeled group joins on the PRINTED letter instead — so each of its
     * fields reads the rows under its OWN caption line. That is what lets a table the form
     * prints as TWO sub-tables (Schedule E Parts II/III letter their rows twice) join name row
     * A to money row A: one shared origin can only ever serve one of the two bands, and serving
     * the lower one is exactly how caption continuations and money rows became phantom "name"
     * occurrences on a real document.
     */
    private record RowFrame(
            PreparedPage page,
            BigDecimal regionTop,
            BigDecimal rowsTop,
            BigDecimal regionBottom) {}

    /**
     * Every ROW group's frame, resolved once per {@code extract} call.
     *
     * <p><b>What makes two fields one group</b>: their REGION — the printed anchors that open and
     * close the table. The region IS the table, and a table has one row numbering; {@code
     * maxRows} is a safety cap on how far each field walks it, not an identity (and {@code
     * ExtractionSchemaLoader} requires the fields over one region to agree on it, so the two
     * readings cannot diverge in a shipped schema). Deliberately explicit rather than resting on
     * {@link GroupSpec}'s record equality: a reader must be able to see what "the same group"
     * means without deducing it from a generated {@code equals}.
     *
     * <p>A group with no frame is absent from the map, and every one of its fields answers
     * MISSING with a null key.
     */
    private static Map<GroupRegionSpec, RowFrame> rowFrames(
            SchemaDefinition schema, List<PreparedPage> pages) {
        Map<GroupRegionSpec, List<FieldSpec>> members = new LinkedHashMap<>();
        for (FieldSpec field : schema.fields()) {
            GroupSpec group = field.group();
            if (group != null && group.kind() == GroupKind.ROW && group.region() != null) {
                members.computeIfAbsent(group.region(), region -> new ArrayList<>()).add(field);
            }
        }
        Map<GroupRegionSpec, RowFrame> frames = new LinkedHashMap<>();
        for (Map.Entry<GroupRegionSpec, List<FieldSpec>> group : members.entrySet()) {
            rowFrame(group.getKey(), group.getValue(), pages)
                    .ifPresent(frame -> frames.put(group.getKey(), frame));
        }
        return Collections.unmodifiableMap(frames);
    }

    /**
     * The group's page, and with it its origin: pages in document order, and the FIRST page that
     * bounds the region, lets at least one member name its column, and prints at least one row
     * wins the whole group.
     *
     * <p>The group agrees on a PAGE for the same reason it agrees on an origin. Letting each
     * field pick its own page produces the identical corruption by another route: a name column
     * that failed to OCR on page 1 would send that field to page 2's table, and {@code #01} would
     * then join one document page's first entity to another's. A field that cannot read the
     * group's page fails SAFE — one missing occurrence, null key — and this also keeps the
     * pre-existing guarantee that EXACTLY ONE page contributes rows, which is the only reason two
     * pages cannot both emit {@code "01"} and collide under {@code extracted_field_one_current}.
     */
    private static Optional<RowFrame> rowFrame(
            GroupRegionSpec region, List<FieldSpec> members, List<PreparedPage> pages) {
        for (PreparedPage page : pages) {
            Optional<RowFrame> frame = rowFrameOn(region, members, page);
            if (frame.isPresent()) {
                return frame;
            }
        }
        return Optional.empty();
    }

    /**
     * One page's frame, or empty when this page does not carry the group's rows.
     *
     * <p>The origin is the BOTTOM of the LOWEST caption line any member of the group resolves
     * inside the region. A real Schedule E Part II prints the {@code (a)}–{@code (e)} entity
     * captions on one line and a SECOND caption band {@code (f)}–{@code (j)} below it over the
     * dollar columns, because the real captions do not fit on one baseline. Counting from the
     * lowest of them is what makes the two bands agree: taking the highest instead would leave
     * the second band inside the row range and every field would open with an occurrence for a
     * row nobody printed.
     */
    private static Optional<RowFrame> rowFrameOn(
            GroupRegionSpec region, List<FieldSpec> members, PreparedPage page) {
        Optional<List<SpanRef>> startLine = firstLineWith(page, region.start(), null);
        if (startLine.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal regionTop = bottomOf(startLine.get());
        Optional<List<SpanRef>> endLine = firstLineWith(page, region.end(), regionTop);
        if (endLine.isEmpty()) {
            // No end anchor is the runaway the design's risk table names: this page does not
            // bound the region, so the group looks at the next one.
            return Optional.empty();
        }
        BigDecimal regionBottom = topOf(endLine.get());

        BigDecimal rowsTop = null;
        for (FieldSpec member : members) {
            Optional<List<SpanRef>> caption = captionLine(member, page, regionTop, regionBottom);
            if (caption.isPresent()) {
                BigDecimal bottom = bottomOf(caption.get());
                rowsTop = rowsTop == null ? bottom : rowsTop.max(bottom);
            }
        }
        if (rowsTop == null) {
            // No member can name a column here, so nothing on this page is addressable as a row.
            return Optional.empty();
        }
        for (List<SpanRef> line : page.lines()) {
            BigDecimal lineTop = topOf(line);
            if (lineTop.compareTo(rowsTop) >= 0 && lineTop.compareTo(regionBottom) < 0) {
                return Optional.of(new RowFrame(page, regionTop, rowsTop, regionBottom));
            }
        }
        return Optional.empty();
    }

    /**
     * The caption line this field's ladder will ACTUALLY use on this page: the first ROW_CELL
     * rung, in spec order, whose column header resolves inside the region — the same walk {@link
     * #rowCell} makes, so the origin is measured from the lines the group really reads and never
     * from a fallback rung that will not run.
     */
    private static Optional<List<SpanRef>> captionLine(
            FieldSpec field, PreparedPage page, BigDecimal regionTop, BigDecimal regionBottom) {
        for (ExtractorSpec spec : field.extractors()) {
            if (spec.method() != ExtractionMethod.ROW_CELL || spec.columnHeader() == null) {
                continue;
            }
            Optional<List<SpanRef>> line = firstLineWith(page, spec.columnHeader(), regionTop);
            if (line.isPresent() && topOf(line.get()).compareTo(regionBottom) < 0) {
                return line;
            }
        }
        return Optional.empty();
    }

    /**
     * A row group's occurrences are DISCOVERED, not declared: the schema names a bounded region
     * and a cap, and the page decides how many rows there are. So — unlike a column group,
     * which always emits one occurrence per declared key — a region that cannot be located at
     * all yields ONE missing occurrence with a null key. There is no ordinal to name when
     * there are no rows, and inventing {@code maxRows} empty ones would bury the reviewer.
     *
     * <p>The same answer covers the field that cannot name its column on the frame's page. A
     * null key says "this field read no rows"; it can never be mistaken for a row of the table,
     * which is exactly what an ordinal counted from some other origin would be.
     */
    private List<FieldOutcome> extractRowGroup(FieldSpec field, RowFrame frame) {
        if (frame == null) {
            return List.of(FieldOutcome.missing(field));
        }
        if (field.group().rowLabels() != null) {
            return extractLabeledRowGroup(field, frame);
        }
        for (ExtractorSpec spec : field.extractors()) {
            if (spec.method() != ExtractionMethod.ROW_CELL) {
                // Only ROW_CELL can address a row. Any other rung would answer every row
                // with the same value — N entities reported as N copies of one.
                continue;
            }
            List<FieldOutcome> rows = rowCell(field, spec, frame);
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        return List.of(FieldOutcome.missing(field));
    }

    /**
     * A LABELED row group's field: one occurrence per DECLARED letter, keyed by the letter the
     * form preprints at each row's own left edge.
     *
     * <p>Unlike the counted scheme, the declared letters are a KEY SPACE the schema states — the
     * same footing as a COLUMN group's keys — so when the region is located but no rung can
     * address the table (column caption unreadable, gutter unreadable), every declared letter is
     * owed a MISSING occurrence (design D5): "row C was unreadable" is reviewable, silence is
     * not. Only a region that cannot be located at all answers with the single null-keyed
     * MISSING, exactly as an unlabeled group does.
     */
    private List<FieldOutcome> extractLabeledRowGroup(FieldSpec field, RowFrame frame) {
        for (ExtractorSpec spec : field.extractors()) {
            if (spec.method() != ExtractionMethod.ROW_CELL) {
                continue;
            }
            Optional<List<FieldOutcome>> rows = labeledRows(field, spec, frame);
            if (rows.isPresent()) {
                return rows.get();
            }
        }
        return field.group().rowLabels().stream()
                .map(label -> FieldOutcome.missing(field, label))
                .toList();
    }

    /**
     * The ROW_CELL rung: one occurrence per ROW of a bounded region, read from the column its
     * printed header names.
     *
     * <p>The region and the row origin come from the group's {@link RowFrame}, never from this
     * field's own caption line: {@code groupKey} is the key consumers JOIN on, so an ordinal
     * counted from one field's caption band and another's would pair one entity's name with the
     * next entity's money — confident, evidence-backed, and wrong about whose money it is. What
     * stays per field is the COLUMN: {@code spec.columnHeader()} is located inside the region and
     * its anchor spans' union box is the column's x-extent.
     *
     * <p>A row is a visual line inside those bounds, and its ordinal — 1-based, among the lines
     * that EXIST, zero-padded to two digits — is the group key (design D2, plan CONTRACTS). A
     * blank row on the form produces no visual line at all, so a region with fewer rows than
     * {@code maxRows} cannot fabricate empties; that contract falls out of the geometry rather
     * than being special-cased. A row whose cell is empty, or whose cell text does not yield a
     * normalizable value, is a MISSING occurrence for THAT ROW only — never a defaulted zero,
     * and never a reason to drop the other rows.
     *
     * <p>Membership in the column is {@link #sharesCell} — narrower-of-the-two containment
     * against the header anchor's extent, NOT {@code LABEL_BELOW}'s {@link #ownsSpan}: a
     * column's data is routinely wider than a short header like "(a) Name", and the column has
     * no derivable right boundary the way a caption row gives a cell one (the divergence is
     * recorded on {@link #sharesCell} itself). Deliberately independent of ruling detection
     * (design D2, inherited from Spec 4): entity tables on real returns are often unruled, and
     * the worker skips rulings on rotated pages.
     *
     * <p>Empty result ⇒ the rung FAILED (no cap, no column, or no rows at all) and the ladder
     * moves on. It never means "try another page": the group has already chosen one.
     */
    private List<FieldOutcome> rowCell(FieldSpec field, ExtractorSpec spec, RowFrame frame) {
        GroupSpec group = field.group();
        if (group.maxRows() == null || spec.columnHeader() == null) {
            return List.of();
        }
        PreparedPage page = frame.page();
        BigDecimal regionBottom = frame.regionBottom();
        Optional<List<SpanRef>> headerLine =
                firstLineWith(page, spec.columnHeader(), frame.regionTop());
        if (headerLine.isEmpty() || topOf(headerLine.get()).compareTo(regionBottom) >= 0) {
            return List.of();
        }
        List<SpanRef> headerAnchor = anchorOn(headerLine.get(), spec.columnHeader());
        if (headerAnchor.isEmpty()) {
            return List.of();
        }
        Box headerBox = unionBox(headerAnchor);
        // The GROUP's origin, not this header line's bottom edge — see RowFrame.
        BigDecimal rowsTop = frame.rowsTop();
        BigDecimal overlapFraction =
                spec.cellOverlap() == null
                        ? DEFAULT_CELL_OVERLAP
                        : BigDecimal.valueOf(spec.cellOverlap());
        List<EvidenceRef> labelEvidence = spanEvidence(headerAnchor, null);

        List<FieldOutcome> rows = new ArrayList<>();
        for (List<SpanRef> line : page.lines()) {
            if (rows.size() >= group.maxRows()) {
                // A HARD stop, not a hint: a malformed page whose rows never end must not emit
                // hundreds of persisted occurrences (the design's risk table).
                break;
            }
            BigDecimal lineTop = topOf(line);
            if (lineTop.compareTo(rowsTop) < 0 || lineTop.compareTo(regionBottom) >= 0) {
                continue;
            }
            String key = rowKey(rows.size() + 1);
            List<SpanRef> cell = new ArrayList<>();
            for (SpanRef span : line) {
                if (sharesCell(headerBox, span.box(), overlapFraction)) {
                    cell.add(span);
                }
            }
            if (cell.isEmpty()) {
                rows.add(FieldOutcome.missing(field, key));
                continue;
            }
            rows.add(
                    capture(
                                    field,
                                    spec,
                                    page,
                                    SpanText.of(List.copyOf(cell)),
                                    labelEvidence,
                                    null,
                                    key)
                            .orElseGet(() -> FieldOutcome.missing(field, key)));
        }
        return List.copyOf(rows);
    }

    /**
     * The LABELED counterpart of {@link #rowCell}: one occurrence per DECLARED letter, each row
     * keyed by the letter printed at its own left edge — never by a count.
     *
     * <p>Three geometric facts anchor a label, so a stray capital inside an entity name can
     * never become a row key:
     *
     * <ul>
     *   <li>the label is the LEFTMOST span of its visual line — the gutter is the table's first
     *       column and nothing prints left of it;
     *   <li>its trimmed text is EXACTLY one declared letter (through the {@link TextFold} seam,
     *       like every other authored string); and
     *   <li>every accepted label must occupy ONE x-column — pairwise-overlapping x-extents,
     *       relative geometry with no fixed offsets, so a differently scaled scan keys the same
     *       rows. A candidate off that column means the gutter cannot be trusted, and the rung
     *       fails CLOSED rather than adjudicating which letter is real: adjudication is how a
     *       confident wrong join would be manufactured.
     * </ul>
     *
     * <p>Rows are read from under the FIELD'S OWN caption line ({@code bottomOf(headerLine)}),
     * not the group's shared origin: the letter replaces the ordinal as the cross-field join, so
     * the alignment the shared origin enforced is carried by the printed key instead, and each
     * field may read its own sub-table. The FIRST line carrying a letter wins it — the field's
     * own sub-table is the one its caption opens, and a later sub-table merely repeats the
     * letters; its rows never overlap this field's column anyway, but first-wins makes the
     * choice a rule rather than a coincidence. The gutter letter itself is EXCLUDED from every
     * cell: it is the row's key, not its data, and the name pattern would happily match a bare
     * capital.
     *
     * <p>Empty ⇒ the rung cannot address the table here (no caption, no letters, or a breached
     * gutter) and the ladder decides; {@link #extractLabeledRowGroup} then answers one MISSING
     * occurrence PER DECLARED LETTER. A letter declared but not printed is likewise MISSING
     * under its own key — there is no positional fallback, because a fallback would silently
     * revert to the counted misalignment this scheme exists to remove.
     */
    private Optional<List<FieldOutcome>> labeledRows(
            FieldSpec field, ExtractorSpec spec, RowFrame frame) {
        List<String> labels = field.group().rowLabels();
        if (spec.columnHeader() == null) {
            return Optional.empty();
        }
        PreparedPage page = frame.page();
        BigDecimal regionBottom = frame.regionBottom();
        Optional<List<SpanRef>> headerLine =
                firstLineWith(page, spec.columnHeader(), frame.regionTop());
        if (headerLine.isEmpty() || topOf(headerLine.get()).compareTo(regionBottom) >= 0) {
            return Optional.empty();
        }
        List<SpanRef> headerAnchor = anchorOn(headerLine.get(), spec.columnHeader());
        if (headerAnchor.isEmpty()) {
            return Optional.empty();
        }
        Box headerBox = unionBox(headerAnchor);
        BigDecimal rowsTop = bottomOf(headerLine.get());
        BigDecimal overlapFraction =
                spec.cellOverlap() == null
                        ? DEFAULT_CELL_OVERLAP
                        : BigDecimal.valueOf(spec.cellOverlap());

        Map<String, List<SpanRef>> rowByLabel = new LinkedHashMap<>();
        Map<String, SpanRef> letterByLabel = new LinkedHashMap<>();
        List<SpanRef> gutter = new ArrayList<>();
        for (List<SpanRef> line : page.lines()) {
            BigDecimal lineTop = topOf(line);
            if (lineTop.compareTo(rowsTop) < 0 || lineTop.compareTo(regionBottom) >= 0) {
                continue;
            }
            SpanRef leftmost = line.get(0);
            String label = declaredLabel(labels, leftmost);
            if (label == null) {
                // Caption continuations, totals lines, the other band's caption row: lines the
                // form prints inside the region without lettering. Not rows — skipped, never
                // counted, never keyed.
                continue;
            }
            for (SpanRef accepted : gutter) {
                if (!horizontalOverlap(accepted.box(), leftmost.box())) {
                    return Optional.empty();
                }
            }
            gutter.add(leftmost);
            if (!rowByLabel.containsKey(label)) {
                rowByLabel.put(label, line);
                letterByLabel.put(label, leftmost);
            }
        }
        if (rowByLabel.isEmpty()) {
            return Optional.empty();
        }

        List<EvidenceRef> labelEvidence = spanEvidence(headerAnchor, null);
        List<FieldOutcome> outcomes = new ArrayList<>();
        for (String label : labels) {
            List<SpanRef> line = rowByLabel.get(label);
            if (line == null) {
                outcomes.add(FieldOutcome.missing(field, label));
                continue;
            }
            SpanRef letter = letterByLabel.get(label);
            List<SpanRef> cell = new ArrayList<>();
            for (SpanRef span : line) {
                if (span == letter) {
                    continue;
                }
                if (sharesCell(headerBox, span.box(), overlapFraction)) {
                    cell.add(span);
                }
            }
            if (cell.isEmpty()) {
                outcomes.add(FieldOutcome.missing(field, label));
                continue;
            }
            outcomes.add(
                    capture(
                                    field,
                                    spec,
                                    page,
                                    SpanText.of(List.copyOf(cell)),
                                    labelEvidence,
                                    null,
                                    label)
                            .orElseGet(() -> FieldOutcome.missing(field, label)));
        }
        return Optional.of(List.copyOf(outcomes));
    }

    /** The declared label this span prints, or null. Fold-equal on the trimmed text, EXACT. */
    private static String declaredLabel(List<String> labels, SpanRef span) {
        String folded = TextFold.fold(span.text().trim());
        for (String label : labels) {
            if (TextFold.fold(label).equals(folded)) {
                return label;
            }
        }
        return null;
    }

    /** Positive-width x-extent overlap — touching edges are not one printed column. */
    private static boolean horizontalOverlap(Box a, Box b) {
        return a.x().add(a.width()).compareTo(b.x()) > 0
                && b.x().add(b.width()).compareTo(a.x()) > 0;
    }

    /**
     * The row's key: its 1-based ordinal, ZERO-PADDED TO TWO DIGITS (plan CONTRACTS). {@code
     * group_key} is {@code text}, so every ordering that touches it — the repository's
     * {@code OrderBy…GroupKeyAsc}, the export's {@code Comparator.naturalOrder()} and the
     * current-row unique index — sorts LEXICALLY. Unpadded, {@code "10"} precedes {@code "2"}
     * and a ten-entity Schedule E would present its rows 1, 10, 2, 3…: every value correct,
     * the reviewer's first read scrambled, and a consumer taking "the first entity" taking the
     * wrong one. Two digits suffice because {@code maxRows} is required and bounded; a group
     * needing three is a schema-authoring error, and {@code %02d} widens rather than truncating
     * so the ordinal is never silently wrong — only mis-sorted.
     *
     * <p>{@link java.util.Locale#ROOT} explicitly: a locale-sensitive format could render the
     * ordinal in a non-ASCII digit set, and the key is a database value, not display text.
     */
    private static String rowKey(int ordinal) {
        return String.format(java.util.Locale.ROOT, "%02d", ordinal);
    }

    /**
     * The first visual line whose top edge is at or below {@code belowY} (null = anywhere) and
     * whose own joined text carries the anchor. Per LINE, not per page: a region anchor that
     * matched across two lines would give a bound nobody printed.
     */
    private static Optional<List<SpanRef>> firstLineWith(
            PreparedPage page, LabelSpec anchor, BigDecimal belowY) {
        for (List<SpanRef> line : page.lines()) {
            if (belowY != null && topOf(line).compareTo(belowY) < 0) {
                continue;
            }
            if (!anchorOn(line, anchor).isEmpty()) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    /** The spans of one line underlying the anchor's FIRST match on that line. */
    private static List<SpanRef> anchorOn(List<SpanRef> line, LabelSpec anchor) {
        SpanText text = SpanText.of(line);
        return text.findFirst(SpanText.labelPattern(anchor))
                .map(text::overlapping)
                .orElse(List.of());
    }

    /** A line's top edge: the minimum y over its spans. Lines are never empty. */
    private static BigDecimal topOf(List<SpanRef> line) {
        BigDecimal top = null;
        for (SpanRef span : line) {
            top = top == null ? span.box().y() : top.min(span.box().y());
        }
        return top;
    }

    /** A line's bottom edge: the maximum {@code y + height} over its spans. */
    private static BigDecimal bottomOf(List<SpanRef> line) {
        BigDecimal bottom = null;
        for (SpanRef span : line) {
            BigDecimal edge = span.box().y().add(span.box().height());
            bottom = bottom == null ? edge : bottom.max(edge);
        }
        return bottom;
    }

    private static List<EvidenceRef> spanEvidence(List<SpanRef> spans, UUID layoutElementId) {
        return spans.stream()
                .map(span -> new EvidenceRef(span.id(), layoutElementId, span.box(), span.confidence()))
                .toList();
    }
}
