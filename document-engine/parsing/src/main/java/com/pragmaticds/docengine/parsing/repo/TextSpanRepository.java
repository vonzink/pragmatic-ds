package com.pragmaticds.docengine.parsing.repo;

import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TextSpanRepository extends JpaRepository<TextSpan, Long> {

    List<TextSpan> findByPageIdOrderByOrdinal(UUID pageId);

    /**
     * Deterministic reading order for MIXED pages: both span sources restart ordinals
     * at 0, so ordinal-only ordering interleaves NATIVE and OCR nondeterministically
     * (heap-order ties). NATIVE block then OCR block — the same convention layout
     * persistence pinned for the identical ambiguity. Classification MUST use this.
     */
    List<TextSpan> findByPageIdOrderBySourceAscOrdinalAsc(UUID pageId);

    /**
     * L1 exposure (Spec "Full capture" P1.1): one window of a page's spans in the page's own TOTAL
     * order {@code (source, ordinal, id)}, resumable from a cursor.
     *
     * <p><b>Call it through {@link TextSpanWindow}</b>, never directly — the parameter list is a
     * cursor tuple plus five filters, and pairing it with the wrong ordering is the one bug a
     * paginated endpoint cannot survive.
     *
     * <p><b>The source half of the cursor is a disjunction, not an inequality.</b> {@code source} is
     * an {@code @Enumerated(STRING)} attribute; ordering enums by inequality in HQL would silently
     * mean "alphabetical in the database", which is a different fact from "the NATIVE block precedes
     * the OCR block" that {@code findByPageIdOrderBySourceAscOrdinalAsc} already pinned. So the
     * second arm says exactly what is meant: a cursor anywhere inside NATIVE still admits the whole
     * OCR block. {@code TextSpanOrderContractTest} fails the build if a third source is ever added,
     * because this arm would then be incomplete.
     *
     * <p>Every bound is non-null by construction (see {@code TextSpanWindow}'s defaults), so no
     * {@code :param is null} arm exists to be mis-typed, and an unfiltered window admits every
     * storable box — including one outside the page frame, which is a row an operator wants to see
     * rather than one the query should quietly drop.
     */
    @Query(
            """
            select new com.pragmaticds.docengine.parsing.repo.TextSpanRow(s, s.ordinal)
            from TextSpan s
            where s.pageId = :pageId
              and s.orgId = :orgId
              and s.source in :sources
              and s.confidence >= :minConfidence
              and s.x <= :boxRight and s.x + s.width >= :boxLeft
              and s.y <= :boxBottom and s.y + s.height >= :boxTop
              and ( ( s.source = :cursorSource
                      and ( s.ordinal > :cursorOrdinal
                            or (s.ordinal = :cursorOrdinal and s.id > :cursorId) ) )
                    or ( :cursorInNativeBlock = true
                         and s.source = com.pragmaticds.docengine.parsing.domain.SpanSource.OCR ) )
            order by s.source asc, s.ordinal asc, s.id asc
            """)
    List<TextSpanRow> findPageSpansAfter(
            @Param("pageId") UUID pageId,
            @Param("orgId") UUID orgId,
            @Param("sources") Collection<SpanSource> sources,
            @Param("minConfidence") BigDecimal minConfidence,
            @Param("boxLeft") BigDecimal boxLeft,
            @Param("boxTop") BigDecimal boxTop,
            @Param("boxRight") BigDecimal boxRight,
            @Param("boxBottom") BigDecimal boxBottom,
            @Param("cursorSource") SpanSource cursorSource,
            @Param("cursorOrdinal") int cursorOrdinal,
            @Param("cursorId") long cursorId,
            @Param("cursorInNativeBlock") boolean cursorInNativeBlock,
            Pageable limit);

    /**
     * The D3 resolver: ONE layout element's member spans, in the ELEMENT's own order — that is
     * {@code layout_element_span.ordinal}, which belongs to the link, not to the span, and need not
     * agree with page reading order.
     *
     * <p>The link's org is asserted alongside the span's: a join table is still tenant data (V4),
     * and an explicit predicate does not depend on which side of the join Hibernate's tenant filter
     * decorates.
     *
     * <p>{@code (linkOrdinal, spanId)} is total within one element because the link table's primary
     * key is {@code (layout_element_id, text_span_id)} — a span appears in an element at most once.
     */
    @Query(
            """
            select new com.pragmaticds.docengine.parsing.repo.TextSpanRow(s, l.ordinal)
            from TextSpan s
            join LayoutElementSpan l on l.textSpanId = s.id
            where l.layoutElementId = :elementId
              and l.orgId = :orgId
              and s.pageId = :pageId
              and s.orgId = :orgId
              and s.source in :sources
              and s.confidence >= :minConfidence
              and s.x <= :boxRight and s.x + s.width >= :boxLeft
              and s.y <= :boxBottom and s.y + s.height >= :boxTop
              and ( l.ordinal > :cursorOrdinal
                    or (l.ordinal = :cursorOrdinal and s.id > :cursorId) )
            order by l.ordinal asc, s.id asc
            """)
    List<TextSpanRow> findElementSpansAfter(
            @Param("pageId") UUID pageId,
            @Param("orgId") UUID orgId,
            @Param("elementId") UUID elementId,
            @Param("sources") Collection<SpanSource> sources,
            @Param("minConfidence") BigDecimal minConfidence,
            @Param("boxLeft") BigDecimal boxLeft,
            @Param("boxTop") BigDecimal boxTop,
            @Param("boxRight") BigDecimal boxRight,
            @Param("boxBottom") BigDecimal boxBottom,
            @Param("cursorOrdinal") int cursorOrdinal,
            @Param("cursorId") long cursorId,
            Pageable limit);

    /** Stage retry idempotency: clear one source's spans across a whole package before re-persist. */
    @Modifying
    @Query(
            """
            delete from TextSpan s
            where s.source = :source and s.orgId = :orgId
              and s.pageId in (select p.id from Page p where p.packageId = :packageId)
            """)
    void deleteByPackageIdAndSource(
            @Param("packageId") UUID packageId,
            @Param("source") SpanSource source,
            @Param("orgId") UUID orgId);

    /**
     * All spans for a package's pages — used when RENDERING re-runs and pages are rebuilt, and by
     * the retention purge (which sums the returned counts into its audit).
     */
    @Modifying
    @Query(
            """
            delete from TextSpan s
            where s.orgId = :orgId
              and s.pageId in (select p.id from Page p where p.packageId = :packageId)
            """)
    int deleteByPackageId(@Param("packageId") UUID packageId, @Param("orgId") UUID orgId);
}
