package com.pragmaticds.rag.lab.analyze;

import com.pragmaticds.rag.lab.engine.EngineEnvelopeParser;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.engine.IncomeEnvelopeCompatibility;
import com.pragmaticds.rag.lab.engine.LabContractException;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Renders one immutable engine result into the deterministic machine-fact block a parsed Lab run
 * sends instead of document media.
 *
 * <p>This is the <em>last</em> gate before the prompt, and it behaves like one. The strict parser
 * already refuses raw-text, storage, filename, review, and correction members at any depth, but a
 * field's {@code normalized.json} arm is a free-form tree, so the same vocabulary is re-checked
 * here before any of it is written. A forbidden member fails the render rather than being quietly
 * dropped: silently omitting it would hide a contract violation the operator needs to see.
 *
 * <p>Determinism is a hard requirement, not a nicety — the rendered text is hashed into the
 * analyzer's {@code prompt_sha256}. So every list is walked in envelope order, every number is
 * written with {@link BigDecimal#toPlainString()} (exact scale preserved, never an exponent and
 * never an IEEE-754 double), and nothing is sourced from an unordered collection.
 *
 * <p>Pages and sources are referenced by short positional handles ({@code P1}, {@code S1}) and
 * logical documents by a stable {@code doc-<ordinal>} <em>citation handle</em>. The handle set is
 * returned alongside the text so the caller can reject a {@code BORROWER_DOC} citation naming a
 * document this run never rendered.
 */
@Component
@ConditionalOnExpression("${ragbrain.lab.enabled:false} or ${ragbrain.instances.enabled:false}")
public class ParsedDocumentPromptRenderer {

    private static final String MISSING_VALUE_LINE =
            "value=MISSING (no value was extracted; do not infer one)";

    private static final String REJECTED_VALUE_LINE = "value=(rejected by reviewer)";

    private static final String REDACTED_VALUE_LINE = "value=<redacted:sensitive>";

    /** The rendered block plus the citation handles it authorized. */
    public record Rendered(String text, List<DocumentHandle> handles) {

        public Rendered {
            Objects.requireNonNull(text, "text");
            handles = List.copyOf(handles);
        }

