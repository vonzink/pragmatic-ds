package com.pragmaticds.docengine.parsing.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.parsing.domain.LayoutElement;
import com.pragmaticds.docengine.parsing.domain.LayoutElementSpan;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.LayoutElementRepository;
import com.pragmaticds.docengine.parsing.repo.LayoutElementSpanRepository;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.ParsingPackageRefRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.parsing.web.PageStructureView.BlockView;
import com.pragmaticds.docengine.parsing.web.PageStructureView.BoxView;
import com.pragmaticds.docengine.parsing.web.PageStructureView.CellView;
import com.pragmaticds.docengine.parsing.web.PageStructureView.MarkView;
import com.pragmaticds.docengine.parsing.web.PageStructureView.TableView;
import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.platform.web.EntityTags;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * L2 — the page's geometric structure, served (full-capture design D1/D7/D10/D11/D12, §6.2).
 *
 * <p>This is a read model over {@code layout_element} exactly as the worker persisted it. Nothing
 * is detected here (design D7): the TABLE → TABLE_ROW → TABLE_CELL trees, the blocks and the pixel
 * marks all exist already, under RLS, with {@code detector}/{@code detector_version} per row, and
 * the extraction engine already cites their cells as field evidence. Re-detecting at read time
 * would produce a second answer that must then be reconciled with the one L3 evidence points at —
 * so the one thing this controller adds is a shape, never a fact.
 *
 * <h2>The walk this makes navigable (design D3)</h2>
 *
 * <p>{@code evidence[].layoutElementId} on {@code /fields} and in the canonical envelope is a
 * {@code layout_element.id}; every structure id in this response is too. {@code ?containsSpan=} is
 * the UPWARD resolver — span id to the structures whose member spans include it; {@code ?element=}
 * on {@code /spans} is the DOWNWARD one. Together the L3 → L2 → L1 walk closes with zero contract
 * change on any existing surface, including the frozen envelope.
 *
 * <h2>ADMIN-only, same boundary as L1</h2>
 *
 * <p>A structure response carries the page's text — cell contents, block text — merely re-grouped,
 * so it sits behind the same role gate and the same value-free audit discipline as {@code /spans}
 * and the raw engine-result bytes. The matcher must precede the broad {@code GET /v1/**} rule in
 * {@code SecurityConfig}; behind it, READONLY reads everything. And the same one-representation
 * caveat carries over: the page RASTER stays READONLY-reachable and unaudited, so this gate
 * protects borrower TEXT, not borrower content in general.
 *
 * <h2>The pin (design D4, as corrected in P1)</h2>
 *
 * <p>The validator names the page, the parse ({@code content_hash}) and the structure contract:
 * {@code "<pageId>.<contentHash>-l2/DOCENGINE-L2-1/1.0.0"}. The page id is load-bearing —
 * {@code content_hash} is the duplicate-page digest and two pages in one package are EXPECTED to
 * collide — and the contract version is how a consumer sees a shape change instead of absorbing it.
 * The tag is issued, and honoured, ONLY for the whole-page window: a filtered window is a different
 * representation and answers 200 with no validator at all. What the pin deliberately does NOT
 * catch: a layout re-parse that changed detector output while reproducing identical span text and
 * boxes keeps the same {@code content_hash} — the digest covers L1, not L2. The contract documents
 * {@code body.contentHash} as the mid-walk re-parse check, with exactly that caveat.
 */
@RestController
public class PageStructureController {

    /** Structure kinds a caller may narrow to. PAIR arrives with P3's contract bump, not before. */
    private enum StructureKind {
        TABLE,
        BLOCK,
        MARK
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * FORM_FIELD is a block since the worker's pair detector (#63): a label and its figure as
     * one element. Served under {@code kind: BLOCK} so a paired line neither vanishes from the
     * structure nor from the {@code containsSpan} owner walk; the PAIR kind stays refused
     * until the contract bump gives it a shape of its own.
     */
    private static final Set<LayoutElementType> BLOCK_TYPES =
            EnumSet.of(
                    LayoutElementType.PARAGRAPH,
                    LayoutElementType.HEADER,
                    LayoutElementType.FORM_FIELD);
    private static final Set<LayoutElementType> MARK_TYPES =
            EnumSet.of(LayoutElementType.CHECKBOX, LayoutElementType.SIGNATURE);

    private final ParsingPackageRefRepository packages;
    private final PageRepository pages;
    private final LayoutElementRepository elements;
    private final LayoutElementSpanRepository elementSpans;
    private final TextSpanRepository spans;
    private final AuditService audit;

    public PageStructureController(
            ParsingPackageRefRepository packages,
            PageRepository pages,
            LayoutElementRepository elements,
            LayoutElementSpanRepository elementSpans,
            TextSpanRepository spans,
            AuditService audit) {
        this.packages = packages;
        this.pages = pages;
        this.elements = elements;
        this.elementSpans = elementSpans;
        this.spans = spans;
        this.audit = audit;
    }

    /**
     * One page's structure: blocks, tables with {@code (row, col)}-addressed cells, marks.
     *
     * @param containsSpan a {@code text_span.id}; keeps only the structures whose member spans
     *     include it — the walk's upward leg. An unknown id is an empty result, not an error: the
     *     question "which structure owns this span" legitimately has the answer "none".
     * @param kind {@code TABLE}, {@code BLOCK} or {@code MARK}. {@code PAIR} is refused until the
     *     pair detector exists (P3) — accepting the name would promise it does.
     */
    @GetMapping("/v1/pages/{id}/structure")
    public ResponseEntity<PageStructureView> structure(
            @PathVariable UUID id,
            @RequestParam(name = "containsSpan", required = false) String containsSpan,
            @RequestParam(name = "kind", required = false) String kind,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        UUID orgId = TenantContext.require();
        // Verbatim from PageController/PageSpanController: page in this org AND package not
        // tombstoned; foreign, tombstoned and nonexistent are one indistinguishable 404.
        Page page =
                pages.findByIdAndOrgId(id, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(page.getPackageId(), orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        // The validator describes ONE representation — the whole-page window (D4 as corrected).
        boolean wholePage = containsSpan == null && kind == null;
        String etag = wholePage ? validator(page) : null;
        if (etag != null && EntityTags.anyMatch(ifNoneMatch, etag)) {
            // No structures leave the process; nothing to audit as read.
            return headers(ResponseEntity.status(304), etag).build();
        }

        Long spanFilter = containsSpan == null ? null : spanId(containsSpan);
        StructureKind kindFilter = kind == null ? null : kind(kind);

        List<LayoutElement> pageElements =
                elements.findByOrgIdAndPageIdOrderByOrdinal(orgId, page.getId());
        Assembly assembly = assemble(page, pageElements);
        List<BlockView> blocks =
                (kindFilter == null || kindFilter == StructureKind.BLOCK)
                        ? filterBlocks(assembly.blocks(), spanFilter)
                        : List.of();
        List<TableView> tables =
                (kindFilter == null || kindFilter == StructureKind.TABLE)
                        ? filterTables(assembly.tables(), spanFilter)
                        : List.of();
        List<MarkView> marks =
                (kindFilter == null || kindFilter == StructureKind.MARK)
                        ? filterMarks(assembly.marks(), spanFilter)
                        : List.of();

        PageStructureView view =
                new PageStructureView(
                        page.getId(),
                        page.getWidthPt(),
                        page.getHeightPt(),
                        page.getRotation(),
                        page.getContentHash(),
                        PageStructureView.STRUCTURE_CONTRACT,
                        detectorCoverage(page, !pageElements.isEmpty()),
                        blocks,
                        tables,
                        marks);
        recordRead(page, view, spanFilter, kindFilter);
        return headers(ResponseEntity.ok(), etag).body(view);
    }

    // ── assembly: the persisted tree, reshaped and nothing more ─────────────

    private record Assembly(List<BlockView> blocks, List<TableView> tables, List<MarkView> marks) {}

    private Assembly assemble(Page page, List<LayoutElement> pageElements) {
        if (pageElements.isEmpty()) {
            return new Assembly(List.of(), List.of(), List.of());
        }

        Map<UUID, List<Long>> spanIdsByElement = spanIdsByElement(pageElements);
        Map<Long, BigDecimal> confidenceBySpan = confidenceBySpan(page.getId());
        Map<UUID, List<LayoutElement>> childrenByParent = new LinkedHashMap<>();
        for (LayoutElement element : pageElements) {
            if (element.getParentElementId() != null) {
                childrenByParent
                        .computeIfAbsent(element.getParentElementId(), parent -> new ArrayList<>())
                        .add(element);
            }
        }

        List<BlockView> blocks = new ArrayList<>();
        List<TableView> tables = new ArrayList<>();
        List<MarkView> marks = new ArrayList<>();
        for (LayoutElement element : pageElements) {
            if (element.getParentElementId() != null) {
                continue; // rows and cells are reached through their table
            }
            if (BLOCK_TYPES.contains(element.getElementType())) {
                blocks.add(block(element, spanIdsByElement, confidenceBySpan));
            } else if (element.getElementType() == LayoutElementType.TABLE) {
                tables.add(table(element, childrenByParent, spanIdsByElement, confidenceBySpan));
            } else if (MARK_TYPES.contains(element.getElementType())) {
                marks.add(mark(element));
            }
            // Anything else (IMAGE/LINE) has no producer and no shape in DOCENGINE-L2-1/1.0.0.
        }
        return new Assembly(blocks, tables, marks);
    }

    private BlockView block(
            LayoutElement element,
            Map<UUID, List<Long>> spanIdsByElement,
            Map<Long, BigDecimal> confidenceBySpan) {
        List<Long> spanIds = spanIdsByElement.getOrDefault(element.getId(), List.of());
        return new BlockView(
                element.getId(),
                element.getElementType().name(),
                box(element),
                element.getConfidence(),
                textConfidence(spanIds, confidenceBySpan),
                element.getText() == null ? "" : element.getText(),
                spanIds);
    }

    private TableView table(
            LayoutElement table,
            Map<UUID, List<LayoutElement>> childrenByParent,
            Map<UUID, List<Long>> spanIdsByElement,
            Map<Long, BigDecimal> confidenceBySpan) {
        List<CellView> cells = new ArrayList<>();
        for (LayoutElement row : childrenByParent.getOrDefault(table.getId(), List.of())) {
            if (row.getElementType() != LayoutElementType.TABLE_ROW) {
                continue;
            }
            for (LayoutElement cell : childrenByParent.getOrDefault(row.getId(), List.of())) {
                if (cell.getElementType() != LayoutElementType.TABLE_CELL) {
                    continue;
                }
                JsonNode address = attributes(cell);
                if (!address.hasNonNull("row") || !address.hasNonNull("col")) {
                    // Both producers always address a cell; a row without an address is corrupt
                    // data, and serving it under an invented address would be a wrong record.
                    // Ids only in the error — never document content.
                    throw new IllegalStateException(
                            "layout cell without (row, col) address: " + cell.getId());
                }
                List<Long> spanIds = spanIdsByElement.getOrDefault(cell.getId(), List.of());
                cells.add(
                        new CellView(
                                cell.getId(),
                                address.get("row").asInt(),
                                address.get("col").asInt(),
                                box(cell),
                                cell.getText() == null ? "" : cell.getText(),
                                textConfidence(spanIds, confidenceBySpan),
                                spanIds));
            }
        }

        // rows/cols/ruled are the DETECTOR's declaration (TABLE attributes, tables.py). The
        // census fallback exists for trees persisted before the worker declared them — derived
        // from the same cells a consumer would count, never invented.
        JsonNode attributes = attributes(table);
        int rows =
                attributes.hasNonNull("rows")
                        ? attributes.get("rows").asInt()
                        : 1 + cells.stream().mapToInt(CellView::row).max().orElse(-1);
        int cols =
                attributes.hasNonNull("cols")
                        ? attributes.get("cols").asInt()
                        : 1 + cells.stream().mapToInt(CellView::col).max().orElse(-1);
        // Absent means unconfirmed: "ruled: true" is a claim vector rulings must have made.
        boolean ruled = attributes.path("ruled").asBoolean(false);
        return new TableView(
                table.getId(), box(table), rows, cols, ruled, table.getConfidence(), cells);
    }

    private MarkView mark(LayoutElement element) {
        JsonNode attributes = attributes(element);
        Boolean checked =
                element.getElementType() == LayoutElementType.CHECKBOX
                                && attributes.hasNonNull("checked")
                        ? attributes.get("checked").asBoolean()
                        : null;
        // Marks are ink, not text: no member spans, so no textConfidence — null, never 1.0.
        return new MarkView(
                element.getId(),
                element.getElementType().name(),
                box(element),
                element.getConfidence(),
                null,
                checked,
                List.of());
    }

    private Map<UUID, List<Long>> spanIdsByElement(List<LayoutElement> pageElements) {
        List<UUID> ids = pageElements.stream().map(LayoutElement::getId).toList();
        Map<UUID, List<Long>> byElement = new HashMap<>();
        for (LayoutElementSpan link :
                elementSpans.findByLayoutElementIdInOrderByLayoutElementIdAscOrdinalAsc(ids)) {
            byElement
                    .computeIfAbsent(link.getLayoutElementId(), element -> new ArrayList<>())
                    .add(link.getTextSpanId());
        }
        return byElement;
    }

    private Map<Long, BigDecimal> confidenceBySpan(UUID pageId) {
        Map<Long, BigDecimal> bySpan = new HashMap<>();
        for (TextSpan span : spans.findByPageIdOrderBySourceAscOrdinalAsc(pageId)) {
            bySpan.put(span.getId(), span.getConfidence());
        }
        return bySpan;
    }

    /**
     * The minimum over member spans — L3's {@code spanConfidence} rule verbatim, so the one axis
     * the layers share stays commensurable (design D10). Null when there are no member spans: a
     * structure with no text has no text confidence, and 1.0 would claim certainty about nothing.
     */
    private static BigDecimal textConfidence(
            List<Long> spanIds, Map<Long, BigDecimal> confidenceBySpan) {
        BigDecimal min = null;
        for (Long spanId : spanIds) {
            BigDecimal confidence = confidenceBySpan.get(spanId);
            if (confidence != null && (min == null || confidence.compareTo(min) < 0)) {
                min = confidence;
            }
        }
        return min;
    }

    private JsonNode attributes(LayoutElement element) {
        if (element.getAttributes() == null) {
            return JSON.missingNode();
        }
        try {
            return JSON.readTree(element.getAttributes());
        } catch (java.io.IOException e) {
            // jsonb was valid when Postgres accepted it; failure here is corruption, not input.
            throw new UncheckedIOException(
                    "unreadable layout_element attributes: " + element.getId(), e);
        }
    }

    private static BoxView box(LayoutElement element) {
        return new BoxView(
                element.getX(), element.getY(), element.getWidth(), element.getHeight());
    }

    // ── the resolvers' filters: narrow, never reorder ───────────────────────

    private static List<BlockView> filterBlocks(List<BlockView> blocks, Long spanFilter) {
        if (spanFilter == null) {
            return blocks;
        }
        return blocks.stream().filter(block -> block.spanIds().contains(spanFilter)).toList();
    }

    private static List<TableView> filterTables(List<TableView> tables, Long spanFilter) {
        if (spanFilter == null) {
            return tables;
        }
        // A table is one structure: it is kept WHOLE when any cell owns the span. Which cell that
        // is stays readable off the cells' own spanIds — returning half a table would misstate
        // rows/cols and break (row, col) addressing.
        return tables.stream()
                .filter(
                        table ->
                                table.cells().stream()
                                        .anyMatch(cell -> cell.spanIds().contains(spanFilter)))
                .toList();
    }

    private static List<MarkView> filterMarks(List<MarkView> marks, Long spanFilter) {
        if (spanFilter == null) {
            return marks;
        }
        return marks.stream().filter(mark -> mark.spanIds().contains(spanFilter)).toList();
    }

    // ── detectorCoverage: the honesty marker ────────────────────────────────

    private static final String COVERAGE_RAN = "RAN";
    private static final String COVERAGE_NOT_IMPLEMENTED = "NOT_IMPLEMENTED";
    private static final String COVERAGE_UNKNOWN = "UNKNOWN";

    /**
     * Read from the worker's persisted per-page declaration ({@code page.layout_not_implemented},
     * V18 — the {@code /v1/layout} {@code notImplemented} list verbatim).
     *
     * <p>With a declaration: a family whose element types the worker listed reads
     * {@code NOT_IMPLEMENTED} — the worker said it did not look — and everything else reads
     * {@code RAN}, including {@code RAN} over an empty result, which is a real answer.
     *
     * <p>Without one, what can honestly be said depends on whether this page has any persisted
     * element at all. Page rows commit at RENDERING, stages commit in separate transactions, and
     * this endpoint has no stage guard — so a NULL declaration covers three states this read
     * cannot tell apart: a parse that predates P2.4, a package whose PARSING has not run yet (or
     * failed, permanently), and a worker build that never sent the key. An element on the page is
     * PROOF a layout response was persisted for it, and no worker version that ever shipped can
     * produce a layout response without running clustering and table detection — so with elements
     * present the span-geometry detectors ({@code table}, {@code block}) read {@code RAN}. With
     * NO elements, nothing proves any layout call ever happened, and {@code RAN} over what may be
     * a never-parsed page would be a fabricated coverage claim (a consumer would cache "no tables
     * here" about a parse that never ran) — so every family reads {@code UNKNOWN}. The pixel
     * detectors ({@code checkbox}, {@code signature}) read {@code UNKNOWN} in both cases: they
     * CAN be skipped per page, and nobody recorded whether they were.
     */
    private Map<String, String> detectorCoverage(Page page, boolean hasElements) {
        Map<String, String> coverage = new LinkedHashMap<>();
        Set<String> declared = declaredNotImplemented(page);
        if (declared == null) {
            String spanGeometry = hasElements ? COVERAGE_RAN : COVERAGE_UNKNOWN;
            coverage.put("table", spanGeometry);
            coverage.put("block", spanGeometry);
            coverage.put("checkbox", COVERAGE_UNKNOWN);
            coverage.put("signature", COVERAGE_UNKNOWN);
            return coverage;
        }
        coverage.put("table", state(declared, "TABLE"));
        // One clustering pass produces both block kinds; either name in the list means the pass
        // did not run for this page.
        coverage.put(
                "block",
                declared.contains("PARAGRAPH") || declared.contains("HEADER")
                        ? COVERAGE_NOT_IMPLEMENTED
                        : COVERAGE_RAN);
        coverage.put("checkbox", state(declared, "CHECKBOX"));
        coverage.put("signature", state(declared, "SIGNATURE"));
        return coverage;
    }

    private static String state(Set<String> declaredNotImplemented, String elementType) {
        return declaredNotImplemented.contains(elementType)
                ? COVERAGE_NOT_IMPLEMENTED
                : COVERAGE_RAN;
    }

    /** The declaration as a set of element-type names, or null when none was persisted. */
    private Set<String> declaredNotImplemented(Page page) {
        if (page.getLayoutNotImplemented() == null) {
            return null;
        }
        Set<String> declared = new java.util.HashSet<>();
        try {
            JSON.readTree(page.getLayoutNotImplemented())
                    .forEach(name -> declared.add(name.asText()));
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(
                    "unreadable layout coverage declaration on page " + page.getId(), e);
        }
        return declared;
    }

    // ── plumbing shared with the L1 read ────────────────────────────────────

    private void recordRead(
            Page page, PageStructureView view, Long spanFilter, StructureKind kindFilter) {
        // Ids, counts and codes only — never an element's text and never a box. The span id and
        // kind of a REQUESTED filter are the caller's own parameters, recorded as the filter they
        // were; nothing read off the page appears here.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("packageId", page.getPackageId());
        metadata.put("packagePageIndex", page.getPackagePageIndex());
        metadata.put("blocks", view.blocks().size());
        metadata.put("tables", view.tables().size());
        metadata.put("cells", view.tables().stream().mapToInt(table -> table.cells().size()).sum());
        metadata.put("marks", view.marks().size());
        if (spanFilter != null) {
            metadata.put("containsSpan", spanFilter);
        }
        if (kindFilter != null) {
            metadata.put("kind", kindFilter.name());
        }
        audit.record(AuditEvent.ACTION_PAGE_STRUCTURE_ACCESSED, "PAGE", page.getId(), metadata);
    }

    private static ResponseEntity.BodyBuilder headers(
            ResponseEntity.BodyBuilder builder, String etag) {
        if (etag != null) {
            builder.eTag(etag);
        }
        // Borrower text, re-grouped but unmasked: never cached anywhere but the caller's memory.
        return builder.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff");
    }

    /**
     * The whole-page validator: page id, parse digest, structure contract — or null when the page
     * has no content hash, because a pin nobody can honour is worse than no pin.
     */
    private static String validator(Page page) {
        return page.getContentHash() == null
                ? null
                : "\""
                        + page.getId()
                        + "."
                        + page.getContentHash()
                        + "-l2/"
                        + PageStructureView.STRUCTURE_CONTRACT
                        + "\"";
    }

    private static Long spanId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid("containsSpan");
        }
    }

    private static StructureKind kind(String raw) {
        try {
            return StructureKind.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("kind");
        }
    }

    /** Names the PARAMETER, never the value — an error message is not a leak channel. */
    private static DomainException invalid(String parameter) {
        return DomainException.badRequest(ErrorCode.INVALID_REQUEST, Map.of("parameter", parameter));
    }
}
