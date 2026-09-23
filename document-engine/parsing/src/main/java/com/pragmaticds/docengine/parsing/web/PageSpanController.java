package com.pragmaticds.docengine.parsing.web;

import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.ParsingPackageRefRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRow;
import com.pragmaticds.docengine.parsing.repo.TextSpanWindow;
import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.platform.web.EntityTags;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * L1 — the raw text layer, served (design "Full capture", D1/D6/D12).
 *
 * <p>The engine has always captured every word with a bounding box and has always thrown almost all
 * of it away at the read boundary: ten named fields per document type reached a consumer, and the
 * first question nobody wrote a schema field for forced a re-parse. This endpoint closes that gap
 * without adding a single detector — the rows already exist, immutable, under RLS, digest-addressed
 * by {@code page.content_hash}. The only thing that was missing was a way to ask for them.
 *
 * <h2>ADMIN-only, and why that is not timidity</h2>
 *
 * <p>The raw text layer is MORE revealing than {@code /fields}, which masks. Masking cannot be
 * applied here in principle: it is defined per named sensitive field, and L1 has no names. So the
 * boundary is access control plus an audit trail, exactly as for the raw engine-result bytes, and
 * the matcher for this path sits AHEAD of the broad {@code GET /v1/**} rule in {@code SecurityConfig}
 * — behind it, READONLY would read everything.
 *
 * <p>What that gate does not cover, stated so nobody over-reads it: the page RASTER
 * ({@code GET /v1/pages/{id}/render}, and the {@code /signed-url} that links to it) shows these same
 * words as pixels, is reachable by READONLY, and writes no audit event. The boundary is one
 * representation deep and that is a decision, not an oversight — but it means L1 is not strictly more
 * revealing than everything a READONLY principal can already reach, and it means the L1 audit trail
 * is not a complete record of who saw this page's content.
 *
 * <h2>Page-scoped, on purpose</h2>
 *
 * <p>There is no package-wide variant. A 75-page package is 4–11 MB of spans, and inventing this
 * repository's first pagination convention on its largest possible payload is the wrong place to
 * start. A consumer walks {@code /v1/packages/{id}/pages} and then comes here per page — and, once
 * L2 ships, reads structure first and fetches L1 only for the regions it cares about.
 */
@RestController
public class PageSpanController {

    /** Design D6. Large enough that a typical page is one round trip; small enough to bound a read. */
    private static final int DEFAULT_LIMIT = TextSpanWindow.DEFAULT_LIMIT;

    private static final int MAX_LIMIT = TextSpanWindow.MAX_LIMIT;

    private static final BigDecimal MIN_CONFIDENCE = BigDecimal.ZERO;
    private static final BigDecimal MAX_CONFIDENCE = BigDecimal.ONE;

    private final ParsingPackageRefRepository packages;
    private final PageRepository pages;
    private final TextSpanRepository spans;
    private final AuditService audit;

    public PageSpanController(
            ParsingPackageRefRepository packages,
            PageRepository pages,
            TextSpanRepository spans,
            AuditService audit) {
        this.packages = packages;
        this.pages = pages;
        this.spans = spans;
        this.audit = audit;
    }

    /**
     * One window of a page's captured spans, in the page's own total order {@code (source, ordinal,
     * id)} — or, with {@code element=}, one layout element's member spans in the ELEMENT's order.
     *
     * <p>All filters AND together. All of them narrow; none of them reorder.
     *
     * @param after an opaque cursor from a previous {@code nextCursor}
     * @param limit 1..5000, default 1000. Out of range is refused rather than clamped: silently
     *     returning fewer rows than asked for is a lie about the request that a paginating caller
     *     cannot detect.
     * @param source {@code NATIVE} or {@code OCR}
     * @param minConfidence 0..1 inclusive; spans at or above it
     * @param box {@code x,y,w,h} in rotation-0 PDF points — spans whose box INTERSECTS the window
     * @param element a {@code layout_element} id; its member spans, in link order (design D3)
     */
    @GetMapping("/v1/pages/{id}/spans")
    public ResponseEntity<PageSpansView> spans(
            @PathVariable UUID id,
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "minConfidence", required = false) String minConfidence,
            @RequestParam(name = "box", required = false) String box,
            @RequestParam(name = "element", required = false) String element,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        UUID orgId = TenantContext.require();
        // Verbatim from PageController: the page must exist in this org, AND its package must not be
        // tombstoned. A soft-deleted package's page rows survive until purge and must read as absent
        // on every path. Foreign, tombstoned and nonexistent are one indistinguishable 404.
        Page page =
                pages.findByIdAndOrgId(id, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(page.getPackageId(), orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));

        // A validator describes ONE representation of ONE resource, and this endpoint serves many
        // representations of each page: the body varies with after/limit/source/minConfidence/box/
        // element while page.content_hash does not. So the tag is issued, and honoured, ONLY for the
        // whole-page window — the one representation it actually describes. Every other window falls
        // through to normal parameter validation and a real 200, which is what stops a caller from
        // being told mid-walk that its 20-of-50 copy is current.
        boolean wholePage =
                after == null
                        && limit == null
                        && source == null
                        && minConfidence == null
                        && box == null
                        && element == null;
        String etag = wholePage ? validator(page) : null;
        if (etag != null && EntityTags.anyMatch(ifNoneMatch, etag)) {
            // No spans leave the process, so there is nothing to audit as read: the caller already
            // holds the bytes it is revalidating, and a page's L1 layer cannot change without a
            // re-parse, which writes a new content_hash and therefore a new ETag.
            return headers(ResponseEntity.status(304), etag).build();
        }

        TextSpanWindow window = window(orgId, page, source, minConfidence, box, element);
        // The cursor is validated against the FINAL window, so a page cursor cannot be replayed
        // against an element's order by adding ?element= to the next request.
        TextSpanWindow resumed = resumed(window, after);
        int pageSize = pageSize(limit);
        // One row past the limit, so "is there more" is answered by evidence rather than by the
        // heuristic "the batch was full" — which reports truncated on an exact-multiple final page.
        List<TextSpanRow> rows = new ArrayList<>(resumed.limit(pageSize + 1).fetch(spans));
        String nextCursor = null;
        if (rows.size() > pageSize) {
            rows.remove(rows.size() - 1);
            nextCursor = SpanCursor.encode(window, rows.get(rows.size() - 1));
        }

        PageSpansView view = PageSpansView.of(page, rows, nextCursor);
        recordRead(page, view, window, pageSize);
        return headers(ResponseEntity.ok(), etag).body(view);
    }

    private TextSpanWindow window(
            UUID orgId, Page page, String source, String minConfidence, String box, String element) {
        TextSpanWindow window = TextSpanWindow.forOrg(orgId).page(page.getId());
        if (element != null) {
            window = window.element(uuid(element, "element"));
        }
        if (source != null) {
            window = window.source(spanSource(source));
        }
        if (minConfidence != null) {
            window = window.minConfidence(confidence(minConfidence));
        }
        if (box != null) {
            BigDecimal[] values = box(box);
            window = window.box(values[0], values[1], values[2], values[3]);
        }
        return window;
    }

    /** Applies {@code ?after=} last, so the cursor is validated against the FINAL window's mode. */
    private TextSpanWindow resumed(TextSpanWindow window, String after) {
        return after == null ? window : SpanCursor.decode(after, window).resume(window);
    }

    private void recordRead(Page page, PageSpansView view, TextSpanWindow window, int pageSize) {
        // Ids, counts and codes only — never a span's text, and never a box's contents. The
        // geometry of a REQUESTED window is the caller's own parameter, not document content, so it
        // is recorded as the filter it was; nothing read off the page appears here.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("packageId", page.getPackageId());
        metadata.put("packagePageIndex", page.getPackagePageIndex());
        metadata.put("mode", window.mode().name());
        metadata.put("spanCount", view.returned());
        metadata.put("limit", pageSize);
        metadata.put("truncated", view.truncated());
        if (window.elementId() != null) {
            metadata.put("layoutElementId", window.elementId());
        }
        audit.record(AuditEvent.ACTION_PAGE_SPANS_ACCESSED, "PAGE", page.getId(), metadata);
    }

    private static ResponseEntity.BodyBuilder headers(ResponseEntity.BodyBuilder builder, String etag) {
        if (etag != null) {
            builder.eTag(etag);
        }
        // Borrower text, unmasked. It is never cached anywhere but the caller's own memory.
        return builder.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff");
    }

    /**
     * The validator for the WHOLE-page window, or null when there is nothing honest to send.
     *
     * <p>It names the page id as well as the digest, and that is not belt-and-braces.
     * {@code page.content_hash} is the DUPLICATE-PAGE detection hash (DATA_MODEL; V5's
     * {@code duplicate_of_page_id} exists precisely because two pages in one package are EXPECTED to
     * collide when their span text and geometry match), so it identifies CONTENT and not a RESOURCE.
     * A bare-hash tag would let a caller validate page B's window against the copy of page A it
     * already holds, and go on to attribute A's span ids to B — and {@code span.id} is the join key
     * {@code evidence[].textSpanId} and {@code ?element=} both rely on. That is a wrong record, not a
     * missing one.
     *
     * <p>Null when the page has no content hash: a pin nobody can honour is worse than no pin.
     */
    private static String validator(Page page) {
        return page.getContentHash() == null
                ? null
                : "\"" + page.getId() + "." + page.getContentHash() + "\"";
    }

    private static int pageSize(String raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        int limit;
        try {
            limit = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid("limit");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw invalid("limit");
        }
        return limit;
    }

    private static SpanSource spanSource(String raw) {
        try {
            return SpanSource.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("source");
        }
    }

    private static BigDecimal confidence(String raw) {
        BigDecimal value = decimal(raw, "minConfidence");
        if (value.compareTo(MIN_CONFIDENCE) < 0 || value.compareTo(MAX_CONFIDENCE) > 0) {
            throw invalid("minConfidence");
        }
        return value;
    }

    /** {@code x,y,w,h}. A negative extent is refused rather than normalised into some other box. */
    private static BigDecimal[] box(String raw) {
        String[] parts = raw.split(",", -1);
        if (parts.length != 4) {
            throw invalid("box");
        }
        BigDecimal[] values = new BigDecimal[4];
        for (int i = 0; i < 4; i++) {
            values[i] = decimal(parts[i], "box");
        }
        if (values[2].signum() < 0 || values[3].signum() < 0) {
            throw invalid("box");
        }
        return values;
    }

    private static BigDecimal decimal(String raw, String parameter) {
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid(parameter);
        }
    }

    private static UUID uuid(String raw, String parameter) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw invalid(parameter);
        }
    }

    /** Names the PARAMETER, never the value — an error message is not a leak channel. */
    private static DomainException invalid(String parameter) {
        return DomainException.badRequest(ErrorCode.INVALID_REQUEST, Map.of("parameter", parameter));
    }
}
