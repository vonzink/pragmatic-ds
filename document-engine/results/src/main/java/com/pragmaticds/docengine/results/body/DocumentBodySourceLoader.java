package com.pragmaticds.docengine.results.body;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.parsing.domain.LayoutElement;
import com.pragmaticds.docengine.parsing.domain.LayoutElementSpan;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.LayoutElementRepository;
import com.pragmaticds.docengine.parsing.repo.LayoutElementSpanRepository;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourceElement;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourceSpan;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourcePage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one logical document's persisted L2 rows into {@link DocumentBodySource} — the adapter half
 * of the seam whose other half, {@link DocumentBodyComposer}, is a pure function.
 *
 * <p>Everything that can fail on the shape of stored data lives here: tenancy, absent rows,
 * attribute JSON that a detector wrote and a schema never constrained. The composer therefore only
 * ever fails on its own ordering logic, which is the one failure a unit test can actually pin.
 *
 * <h2>Why this does not sort</h2>
 *
 * <p>It would be easy to hand the composer a pre-sorted list and delete its sort. That would move
 * the document's reading order into an untested place. {@code
 * LayoutElementRepository.findByPageIdInOrderByPageIdAscOrdinalAsc} orders by page id — a UUID —
 * and a body built on that arrival order is shuffled prose whose every citation box is
 * individually correct: it reads plausibly and survives a smoke test. So this class populates
 * {@link SourcePage#documentPageOrdinal} from {@code logical_document_page.ordinal} and {@link
 * SourceElement#ordinal} from the element row, and leaves the sort where a database-free test can
 * shuffle the input and watch it come back right.
 *
 * <h2>Why absence is {@link Optional}, not an exception</h2>
 *
 * <p>The house 404 is three-way indistinguishable — foreign, tombstoned and nonexistent answer
 * identically. Two of those three are decided here (the org guard on the document) and the third
 * (the package tombstone) is decided by the endpoint, which owns HTTP. Returning empty lets the
 * endpoint raise ONE {@code DomainException} for all three from one place; a throw from here would
 * put a second, distant throw site in the lockstep, and the day one of them gains a params map the
 * two 404s stop being indistinguishable.
 *
 * <p>A document with no member pages is present, not absent: it loads as a body with no pages.
 * Structural absence is a legitimate answer to "what is in this document"; nonexistence is not.
 *
 * <h2>Cost</h2>
 *
 * <p>Six repository calls per document regardless of page count — document, membership, pages,
 * elements, element→span links, span confidences — of which the last four fan their id lists out
 * over {@link #ID_BATCH}-sized {@code IN} lists, because a several-hundred-page document carries
 * more element and span ids than Postgres will accept as bind parameters in one statement. No call
 * is made per page: the per-page span sweep L2 can afford for one page is a query per page here.
 */
@Component
public class DocumentBodySourceLoader {

    /**
     * Ids per {@code IN} list. Postgres caps a statement at 65535 bind parameters and a document
     * with hundreds of pages can carry six figures of span ids, so the fan-out is a correctness
     * requirement rather than a tuning knob — but it is well under the cap, so the fan-out itself
     * never becomes the reason a document fails to load.
     */
    private static final int ID_BATCH = 1000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository memberships;
    private final PageRepository pages;
    private final LayoutElementRepository elements;
    private final LayoutElementSpanRepository elementSpans;
    private final TextSpanRepository spans;

    public DocumentBodySourceLoader(
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository memberships,
            PageRepository pages,
            LayoutElementRepository elements,
            LayoutElementSpanRepository elementSpans,
            TextSpanRepository spans) {
        this.documents = documents;
        this.memberships = memberships;
        this.pages = pages;
        this.elements = elements;
        this.elementSpans = elementSpans;
        this.spans = spans;
    }

    /**
     * @return the document's rows, or empty when no document with this id belongs to the calling
     *     org — the caller decides what that means over HTTP
     */
    @Transactional(readOnly = true)
    public Optional<DocumentBodySource> load(UUID logicalDocumentId) {
        UUID orgId = TenantContext.require();
        Optional<LogicalDocument> found = documents.findByIdAndOrgId(logicalDocumentId, orgId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        LogicalDocument document = found.get();

        List<LogicalDocumentPage> memberRows =
                memberships.findByLogicalDocumentIdOrderByOrdinal(logicalDocumentId);
        requireTenant(orgId, memberRows, LogicalDocumentPage::getOrgId);
        if (memberRows.isEmpty()) {
            // An UNKNOWN-typed or unsplit document is the headline case for a body, not an error;
            // so is a document that lost its pages. Neither is a reason to refuse the read.
            return Optional.of(
                    new DocumentBodySource(
                            logicalDocumentId, document.getDocumentTypeCode(), List.of()));
        }

        List<UUID> pageIds = memberRows.stream().map(LogicalDocumentPage::getPageId).toList();
        Map<UUID, Page> pageById =
                index(batched(pageIds, ids -> pages.findByIdInAndOrgId(ids, orgId)), Page::getId);
        for (UUID pageId : pageIds) {
            if (!pageById.containsKey(pageId)) {
                // A member page that is absent or foreign cannot be cited, and dropping it would
                // silently shorten the document — the failure shape this whole feature avoids.
                throw inconsistent("member page not readable in this org: " + pageId);
            }
        }

        List<LayoutElement> elementRows =
                batched(pageIds, elements::findByPageIdInOrderByPageIdAscOrdinalAsc);
        requireTenant(orgId, elementRows, LayoutElement::getOrgId);

        Map<UUID, List<Long>> spanIdsByElement = spanIdsByElement(orgId, elementRows);
        Map<Long, TextSpan> spanById = spanById(orgId, spanIdsByElement);

        Map<UUID, List<SourceElement>> elementsByPage = new HashMap<>();
        for (LayoutElement element : elementRows) {
            elementsByPage
                    .computeIfAbsent(element.getPageId(), page -> new ArrayList<>())
                    .add(sourceElement(element, spanIdsByElement, spanById));
        }

        List<SourcePage> sourcePages = new ArrayList<>(memberRows.size());
        for (LogicalDocumentPage member : memberRows) {
            Page page = pageById.get(member.getPageId());
            sourcePages.add(
                    new SourcePage(
                            page.getId(),
                            page.getPackagePageIndex(),
                            member.getOrdinal(),
                            elementsByPage.getOrDefault(page.getId(), List.of())));
        }
        return Optional.of(
                new DocumentBodySource(
                        logicalDocumentId, document.getDocumentTypeCode(), sourcePages));
    }

    // ── row → seam member ───────────────────────────────────────────────────

    private static SourceElement sourceElement(
            LayoutElement element,
            Map<UUID, List<Long>> spanIdsByElement,
            Map<Long, TextSpan> spanById) {
        List<SourceSpan> spans = spans(spanIdsByElement.getOrDefault(element.getId(), List.of()), spanById);
        JsonNode attributes = attributes(element);
        LayoutElementType type = element.getElementType();
        boolean cell = type == LayoutElementType.TABLE_CELL;
        boolean table = type == LayoutElementType.TABLE;
        return new SourceElement(
                element.getId(),
                element.getParentElementId(),
                type,
                element.getOrdinal(),
                new BodyBox(
                        element.getX(), element.getY(), element.getWidth(), element.getHeight()),
                element.getText(),
                element.getConfidence(),
                textConfidence(spans),
                spans,
                cell ? intAttribute(attributes, "row") : null,
                cell ? intAttribute(attributes, "col") : null,
                table ? intAttribute(attributes, "rows") : null,
                table ? intAttribute(attributes, "cols") : null,
                table ? booleanAttribute(attributes, "ruled") : null,
                type == LayoutElementType.CHECKBOX
                        ? booleanAttribute(attributes, "checked")
                        : null);
    }

    /**
     * Attributes are read only where the detector that writes them says they mean something — cell
     * addresses on a {@code TABLE_CELL}, geometry on a {@code TABLE}, state on a {@code CHECKBOX} —
     * exactly as {@code PageStructureController} gates them. Reading {@code rows} off whatever
     * element happens to carry the key would let one detector's private attribute arrive at the
     * composer wearing the table contract's name.
     */
    private static Integer intAttribute(JsonNode attributes, String name) {
        return attributes.hasNonNull(name) ? attributes.get(name).asInt() : null;
    }

    /**
     * Absent decodes to null, never to false. L2 can default {@code ruled} to false because its
     * consumer is drawing a table; a body's consumer is answering a question, and "the detector
     * could not tell" is a materially different fact from "the box is unticked".
     */
    private static Boolean booleanAttribute(JsonNode attributes, String name) {
        return attributes.hasNonNull(name) ? attributes.get(name).asBoolean() : null;
    }

    private static JsonNode attributes(LayoutElement element) {
        if (element.getAttributes() == null) {
            return JSON.missingNode();
        }
        try {
            return JSON.readTree(element.getAttributes());
        } catch (IOException e) {
            // jsonb was valid when Postgres accepted it; failure here is corruption, not input.
            // Ids only — never an element's text.
            throw new UncheckedIOException(
                    "unreadable layout_element attributes: " + element.getId(), e);
        }
    }

    /**
     * The MINIMUM over member spans, byte-for-byte the rule {@code
     * PageStructureController.textConfidence} applies, so the one axis L2 and the body share stays
     * the same number for the same element. Null when the element owns no span with a known
     * confidence: 1.0 would claim certainty about text that does not exist.
     */
    private static BigDecimal textConfidence(List<SourceSpan> spans) {
        BigDecimal min = null;
        for (SourceSpan span : spans) {
            BigDecimal confidence = span.confidence();
            if (confidence != null && (min == null || confidence.compareTo(min) < 0)) {
                min = confidence;
            }
        }
        return min;
    }

    /**
     * Member spans in LINK order, each with the geometry a column analysis needs.
     *
     * <p>A link whose {@code text_span} row did not come back still yields a span — id only, no
     * geometry, no text. Dropping it would shorten the element's citation, and a span with no
     * geometry is exactly the input that makes {@link DocumentBodyComposer} decline to split a
     * column rather than guess at one.
     */
    private static List<SourceSpan> spans(List<Long> spanIds, Map<Long, TextSpan> spanById) {
        List<SourceSpan> spans = new ArrayList<>(spanIds.size());
        for (Long spanId : spanIds) {
            TextSpan span = spanById.get(spanId);
            spans.add(
                    span == null
                            ? new SourceSpan(spanId, null, null, "", null)
                            : new SourceSpan(
                                    span.getId(),
                                    span.getX(),
                                    span.getWidth(),
                                    span.getText(),
                                    span.getConfidence()));
        }
        return spans;
    }

    // ── the two id-keyed sweeps ─────────────────────────────────────────────

    /**
     * Span ids per element in LINK order — {@code layout_element_span.ordinal}, which belongs to
     * the link rather than to the span and need not agree with page reading order. Batching is by
     * element id, so every one of an element's links lands in the same chunk and link order
     * survives the fan-out.
     */
    private Map<UUID, List<Long>> spanIdsByElement(UUID orgId, List<LayoutElement> elementRows) {
        List<UUID> ids = elementRows.stream().map(LayoutElement::getId).toList();
        List<LayoutElementSpan> links =
                batched(
                        ids,
                        elementSpans
                                ::findByLayoutElementIdInOrderByLayoutElementIdAscOrdinalAsc);
        requireTenant(orgId, links, LayoutElementSpan::getOrgId);
        Map<UUID, List<Long>> byElement = new HashMap<>();
        for (LayoutElementSpan link : links) {
            byElement
                    .computeIfAbsent(link.getLayoutElementId(), element -> new ArrayList<>())
                    .add(link.getTextSpanId());
        }
        return byElement;
    }

    /**
     * Only the spans some element actually claims — whole rows now, not just their confidence,
     * because the body needs each span's x-extent. L2 sweeps a whole page's spans because it holds
     * one page; a document-wide loader that did the same would read every span in the document to
     * use the linked minority, and would need a query per page to do it.
     */
    private Map<Long, TextSpan> spanById(UUID orgId, Map<UUID, List<Long>> spanIdsByElement) {
        Set<Long> ids = new LinkedHashSet<>();
        spanIdsByElement.values().forEach(ids::addAll);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<TextSpan> rows = batched(List.copyOf(ids), spans::findAllById);
        requireTenant(orgId, rows, TextSpan::getOrgId);
        Map<Long, TextSpan> bySpan = new HashMap<>();
        for (TextSpan span : rows) {
            bySpan.put(span.getId(), span);
        }
        return bySpan;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private static <I, R> List<R> batched(List<I> ids, Function<List<I>, List<R>> query) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() <= ID_BATCH) {
            return query.apply(ids);
        }
        List<R> all = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += ID_BATCH) {
            all.addAll(query.apply(ids.subList(from, Math.min(from + ID_BATCH, ids.size()))));
        }
        return all;
    }

    private static <T> Map<UUID, T> index(Collection<T> rows, Function<T, UUID> key) {
        Map<UUID, T> byId = new HashMap<>();
        rows.forEach(row -> byId.put(key.apply(row), row));
        return byId;
    }

    /**
     * A belt over {@code @TenantId}'s braces. The batch reads here are derived queries, which
     * Hibernate does filter — but {@code findAllById} is a primary-key load, which it does NOT
     * (the warning on {@code TenantScopedEntity} is about exactly this), and a loader that assumed
     * uniformly-filtered reads would leak the day one method is swapped for another.
     */
    private static <T> void requireTenant(
            UUID orgId, Collection<T> rows, Function<T, UUID> tenant) {
        if (rows.stream().anyMatch(row -> !orgId.equals(tenant.apply(row)))) {
            throw inconsistent("row from another org reached the document body loader");
        }
    }

    private static IllegalStateException inconsistent(String detail) {
        return new IllegalStateException(detail);
    }
}
