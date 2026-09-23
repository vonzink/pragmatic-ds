package com.pragmaticds.docengine.results.body;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.pragmaticds.docengine.results.body.DocumentBody.BodyBlock;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourceElement;
import com.pragmaticds.docengine.results.body.DocumentBodySource.SourcePage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The loader's contract with the composer: which rows arrive, what the attribute JSON decodes to,
 * and — the one that matters — that document reading order is carried as DATA rather than as the
 * order the rows happened to arrive in.
 */
class DocumentBodySourceLoaderTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();

    private final LogicalDocumentRepository documents = mock(LogicalDocumentRepository.class);
    private final LogicalDocumentPageRepository memberships =
            mock(LogicalDocumentPageRepository.class);
    private final PageRepository pages = mock(PageRepository.class);
    private final LayoutElementRepository elements = mock(LayoutElementRepository.class);
    private final LayoutElementSpanRepository elementSpans =
            mock(LayoutElementSpanRepository.class);
    private final TextSpanRepository spans = mock(TextSpanRepository.class);

    private final DocumentBodySourceLoader loader =
            new DocumentBodySourceLoader(
                    documents, memberships, pages, elements, elementSpans, spans);

    @BeforeEach
    void setTenant() {
        TenantContext.set(ORG);
        lenient().when(pages.findByIdInAndOrgId(anyCollection(), any())).thenReturn(List.of());
        lenient()
                .when(memberships.findByLogicalDocumentIdOrderByOrdinal(any()))
                .thenReturn(List.of());
        lenient()
                .when(elements.findByPageIdInOrderByPageIdAscOrdinalAsc(anyCollection()))
                .thenReturn(List.of());
        lenient()
                .when(
                        elementSpans
                                .findByLayoutElementIdInOrderByLayoutElementIdAscOrdinalAsc(
                                        anyCollection()))
                .thenReturn(List.of());
        lenient().when(spans.findAllById(any())).thenReturn(List.of());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ── not found ───────────────────────────────────────────────────────────

    @Test
    void absentOrForeignDocumentLoadsAsEmptyOptional() {
        when(documents.findByIdAndOrgId(DOCUMENT, ORG)).thenReturn(Optional.empty());

        assertThat(loader.load(DOCUMENT)).isEmpty();
        verify(memberships, never()).findByLogicalDocumentIdOrderByOrdinal(any());
    }

    @Test
    void documentWithNoMemberPagesIsPresentAndEmpty() {
        document("W2");

        Optional<DocumentBodySource> source = loader.load(DOCUMENT);

        assertThat(source).isPresent();
        assertThat(source.get().pages()).isEmpty();
        assertThat(source.get().documentTypeCode()).isEqualTo("W2");
    }

    @Test
    void unknownTypedDocumentLoadsLikeAnyOther() {
        document("UNKNOWN");
        UUID pageId = page(0, 7);
        membership(pageId, 0);
        givenElements(element(pageId, null, LayoutElementType.PARAGRAPH, 0, "hello", null));

        DocumentBodySource source = loader.load(DOCUMENT).orElseThrow();

        assertThat(source.documentTypeCode()).isEqualTo("UNKNOWN");
        assertThat(source.pages()).hasSize(1);
        assertThat(source.pages().get(0).elements()).hasSize(1);
    }

    // ── membership and page identity ────────────────────────────────────────

    @Test
    void eachPageCarriesItsPackageIndexAndItsDocumentOrdinal() {
        document("W2");
        UUID first = page(0, 12);
        UUID second = page(1, 3);
        membership(first, 0);
        membership(second, 1);

        DocumentBodySource source = loader.load(DOCUMENT).orElseThrow();

        assertThat(source.pages())
                .extracting(
                        SourcePage::packagePageIndex,
                        SourcePage::documentPageOrdinal,
                        SourcePage::pageId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(12, 0, first),
                        org.assertj.core.groups.Tuple.tuple(3, 1, second));
    }

    @Test
    void aMemberPageThatIsNotReadableInThisOrgIsRefusedRatherThanDropped() {
        document("W2");
        UUID pageId = UUID.randomUUID();
        membership(pageId, 0);
        when(pages.findByIdInAndOrgId(anyCollection(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> loader.load(DOCUMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(pageId.toString());
    }

    @Test
    void aRowFromAnotherOrgIsRefused() {
        document("W2");
        UUID pageId = page(0, 0);
        LogicalDocumentPage foreign = mock(LogicalDocumentPage.class);
        when(foreign.getPageId()).thenReturn(pageId);
        when(foreign.getOrdinal()).thenReturn(0);
        when(foreign.getOrgId()).thenReturn(OTHER_ORG);
        when(memberships.findByLogicalDocumentIdOrderByOrdinal(DOCUMENT))
                .thenReturn(List.of(foreign));

        assertThatThrownBy(() -> loader.load(DOCUMENT)).isInstanceOf(IllegalStateException.class);
    }

    // ── the whole tree, unfiltered ──────────────────────────────────────────

    @Test
    void childrenArriveAlongsideTheirParentsUnfiltered() {
        document("W2");
        UUID pageId = page(0, 0);
        membership(pageId, 0);
        LayoutElement table = element(pageId, null, LayoutElementType.TABLE, 0, null, "{}");
        LayoutElement row =
                element(pageId, table.getId(), LayoutElementType.TABLE_ROW, 1, null, null);
        LayoutElement cell =
                element(
                        pageId,
                        row.getId(),
                        LayoutElementType.TABLE_CELL,
                        2,
                        "x",
                        "{\"row\":0,\"col\":1}");
        givenElements(table, row, cell);

        List<SourceElement> loaded = loader.load(DOCUMENT).orElseThrow().pages().get(0).elements();

        assertThat(loaded)
                .extracting(SourceElement::type)
                .containsExactly(
                        LayoutElementType.TABLE,
                        LayoutElementType.TABLE_ROW,
                        LayoutElementType.TABLE_CELL);
        assertThat(loaded.get(2).parentElementId()).isEqualTo(row.getId());
    }

    // ── attribute decoding ──────────────────────────────────────────────────

    @Test
    void cellAddressDecodesToTypedRowAndCol() {
        SourceElement cell =
                onlyElement(
                        LayoutElementType.TABLE_CELL, "{\"row\":2,\"col\":5,\"rows\":99}");

        assertThat(cell.cellRow()).isEqualTo(2);
        assertThat(cell.cellCol()).isEqualTo(5);
        // "rows" belongs to a TABLE; reading it here would let one detector's attribute wear
        // another contract's name.
        assertThat(cell.tableRows()).isNull();
    }

    @Test
    void anUnaddressedCellDecodesToNullRatherThanThrowing() {
        SourceElement cell = onlyElement(LayoutElementType.TABLE_CELL, "{}");

        assertThat(cell.cellRow()).isNull();
        assertThat(cell.cellCol()).isNull();
    }

    @Test
    void tableGeometryDecodesFromTheDetectorsDeclaration() {
        SourceElement table =
                onlyElement(
                        LayoutElementType.TABLE, "{\"rows\":4,\"cols\":3,\"ruled\":true}");

        assertThat(table.tableRows()).isEqualTo(4);
        assertThat(table.tableCols()).isEqualTo(3);
        assertThat(table.tableRuled()).isTrue();
    }

    @Test
    void anUndeclaredTableGeometryIsNullNotZeroAndUndeclaredRuledIsNullNotFalse() {
        SourceElement table = onlyElement(LayoutElementType.TABLE, "{}");

        assertThat(table.tableRows()).isNull();
        assertThat(table.tableCols()).isNull();
        assertThat(table.tableRuled()).isNull();
    }

    @Test
    void checkboxStateDecodesAndAbsenceStaysNull() {
        assertThat(onlyElement(LayoutElementType.CHECKBOX, "{\"checked\":true}").checked()).isTrue();
        assertThat(onlyElement(LayoutElementType.CHECKBOX, "{\"checked\":false}").checked())
                .isFalse();
        // Null is "the detector could not tell", which is not false.
        assertThat(onlyElement(LayoutElementType.CHECKBOX, "{}").checked()).isNull();
        assertThat(onlyElement(LayoutElementType.CHECKBOX, null).checked()).isNull();
    }

    @Test
    void checkedIsReadOnlyOffACheckbox() {
        assertThat(onlyElement(LayoutElementType.SIGNATURE, "{\"checked\":true}").checked())
                .isNull();
    }

    @Test
    void absentAttributeJsonDecodesEveryTypedMemberToNull() {
        SourceElement table = onlyElement(LayoutElementType.TABLE, null);

        assertThat(table.tableRows()).isNull();
        assertThat(table.tableRuled()).isNull();
    }

    // ── confidence and spans ────────────────────────────────────────────────

    @Test
    void textConfidenceIsTheMinimumOverMemberSpans() {
        document("W2");
        UUID pageId = page(0, 0);
        membership(pageId, 0);
        LayoutElement paragraph =
                element(pageId, null, LayoutElementType.PARAGRAPH, 0, "hi", null);
        givenElements(paragraph);
        givenLinks(link(paragraph.getId(), 11L, 0), link(paragraph.getId(), 12L, 1));
        givenSpans(span(11L, "0.92"), span(12L, "0.41"));

        SourceElement loaded = loader.load(DOCUMENT).orElseThrow().pages().get(0).elements().get(0);

        assertThat(loaded.textConfidence()).isEqualByComparingTo("0.41");
    }

    @Test
    void anElementWithNoMemberSpansHasNullTextConfidenceNeverOne() {
        SourceElement mark = onlyElement(LayoutElementType.SIGNATURE, null);

        assertThat(mark.textConfidence()).isNull();
        assertThat(mark.spanIds()).isEmpty();
    }

    @Test
    void spanIdsArriveInLinkOrder() {
        document("W2");
        UUID pageId = page(0, 0);
        membership(pageId, 0);
        LayoutElement paragraph =
                element(pageId, null, LayoutElementType.PARAGRAPH, 0, "hi", null);
        givenElements(paragraph);
        // The repository orders by link ordinal, not by span id: 90 precedes 12.
        givenLinks(link(paragraph.getId(), 90L, 0), link(paragraph.getId(), 12L, 1));
        givenSpans(span(90L, "0.9"), span(12L, "0.9"));

        SourceElement loaded = loader.load(DOCUMENT).orElseThrow().pages().get(0).elements().get(0);

        assertThat(loaded.spanIds()).containsExactly(90L, 12L);
    }

    @Test
    void onlyClaimedSpansAreRead() {
        document("W2");
        UUID pageId = page(0, 0);
        membership(pageId, 0);
        LayoutElement paragraph =
                element(pageId, null, LayoutElementType.PARAGRAPH, 0, "hi", null);
        givenElements(paragraph);
        givenLinks(link(paragraph.getId(), 5L, 0));
        givenSpans(span(5L, "0.5"));

        loader.load(DOCUMENT);

        // Not a per-page sweep: a several-hundred-page document would be a query per page.
        verify(spans, never()).findByPageIdOrderBySourceAscOrdinalAsc(any());
        verify(spans).findAllById(List.of(5L));
    }

    // ── the hazard: arrival order is not reading order ──────────────────────

    @Test
    void uuidOrderedElementArrivalStillComposesInDocumentReadingOrder() {
        document("W2");
        UUID firstPage = page(0, 0);
        UUID secondPage = page(1, 1);
        // Membership, not page id, is document order — and here they disagree.
        membership(secondPage, 0);
        membership(firstPage, 1);
        LayoutElement onFirst =
                element(firstPage, null, LayoutElementType.PARAGRAPH, 0, "second", null);
        LayoutElement onSecond =
                element(secondPage, null, LayoutElementType.PARAGRAPH, 0, "first", null);
        // The batch query returns page-id order, which is a UUID order: hand it back shuffled.
        givenElements(onFirst, onSecond);

        DocumentBody body = DocumentBodyComposer.compose(loader.load(DOCUMENT).orElseThrow());

        assertThat(body.blocks()).extracting(BodyBlock::text).containsExactly("first", "second");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private final List<LogicalDocumentPage> memberRows = new ArrayList<>();
    private final Map<UUID, Page> pageRows = new java.util.LinkedHashMap<>();

    private void document(String typeCode) {
        LogicalDocument document = mock(LogicalDocument.class);
        lenient().when(document.getDocumentTypeCode()).thenReturn(typeCode);
        when(documents.findByIdAndOrgId(DOCUMENT, ORG)).thenReturn(Optional.of(document));
    }

    private UUID page(int pageIndex, int packagePageIndex) {
        UUID id = UUID.randomUUID();
        Page page = mock(Page.class);
        lenient().when(page.getId()).thenReturn(id);
        lenient().when(page.getPageIndex()).thenReturn(pageIndex);
        lenient().when(page.getPackagePageIndex()).thenReturn(packagePageIndex);
        lenient().when(page.getOrgId()).thenReturn(ORG);
        pageRows.put(id, page);
        when(pages.findByIdInAndOrgId(anyCollection(), any()))
                .thenAnswer(
                        invocation -> {
                            Collection<?> ids = invocation.getArgument(0);
                            return pageRows.entrySet().stream()
                                    .filter(entry -> ids.contains(entry.getKey()))
                                    .map(Map.Entry::getValue)
                                    .toList();
                        });
        return id;
    }

    private void membership(UUID pageId, int ordinal) {
        LogicalDocumentPage member = mock(LogicalDocumentPage.class);
        lenient().when(member.getPageId()).thenReturn(pageId);
        lenient().when(member.getOrdinal()).thenReturn(ordinal);
        lenient().when(member.getOrgId()).thenReturn(ORG);
        memberRows.add(member);
        when(memberships.findByLogicalDocumentIdOrderByOrdinal(DOCUMENT)).thenReturn(memberRows);
    }

    private LayoutElement element(
            UUID pageId,
            UUID parentId,
            LayoutElementType type,
            int ordinal,
            String text,
            String attributes) {
        LayoutElement element = mock(LayoutElement.class);
        lenient().when(element.getId()).thenReturn(UUID.randomUUID());
        lenient().when(element.getOrgId()).thenReturn(ORG);
        lenient().when(element.getPageId()).thenReturn(pageId);
        lenient().when(element.getParentElementId()).thenReturn(parentId);
        lenient().when(element.getElementType()).thenReturn(type);
        lenient().when(element.getOrdinal()).thenReturn(ordinal);
        lenient().when(element.getX()).thenReturn(new BigDecimal("1.0"));
        lenient().when(element.getY()).thenReturn(new BigDecimal("2.0"));
        lenient().when(element.getWidth()).thenReturn(new BigDecimal("3.0"));
        lenient().when(element.getHeight()).thenReturn(new BigDecimal("4.0"));
        lenient().when(element.getText()).thenReturn(text);
        lenient().when(element.getConfidence()).thenReturn(new BigDecimal("0.99"));
        lenient().when(element.getAttributes()).thenReturn(attributes);
        return element;
    }

    private void givenElements(LayoutElement... rows) {
        when(elements.findByPageIdInOrderByPageIdAscOrdinalAsc(anyCollection()))
                .thenReturn(List.of(rows));
    }

    private LayoutElementSpan link(UUID elementId, long spanId, int ordinal) {
        LayoutElementSpan link = mock(LayoutElementSpan.class);
        lenient().when(link.getLayoutElementId()).thenReturn(elementId);
        lenient().when(link.getTextSpanId()).thenReturn(spanId);
        lenient().when(link.getOrdinal()).thenReturn(ordinal);
        lenient().when(link.getOrgId()).thenReturn(ORG);
        return link;
    }

    private void givenLinks(LayoutElementSpan... rows) {
        when(elementSpans.findByLayoutElementIdInOrderByLayoutElementIdAscOrdinalAsc(
                        anyCollection()))
                .thenReturn(List.of(rows));
    }

    private TextSpan span(long id, String confidence) {
        TextSpan span = mock(TextSpan.class);
        lenient().when(span.getId()).thenReturn(id);
        lenient().when(span.getOrgId()).thenReturn(ORG);
        lenient().when(span.getConfidence()).thenReturn(new BigDecimal(confidence));
        return span;
    }

    private void givenSpans(TextSpan... rows) {
        when(spans.findAllById(any())).thenReturn(List.of(rows));
    }

    /** One page, one top-level element of the given type and attribute JSON. */
    private SourceElement onlyElement(LayoutElementType type, String attributes) {
        memberRows.clear();
        pageRows.clear();
        document("W2");
        UUID pageId = page(0, 0);
        membership(pageId, 0);
        givenElements(element(pageId, null, type, 0, "text", attributes));
        return loader.load(DOCUMENT).orElseThrow().pages().get(0).elements().get(0);
    }
}
