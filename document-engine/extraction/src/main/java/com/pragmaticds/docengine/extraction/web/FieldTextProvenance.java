package com.pragmaticds.docengine.extraction.web;

import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The read-side answer to "was this value read, or was it recognised?" — the span-level companion
 * to {@link FieldGroupKinds}.
 *
 * <p><b>Why SPAN granularity and not page.</b> {@code page.text_layer} already says
 * {@code NATIVE}/{@code SCANNED}/{@code MIXED}/{@code NONE}, and for a whole-page verdict that is
 * the right fact. It is the wrong fact for a FIELD: on a {@code MIXED} page — a native-text form
 * carrying an OCR'd handwritten entry or a scanned stamp — the page verdict is {@code MIXED} for
 * every field on it, including the ones read entirely from the text layer. {@code text_span.source}
 * is per span, so the question can be answered for the spans THIS value actually came from.
 *
 * <p><b>Why DERIVED and not persisted.</b> Same argument {@link FieldGroupKinds} makes. Every
 * {@code field_evidence} row already cites its {@code text_span}, and a span is append-only
 * ({@code TextSpan} maps every column {@code updatable = false}) — so the fact is already stored,
 * once, in the table that owns it. A copy on the field row could only ever be right by accident of
 * a backfill, and it would need a migration to add a column that restates a join.
 *
 * <p><b>VALUE evidence only.</b> A field's provenance is the provenance of the spans its VALUE came
 * from. LABEL spans are how the value was FOUND, not what it says: a native-text caption beside an
 * OCR'd number would otherwise launder that number into {@code MIXED} — or worse, a
 * majority rule would call it {@code NATIVE} — and the reviewer would stop looking at exactly the
 * digits that need looking at. Callers pass VALUE span ids and nothing else.
 *
 * <p>Tenancy: span ids arrive from {@code field_evidence} rows already org-guarded, and the lookup
 * still applies its own {@code org_id} predicate, so a stray id cannot read another tenant's span.
 */
@Component
public class FieldTextProvenance {

    /**
     * Bind-parameter ceiling per statement. The export path spans a whole package — tens of
     * thousands of VALUE spans on a large one — and Postgres refuses a statement with more than
     * 65535 parameters, so the IN-list is chunked rather than trusted to stay small.
     */
    private static final int CHUNK = 1000;

    private final JdbcTemplate jdbc;

    public FieldTextProvenance(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One lookup covering every span the caller's evidence rows cite. Batched deliberately, for the
     * reason {@link FieldGroupKinds#forSchemas} is: a per-field query on the export path would be
     * one SELECT per occurrence.
     */
    public Lookup forSpans(Collection<Long> spanIds) {
        Set<Long> distinct = new LinkedHashSet<>(spanIds);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return new Lookup(Map.of());
        }
        UUID orgId = TenantContext.require();
        List<Long> all = new ArrayList<>(distinct);
        Map<Long, TextProvenanceView> bySpan = new HashMap<>();
        for (int from = 0; from < all.size(); from += CHUNK) {
            List<Long> chunk = all.subList(from, Math.min(from + CHUNK, all.size()));
            String placeholders = chunk.stream().map(id -> "?").collect(Collectors.joining(", "));
            Object[] arguments = new Object[chunk.size() + 1];
            int index = 0;
            for (Long id : chunk) {
                arguments[index++] = id;
            }
            arguments[index] = orgId;
            for (Map<String, Object> row :
                    jdbc.queryForList(
                            "SELECT id, source, ocr_engine FROM text_span"
                                    + " WHERE id IN ("
                                    + placeholders
                                    + ") AND org_id = ?",
                            arguments)) {
                bySpan.put(
                        ((Number) row.get("id")).longValue(),
                        new TextProvenanceView(
                                (String) row.get("source"), (String) row.get("ocr_engine")));
            }
        }
        return new Lookup(Map.copyOf(bySpan));
    }

    /** The resolved per-span origins for one read. */
    public record Lookup(Map<Long, TextProvenanceView> bySpan) {

        /**
         * The provenance of ONE value, folded from the spans it was captured from.
         *
         * <p>The fold is deliberately not a vote. If any contributing span was recognised and any
         * other was read, the answer is {@link TextProvenanceView#MIXED} and the engine is still
         * named — because a value that is half-guessed is not a native value, and picking the
         * majority would hide the half that needs a human. A value with no visible span at all
         * answers {@link TextProvenanceView#UNKNOWN}: that is what a MISSING occurrence looks like,
         * what an element-only capture (checkbox state, signature presence) looks like, and what a
         * row whose span was purged out from under it looks like. Reporting {@code NATIVE} for any
         * of those would be an invention, which is the same floor {@link FieldGroupKinds} takes.
         */
        public TextProvenanceView of(Collection<Long> spanIds) {
            boolean sawNative = false;
            boolean sawOcr = false;
            Set<String> engines = new TreeSet<>();
            for (Long spanId : spanIds) {
                TextProvenanceView span = bySpan.get(spanId);
                if (span == null) {
                    continue;
                }
                if (TextProvenanceView.OCR.equals(span.source())) {
                    sawOcr = true;
                    if (span.ocrEngine() != null) {
                        engines.add(span.ocrEngine());
                    }
                } else if (TextProvenanceView.NATIVE.equals(span.source())) {
                    sawNative = true;
                }
                // Any other value contributes NOTHING rather than being folded into one of the two
                // arms. text_span_source_check admits only NATIVE and OCR, so this is unreachable
                // today; if that constraint ever widens, an unrecognised source must show up as an
                // honest UNKNOWN rather than be silently reported as native text.
            }
            if (!sawNative && !sawOcr) {
                return TextProvenanceView.UNKNOWN_PROVENANCE;
            }
            String source =
                    sawNative && sawOcr
                            ? TextProvenanceView.MIXED
                            : sawOcr ? TextProvenanceView.OCR : TextProvenanceView.NATIVE;
            return new TextProvenanceView(
                    source, engines.isEmpty() ? null : String.join("+", engines));
        }
    }
}