        /** The handle strings a {@code BORROWER_DOC} citation may name, in render order. */
        public Set<String> handleIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (DocumentHandle handle : handles) {
                ids.add(handle.handle());
            }
            return java.util.Collections.unmodifiableSet(ids);
        }
    }

    /** One analyzable logical document and the citation handle the prompt gave it. */
    public record DocumentHandle(
            String handle, UUID documentId, String documentTypeCode, int ordinal) {

        public DocumentHandle {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(documentTypeCode, "documentTypeCode");
        }
    }

    /** The stable citation handle for a logical document's ordinal. */
    public static String handleFor(int documentOrdinal) {
        return "doc-" + documentOrdinal;
    }

    /**
     * Renders the analyzable half of one envelope.
     *
     * @param decision the compatibility policy's decision for this exact envelope; documents the
     *     policy ignored are not rendered as facts and receive no citation handle, but their
     *     warnings are still printed so the model cannot mistake an ignored document for an
     *     absent one
     * @throws LabContractException payload-free, when a free-form value carries a forbidden member
     *     or an evidence span names a page outside this envelope
     */
    public Rendered render(EngineResultEnvelope envelope,
                           IncomeEnvelopeCompatibility.Decision decision) {
        Objects.requireNonNull(decision, "decision");
        return render(envelope, decision.warnings().stream()
                .map(ParsedDocumentPromptRenderer::generalize).toList());
    }

    /**
     * The generalized entry point: any instance's compatibility decision, not only Income's.
     *
     * <p>Rendering never depended on which instance judged the envelope — only on the warnings the
     * judgement produced — so the two overloads share one implementation and produce byte-identical
     * text for the same warnings. That matters because the rendered block is hashed into the
     * analyzer's {@code prompt_sha256}.
     */
    public Rendered render(EngineResultEnvelope envelope,
                           ParsedDataCompatibilityService.CompatibilityDecision decision) {
        Objects.requireNonNull(decision, "decision");
        return render(envelope, decision.warnings());
    }

    private Rendered render(EngineResultEnvelope envelope,
                            List<ParsedDataCompatibilityService.Warning> warnings) {
        Objects.requireNonNull(envelope, "envelope");

        Map<UUID, String> pageHandles = new LinkedHashMap<>();
        for (int i = 0; i < envelope.pages().size(); i++) {
            pageHandles.put(envelope.pages().get(i).id(), "P" + (i + 1));
        }
        Map<UUID, String> sourceHandles = new LinkedHashMap<>();
        for (int i = 0; i < envelope.sources().size(); i++) {
            sourceHandles.put(envelope.sources().get(i).id(), "S" + (i + 1));
        }
        Set<Integer> ignoredOrdinals = ignoredDocumentOrdinals(warnings);

        List<DocumentHandle> handles = new ArrayList<>();
        for (EngineResultEnvelope.LogicalDocument document : envelope.documents()) {
            if (!ignoredOrdinals.contains(document.ordinal())) {
                handles.add(new DocumentHandle(handleFor(document.ordinal()), document.id(),
                        document.documentTypeCode(), document.ordinal()));
            }
        }

        StringBuilder out = new StringBuilder(4096);
        appendHeader(out, envelope);
        appendSources(out, envelope, sourceHandles);
        appendPages(out, envelope, pageHandles, sourceHandles);
        appendDocuments(out, envelope, pageHandles, ignoredOrdinals);
        appendUnassignedPages(out, envelope, pageHandles);
        appendWarnings(out, warnings);
        appendCitationHandles(out, handles);
        return new Rendered(out.toString(), handles);
    }

    // ------------------------------------------------------------------ sections

    private static void appendHeader(StringBuilder out, EngineResultEnvelope envelope) {
        EngineResultEnvelope.Generation generation = envelope.generation();
        out.append("PARSED DOCUMENT FACTS (Pragmatic DS Document Engine immutable result)\n")
                .append("envelopeVersion=").append(envelope.envelopeVersion())
                .append(" canonicalization=").append(envelope.canonicalizationVersion())
                .append('\n')
                .append("package=").append(envelope.packageId())
                .append(" packageRevision=").append(generation.packageRevision())
                .append(" parseGeneration=").append(generation.parseGeneration())
                .append(" processingJob=").append(generation.processingJobId())
                .append('\n')
                .append("sourceSetSha256=").append(generation.sourceSetSha256())
                .append(" reuseEligibility=").append(generation.reuseEligibility())
                .append('\n')
                .append("No document image, page image, or page text is attached to this request."
                        + " The machine-extracted facts below are the only borrower evidence"
                        + " available; never infer, estimate, or restate a value shown as MISSING."
                        + " A value marked reviewState=CORRECTED is a named reviewer's correction"
                        + " and takes precedence over anything you might infer; a field marked"
                        + " reviewState=REJECTED must be treated as absent.")
                .append('\n');
    }

    private static void appendSources(StringBuilder out, EngineResultEnvelope envelope,
                                      Map<UUID, String> sourceHandles) {
        out.append("\nSOURCES (").append(envelope.sources().size()).append(")\n");
        for (EngineResultEnvelope.SourceFile source : envelope.sources()) {
            out.append("  ").append(sourceHandles.get(source.id()))
                    .append(" contentSha256=").append(source.contentSha256())
                    .append(" sizeBytes=").append(source.sizeBytes())
                    .append(" contentType=").append(source.contentType())
                    .append('\n');
        }
    }

    private static void appendPages(StringBuilder out, EngineResultEnvelope envelope,
                                    Map<UUID, String> pageHandles,
                                    Map<UUID, String> sourceHandles) {
        out.append("\nPAGES (").append(envelope.pages().size()).append(")\n");
        List<String> rotationNotes = new ArrayList<>();
        for (EngineResultEnvelope.EnginePage page : envelope.pages()) {
            String handle = pageHandles.get(page.id());
            out.append("  ").append(handle)
                    .append(" source=").append(sourceHandles.getOrDefault(page.sourceFileId(), "?"))
                    .append(" sourcePageIndex=").append(page.sourcePageIndex())
                    .append(" widthPt=").append(plain(page.widthPt()))
                    .append(" heightPt=").append(plain(page.heightPt()))
                    .append(" rotation=").append(page.rotation())
                    .append(" textLayer=").append(page.textLayer())
                    .append(" blank=").append(page.blank())
                    .append(" duplicate=").append(page.duplicate());
            EngineResultEnvelope.PageClassification classification = page.classification();
            if (classification == null) {
                out.append(" classification=none");
            } else {
                out.append(" classification=").append(classification.documentTypeCode())
                        .append(" confidence=").append(plain(classification.confidence()))
                        .append(" method=").append(classification.method());
                appendClassificationProvenance(out, classification.evidence());
            }
            out.append('\n');
            if (page.rotation() != 0) {
                rotationNotes.add("  NOTE: page " + handle + " is rotated " + page.rotation()
                        + " degrees; its evidence boxes are stated in unrotated page space.\n");
            }
        }
        rotationNotes.forEach(out::append);
    }

    private void appendDocuments(StringBuilder out, EngineResultEnvelope envelope,
                                 Map<UUID, String> pageHandles, Set<Integer> ignoredOrdinals) {
        List<EngineResultEnvelope.LogicalDocument> rendered = envelope.documents().stream()
                .filter(document -> !ignoredOrdinals.contains(document.ordinal()))
                .toList();
        out.append("\nDOCUMENTS (").append(rendered.size()).append(")\n");
        for (EngineResultEnvelope.LogicalDocument document : rendered) {
            out.append("  ").append(handleFor(document.ordinal()))
                    .append(" type=").append(document.documentTypeCode())
                    .append(" engineDocumentId=").append(document.id())
                    .append(" pages=[");
            for (int i = 0; i < document.pageIds().size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(requirePage(pageHandles, document.pageIds().get(i)));
            }
            out.append("]\n");
            for (EngineResultEnvelope.FieldOccurrence field : document.fields()) {
                appendField(out, field, pageHandles);
            }
        }
    }

    private void appendField(StringBuilder out, EngineResultEnvelope.FieldOccurrence field,
                             Map<UUID, String> pageHandles) {
        out.append("    name=").append(field.name())
                .append(" group=").append(field.groupKey() == null ? "-" : field.groupKey())
                .append(" status=").append(field.status())
                .append(" dataType=").append(field.dataType())
                .append(" confidence=").append(plain(field.confidence()))
                .append(" method=").append(field.method())
                .append(" extractorVersion=").append(field.extractorVersion())
                .append(" validationStatus=").append(field.validationStatus())
                .append(" sensitive=").append(field.sensitive())
                .append(" reviewState=").append(field.reviewState().name())
                .append('\n');

        out.append("      ").append(valueLine(field)).append('\n');

        if (field.evidence().isEmpty()) {
            out.append("      evidence: none\n");
            return;
        }
        for (EngineResultEnvelope.EvidenceSpan span : field.evidence()) {
            out.append("      evidence: page=").append(requirePage(pageHandles, span.pageId()))
                    .append(" role=").append(span.role())
                    .append(" ordinal=").append(span.ordinal())
                    .append(" box=[x=").append(plain(span.box().x()))
                    .append(",y=").append(plain(span.box().y()))
                    .append(",w=").append(plain(span.box().width()))
                    .append(",h=").append(plain(span.box().height()))
                    .append(']');
            if (span.layoutElementId() != null) {
                out.append(" layoutElementId=").append(span.layoutElementId());
            }
            if (span.textSpanId() != null) {
                out.append(" textSpanId=").append(span.textSpanId());
            }
            out.append('\n');
        }
    }

    private String valueLine(EngineResultEnvelope.FieldOccurrence field) {
        if (field.status() == EngineResultEnvelope.FieldStatus.MISSING) {
            return field.reviewState() == EngineResultEnvelope.ReviewState.REJECTED
                    ? REJECTED_VALUE_LINE
                    : MISSING_VALUE_LINE;
        }
        // A sensitive occurrence still appears — its presence and confidence are analyzable facts
        // — but no borrower identifier is written into a prompt that income analysis never needs.
        if (field.sensitive()) {
            return REDACTED_VALUE_LINE;
        }
        StringBuilder line = new StringBuilder(96);
        if (field.displayedText() != null) {
            line.append("displayedText=").append(quote(field.displayedText()));
        }
        if (field.rawValue() != null) {
            space(line).append("rawValue=").append(quote(field.rawValue()));
        }
        EngineResultEnvelope.NormalizedValue normalized = field.normalized();
        if (normalized != null) {
            if (normalized.text() != null) {
                space(line).append("normalized.text=").append(quote(normalized.text()));
            }
            if (normalized.number() != null) {
                space(line).append("normalized.number=").append(plain(normalized.number()));
            }
            if (normalized.date() != null) {
                space(line).append("normalized.date=").append(normalized.date());
            }
            if (normalized.json() != null) {
                space(line).append("normalized.json=").append(json(normalized.json()));
            }
        }
        return line.isEmpty() ? "value=(none)" : line.toString();
    }

    private static void appendUnassignedPages(StringBuilder out, EngineResultEnvelope envelope,
                                              Map<UUID, String> pageHandles) {
        out.append("\nUNASSIGNED PAGES: ");
        if (envelope.unassignedPageIds().isEmpty()) {
            out.append("none\n");
            return;
        }
        for (int i = 0; i < envelope.unassignedPageIds().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(requirePage(pageHandles, envelope.unassignedPageIds().get(i)));
        }
        out.append('\n');
    }

    private static void appendWarnings(
            StringBuilder out, List<ParsedDataCompatibilityService.Warning> warnings) {
        if (warnings.isEmpty()) {
            out.append("\nPARSE WARNINGS: none\n");
            return;
        }
        out.append("\nPARSE WARNINGS (").append(warnings.size()).append(")\n");
        for (ParsedDataCompatibilityService.Warning warning : warnings) {
            out.append("  ").append(warning.code())
                    .append(' ').append(handleFor(warning.documentOrdinal()))
                    .append(" type=").append(warning.documentTypeCode());
            if (warning.fieldName() != null) {
                out.append(" name=").append(warning.fieldName())
                        .append(" group=")
                        .append(warning.groupKey() == null ? "-" : warning.groupKey());
            }
            out.append('\n');
        }
    }

    private static void appendCitationHandles(StringBuilder out, List<DocumentHandle> handles) {
        out.append("\nCITATION HANDLES\n");
        for (DocumentHandle handle : handles) {
            out.append("  ").append(handle.handle()).append(" -> ")
                    .append(handle.documentTypeCode()).append('\n');
        }
        out.append("Cite every borrower value with a BORROWER_DOC citation whose documentId is"
                + " exactly one of these handles; never invent another handle.\n");
    }

    // ------------------------------------------------------------------ helpers

    /** Ordinals the compatibility policy declared unsupported, so they are never rendered. */
    /** Income's warning vocabulary expressed in the generalized one; the names are identical. */
    private static ParsedDataCompatibilityService.Warning generalize(
            IncomeEnvelopeCompatibility.Warning warning) {
        ParsedDataCompatibilityService.WarningCode code = switch (warning.code()) {
            case FIELD_REVIEW_REQUIRED ->
                    ParsedDataCompatibilityService.WarningCode.FIELD_REVIEW_REQUIRED;
            case FIELD_MISSING -> ParsedDataCompatibilityService.WarningCode.FIELD_MISSING;
            case UNSUPPORTED_DOCUMENT_IGNORED ->
                    ParsedDataCompatibilityService.WarningCode.UNSUPPORTED_DOCUMENT_IGNORED;
        };
        return new ParsedDataCompatibilityService.Warning(code, warning.documentOrdinal(),
                warning.documentTypeCode(), warning.fieldName(), warning.groupKey());
    }

    private static Set<Integer> ignoredDocumentOrdinals(
            List<ParsedDataCompatibilityService.Warning> warnings) {
        Set<Integer> ignored = new HashSet<>();
        for (ParsedDataCompatibilityService.Warning warning : warnings) {
            if (warning.code()
                    == ParsedDataCompatibilityService.WarningCode.UNSUPPORTED_DOCUMENT_IGNORED) {
                ignored.add(warning.documentOrdinal());
            }
        }
        return ignored;
    }

    private static String requirePage(Map<UUID, String> pageHandles, UUID pageId) {
        String handle = pageHandles.get(pageId);
        if (handle == null) {
            throw new LabContractException(LabContractException.Code.PAGE_MEMBERSHIP_VIOLATION);
        }
        return handle;
    }

    private static StringBuilder space(StringBuilder line) {
        if (!line.isEmpty()) {
            line.append(' ');
        }
        return line;
    }

    /** Exact decimal text: scale preserved, never an exponent, never a double. */
    private static String plain(BigDecimal value) {
        return value.toPlainString();
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }

    /**
     * Writes a free-form normalized tree in insertion order, re-checking the engine's forbidden
     * member vocabulary at every depth. The parser already enforces it over the received bytes;
     * enforcing it again at the prompt boundary means one missed depth in either place cannot put
     * a storage key, filename, or review note in front of a model.
     */
    private String json(Object value) {
        StringBuilder out = new StringBuilder(64);
        writeJson(value, out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private void writeJson(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String text -> out.append(quote(text));
            case Boolean flag -> out.append(flag.booleanValue());
            case BigDecimal number -> out.append(number.toPlainString());
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<String, Object>) map).entrySet()) {
                    String name = String.valueOf(entry.getKey());
                    if (EngineEnvelopeParser.FORBIDDEN_MEMBERS
                            .contains(name.toLowerCase(Locale.ROOT))) {
                        throw new LabContractException(
                                LabContractException.Code.ENVELOPE_FORBIDDEN_MEMBER);
                    }
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append(quote(name)).append(':');
                    writeJson(entry.getValue(), out);
                }
                out.append('}');
            }
            case List<?> items -> {
                out.append('[');
                for (int i = 0; i < items.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    writeJson(items.get(i), out);
                }
                out.append(']');
            }
            default -> throw new LabContractException(
                    LabContractException.Code.ENVELOPE_VALUE_MALFORMED);
        }
    }
    /**
     * A model-typed page (engine #64) is weaker provenance than a rule-anchor match, and the model
     * must be told so; a rule-anchor page that also qualified for other types names them so the
     * model does not treat the page's type as exclusive.
     */
    private static void appendClassificationProvenance(
            StringBuilder out, EngineResultEnvelope.ClassificationEvidence evidence) {
        switch (evidence) {
            case EngineResultEnvelope.RuleAnchorEvidence ruleAnchor -> {
                if (!ruleAnchor.coQualifyingTypes().isEmpty()) {
                    out.append(" coQualifyingTypes=[")
                            .append(String.join(",", ruleAnchor.coQualifyingTypes()))
                            .append(']');
                }
            }
            case EngineResultEnvelope.LlmEvidence llm ->
                    out.append(" model=").append(llm.model())
                            .append(" promptVersion=").append(llm.promptVersion())
                            .append(" (model-typed page: weaker provenance than a rule-anchor match)");
        }
    }
}
