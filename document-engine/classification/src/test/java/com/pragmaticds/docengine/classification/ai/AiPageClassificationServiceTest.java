package com.pragmaticds.docengine.classification.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.classification.domain.DocumentType;
import com.pragmaticds.docengine.classification.repo.ClassificationResultRepository;
import com.pragmaticds.docengine.classification.repo.DocumentTypeRepository;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.platform.ai.AiTokenCounts;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationPort;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationRequest;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationResult;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationResult.PageTypeProposal;
import com.pragmaticds.docengine.platform.ai.PageTypeClassificationStatus;
import com.pragmaticds.docengine.platform.ai.StubPageTypeClassificationAdapter;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The impure shell, driven thinly: the exhaustive rule coverage lives in {@link
 * AiClassificationGatesTest}, and what matters here is that the shell HONOURS those rules against
 * real repositories — that a refusal writes nothing at all, that an unusable port is a silent
 * no-op rather than a throw, and that a page the rule packs already typed is never even shown to
 * the model.
 */
class AiPageClassificationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PACKAGE = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the adapter under the port calls itself — the name the cost rollup groups by. */
    private static final String PROVIDER = "vertex-gemini";

    /** Argument positions in the ledger INSERT, which is column-for-column the app's ledger. */
    private static final int LEDGER_PROVIDER = 3;

    private static final int LEDGER_TOKENS_IN = 7;

    private PageTypeClassificationPort port;
    private PageRepository pages;
    private TextSpanRepository spans;
    private ClassificationResultRepository classifications;
    private DocumentTypeRepository documentTypes;
    private JdbcTemplate jdbc;
    private final List<Page> packagePages = new ArrayList<>();
    private final List<ClassificationResult> currentResults = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG);
        port = mock(PageTypeClassificationPort.class);
        pages = mock(PageRepository.class);
        spans = mock(TextSpanRepository.class);
        classifications = mock(ClassificationResultRepository.class);
        documentTypes = mock(DocumentTypeRepository.class);
        jdbc = mock(JdbcTemplate.class);
        packagePages.clear();
        currentResults.clear();

        when(port.provider()).thenReturn(PROVIDER);
        when(pages.findByPackageIdOrderByPackagePageIndex(PACKAGE)).thenReturn(packagePages);
        when(classifications.findBySubjectTypeAndSubjectIdInAndCurrentTrue(
                        eq(ClassificationResult.SUBJECT_PAGE), any()))
                .thenReturn(currentResults);
        when(classifications.save(any())).thenAnswer(call -> call.getArgument(0));
        // Built BEFORE the stubbing call: creating a mock inside thenReturn(...) happens while the
        // outer when(...) is still in progress, which Mockito rejects.
        List<DocumentType> taxonomy = List.of(documentType());
        when(documentTypes.findActiveVisibleTo(ORG)).thenReturn(taxonomy);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AiPageClassificationService service(PageTypeClassificationPort withPort) {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new AiPageClassificationService(
                true,
                "gemini-2.5-flash-lite",
                new BigDecimal("0.60"),
                40,
                withPort,
                pages,
                spans,
                classifications,
                documentTypes,
                transactions,
                jdbc);
    }

    private AiPageClassificationService service() {
        return service(port);
    }

    // ── fixture helpers ─────────────────────────────────────────────────────

    private static DocumentType documentType() {
        DocumentType type = mock(DocumentType.class);
        when(type.getCode()).thenReturn("PAYSTUB");
        when(type.getDisplayName()).thenReturn("Paystub");
        when(type.getSplitDescription()).thenReturn("An employer's earnings statement.");
        return type;
    }

    /** A page carrying "EARNINGS STATEMENT" across its top, already typed {@code currentType}. */
    private UUID page(int index, String currentType, boolean transparent) {
        UUID pageId = UUID.randomUUID();
        Page page = mock(Page.class);
        when(page.getId()).thenReturn(pageId);
        when(page.getPackagePageIndex()).thenReturn(index);
        when(page.getHeightPt()).thenReturn(BigDecimal.valueOf(792));
        when(page.isBlank()).thenReturn(transparent);
        packagePages.add(page);
        if (currentType != null) {
            ClassificationResult current =
                    new ClassificationResult(
                            ClassificationResult.SUBJECT_PAGE,
                            pageId,
                            currentType,
                            new BigDecimal("0.2000"),
                            ClassificationResult.METHOD_RULE_ANCHOR,
                            null,
                            "{\"scores\":[{\"packType\":\"W2\",\"score\":0.1},"
                                    + "{\"packType\":\"PAYSTUB\",\"score\":0.2}]}");
            currentResults.add(current);
            when(classifications.findBySubjectTypeAndSubjectIdAndCurrentTrue(
                            ClassificationResult.SUBJECT_PAGE, pageId))
                    .thenReturn(java.util.Optional.of(current));
        }
        List<TextSpan> pageSpans =
                List.of(span(pageId, 0, "EARNINGS", 0), span(pageId, 1, "STATEMENT", 55));
        when(spans.findByPageIdOrderBySourceAscOrdinalAsc(pageId)).thenReturn(pageSpans);
        return pageId;
    }

    private static TextSpan span(UUID pageId, int ordinal, String text, int x) {
        TextSpan span = mock(TextSpan.class);
        when(span.getId()).thenReturn((long) (ordinal + 1));
        when(span.getText()).thenReturn(text);
        when(span.getX()).thenReturn(BigDecimal.valueOf(x));
        when(span.getY()).thenReturn(BigDecimal.ZERO);
        when(span.getWidth()).thenReturn(BigDecimal.valueOf(50));
        when(span.getHeight()).thenReturn(BigDecimal.TEN);
        return span;
    }

    private void modelAnswers(PageTypeProposal... proposals) {
        when(port.classify(any()))
                .thenReturn(
                        new PageTypeClassificationResult(
                                PageTypeClassificationStatus.OK,
                                List.of(proposals),
                                new AiTokenCounts(120, 30, 0, 0)));
    }

    private PageTypeClassificationRequest capturedRequest() {
        ArgumentCaptor<PageTypeClassificationRequest> captor =
                ArgumentCaptor.forClass(PageTypeClassificationRequest.class);
        verify(port).classify(captor.capture());
        return captor.getValue();
    }

    // ── the happy path ──────────────────────────────────────────────────────

    @Test
    void a_verified_proposal_retypes_the_page_as_an_LLM_result() {
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(pageId, "PAYSTUB", new BigDecimal("0.91"), "EARNINGS STATEMENT"));

        AiPageClassificationService.StageResult result = service().classifyUnknownPages(PACKAGE);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.OK);
        assertThat(result.pagesConsidered()).isEqualTo(1);
        assertThat(result.pagesTypedByModel()).isEqualTo(1);
        assertThat(result.pagesRefusedByGate()).isZero();

        verify(classifications)
                .supersedeCurrent(ORG, ClassificationResult.SUBJECT_PAGE, pageId);
        ArgumentCaptor<ClassificationResult> saved =
                ArgumentCaptor.forClass(ClassificationResult.class);
        verify(classifications).save(saved.capture());
        ClassificationResult row = saved.getValue();
        assertThat(row.getDocumentTypeCode()).isEqualTo("PAYSTUB");
        assertThat(row.getMethod()).isEqualTo(ClassificationResult.METHOD_LLM);
        assertThat(row.getRulePackVersion()).isNull();
        assertThat(row.getConfidence()).isEqualByComparingTo("0.91");
    }

    @Test
    void the_evidence_carries_span_ids_and_offsets_and_no_document_text() throws Exception {
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(pageId, "PAYSTUB", new BigDecimal("0.91"), "EARNINGS STATEMENT"));

        service().classifyUnknownPages(PACKAGE);

        ArgumentCaptor<ClassificationResult> saved =
                ArgumentCaptor.forClass(ClassificationResult.class);
        verify(classifications).save(saved.capture());
        JsonNode evidence = JSON.readTree(saved.getValue().getEvidence());
        assertThat(evidence.path("source").asText()).isEqualTo("AI");
        assertThat(evidence.path("model").asText()).isEqualTo("gemini-2.5-flash-lite");
        assertThat(evidence.path("promptVersion").asText()).isNotBlank();
        assertThat(evidence.path("matchedSpanIds")).hasSize(2);
        assertThat(evidence.path("offsets").path("start").asInt()).isZero();
        assertThat(evidence.path("offsets").path("end").asInt()).isEqualTo(18);
        assertThat(evidence.path("deterministicRunnerUp").path("type").asText())
                .isEqualTo("PAYSTUB");
        assertThat(evidence.toString()).doesNotContain("EARNINGS");
    }

    @Test
    void one_ai_interpretation_row_is_written_per_model_call() {
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(pageId, "PAYSTUB", new BigDecimal("0.91"), "EARNINGS STATEMENT"));

        service().classifyUnknownPages(PACKAGE);

        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }

    // ── the refusals, seen from the shell ───────────────────────────────────

    @Test
    void a_quote_that_appears_nowhere_leaves_the_page_unknown() {
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(
                        pageId, "PAYSTUB", new BigDecimal("0.99"), "Uniform Residential Loan App"));

        AiPageClassificationService.StageResult result = service().classifyUnknownPages(PACKAGE);

        assertThat(result.pagesTypedByModel()).isZero();
        assertThat(result.pagesRefusedByGate()).isEqualTo(1);
        verify(classifications, never()).supersedeCurrent(any(), any(), any());
        verify(classifications, never()).save(any());
    }

    @Test
    void a_proposal_below_the_confidence_floor_leaves_the_page_unknown() {
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(pageId, "PAYSTUB", new BigDecimal("0.42"), "EARNINGS STATEMENT"));

        assertThat(service().classifyUnknownPages(PACKAGE).pagesRefusedByGate()).isEqualTo(1);
        verify(classifications, never()).save(any());
    }

    @Test
    void an_unknown_proposal_leaves_the_deterministic_result_standing() {
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(
                        pageId,
                        AiClassificationGates.UNKNOWN,
                        new BigDecimal("0.99"),
                        "EARNINGS STATEMENT"));

        assertThat(service().classifyUnknownPages(PACKAGE).pagesRefusedByGate()).isEqualTo(1);
        verify(classifications, never()).save(any());
    }

    // ── what the model is allowed to see ────────────────────────────────────

    @Test
    void blank_and_duplicate_pages_are_never_sent_to_the_model() {
        UUID unknownPage = page(0, AiClassificationGates.UNKNOWN, false);
        page(1, AiClassificationGates.UNKNOWN, true);
        modelAnswers();

        service().classifyUnknownPages(PACKAGE);

        assertThat(capturedRequest().pages())
                .extracting(PageTypeClassificationRequest.CandidatePage::pageId)
                .containsExactly(unknownPage);
    }

    @Test
    void a_page_the_rule_packs_already_typed_is_never_sent_and_never_superseded() {
        page(0, "W2", false);
        modelAnswers();

        AiPageClassificationService.StageResult result = service().classifyUnknownPages(PACKAGE);

        assertThat(result.pagesConsidered()).isZero();
        verify(port, never()).classify(any());
        verify(classifications, never()).supersedeCurrent(any(), any(), any());
    }

    @Test
    void the_page_cap_is_honoured_in_page_order() {
        UUID first = page(0, AiClassificationGates.UNKNOWN, false);
        UUID second = page(1, AiClassificationGates.UNKNOWN, false);
        page(2, AiClassificationGates.UNKNOWN, false);
        modelAnswers();

        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        new AiPageClassificationService(
                        true,
                        "gemini-2.5-flash-lite",
                        new BigDecimal("0.60"),
                        2,
                        port,
                        pages,
                        spans,
                        classifications,
                        documentTypes,
                        transactions,
                        jdbc)
                .classifyUnknownPages(PACKAGE);

        assertThat(capturedRequest().pages())
                .extracting(PageTypeClassificationRequest.CandidatePage::pageId)
                .containsExactly(first, second);
    }

    @Test
    void the_taxonomy_travels_as_data_from_the_document_type_rows() {
        page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers();

        service().classifyUnknownPages(PACKAGE);

        assertThat(capturedRequest().taxonomy())
                .extracting(PageTypeClassificationRequest.TypeDescription::code)
                .containsExactly("PAYSTUB");
    }

    // ── fail closed, never throw ────────────────────────────────────────────

    @Test
    void the_disabled_stub_adapter_supersedes_nothing_and_throws_nothing() {
        page(0, AiClassificationGates.UNKNOWN, false);

        AiPageClassificationService.StageResult result =
                service(new StubPageTypeClassificationAdapter()).classifyUnknownPages(PACKAGE);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.DISABLED);
        assertThat(result.pagesTypedByModel()).isZero();
        verify(classifications, never()).supersedeCurrent(any(), any(), any());
    }

    @Test
    void a_provider_error_supersedes_nothing_and_throws_nothing() {
        page(0, AiClassificationGates.UNKNOWN, false);
        when(port.classify(any()))
                .thenReturn(
                        new PageTypeClassificationResult(
                                PageTypeClassificationStatus.ERROR, List.of(), AiTokenCounts.ZERO));

        AiPageClassificationService.StageResult result = service().classifyUnknownPages(PACKAGE);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.ERROR);
        verify(classifications, never()).save(any());
    }

    @Test
    void an_adapter_that_throws_despite_the_contract_still_leaves_every_page_as_it_was() {
        page(0, AiClassificationGates.UNKNOWN, false);
        when(port.classify(any())).thenThrow(new IllegalStateException("boom"));

        AiPageClassificationService.StageResult result = service().classifyUnknownPages(PACKAGE);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.ERROR);
        verify(classifications, never()).save(any());
    }

    @Test
    void the_flag_off_is_a_no_op_that_never_touches_a_repository() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        AiPageClassificationService disabled =
                new AiPageClassificationService(
                        false,
                        "gemini-2.5-flash-lite",
                        new BigDecimal("0.60"),
                        40,
                        port,
                        pages,
                        spans,
                        classifications,
                        documentTypes,
                        transactions,
                        jdbc);

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.classifyUnknownPages(PACKAGE).status())
                .isEqualTo(PageTypeClassificationStatus.DISABLED);
        verify(pages, never()).findByPackageIdOrderByPackagePageIndex(any());
    }

    // ── one answer per page ─────────────────────────────────────────────────

    @Test
    void a_second_proposal_about_the_same_page_never_retypes_it_a_second_time() {
        // Two answers for one page is last-write-wins with no unique index to stop it: retype #1
        // supersedes UNKNOWN, retype #2 then supersedes retype #1 — and #2's runner-up is read
        // from the LLM row #1 just wrote, which carries no `scores`. First proposal wins.
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(pageId, "PAYSTUB", new BigDecimal("0.91"), "EARNINGS STATEMENT"),
                new PageTypeProposal(pageId, "W2", new BigDecimal("0.99"), "EARNINGS STATEMENT"));

        AiPageClassificationService.StageResult result = service().classifyUnknownPages(PACKAGE);

        assertThat(result.pagesTypedByModel()).isEqualTo(1);
        assertThat(result.pagesRefusedByGate()).isEqualTo(1);
        assertThat(result.refusalsByReason())
                .containsEntry(AiClassificationGates.Refusal.DUPLICATE_PAGE, 1);
        verify(classifications, times(1)).supersedeCurrent(any(), any(), any());
        ArgumentCaptor<ClassificationResult> saved =
                ArgumentCaptor.forClass(ClassificationResult.class);
        verify(classifications, times(1)).save(saved.capture());
        assertThat(saved.getValue().getDocumentTypeCode()).isEqualTo("PAYSTUB");
    }

    @Test
    void the_first_proposal_wins_even_when_it_is_the_one_the_gates_refuse() {
        // Consume-once on the PAGE, not on acceptance: a model that answered twice about one page
        // is not trustworthy about that page, and letting a second answer through would make the
        // outcome depend on the order the model happened to emit its contradictions in.
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(pageId, "PAYSTUB", new BigDecimal("0.99"), "nowhere on page"),
                new PageTypeProposal(pageId, "W2", new BigDecimal("0.99"), "EARNINGS STATEMENT"));

        AiPageClassificationService.StageResult result = service().classifyUnknownPages(PACKAGE);

        assertThat(result.pagesTypedByModel()).isZero();
        verify(classifications, never()).save(any());
    }

    // ── a persistence failure costs one page, not the rest ──────────────────

    @Test
    void a_page_that_fails_to_persist_neither_abandons_the_rest_nor_is_reported_as_typed() {
        // One transient blip on page 1 of N must not discard the answers already committed for
        // the pages after it, nor report zero retypes when a page really was retyped: the model
        // was paid for the whole batch either way.
        UUID failing = page(0, AiClassificationGates.UNKNOWN, false);
        UUID surviving = page(1, AiClassificationGates.UNKNOWN, false);
        doThrow(new IllegalStateException("connection blip"))
                .when(classifications)
                .supersedeCurrent(ORG, ClassificationResult.SUBJECT_PAGE, failing);
        modelAnswers(
                new PageTypeProposal(failing, "PAYSTUB", new BigDecimal("0.91"), "EARNINGS STATEMENT"),
                new PageTypeProposal(
                        surviving, "PAYSTUB", new BigDecimal("0.91"), "EARNINGS STATEMENT"));

        AiPageClassificationService.StageResult result = service().classifyUnknownPages(PACKAGE);

        assertThat(result.status()).isEqualTo(PageTypeClassificationStatus.OK);
        assertThat(result.pagesConsidered()).isEqualTo(2);
        assertThat(result.pagesTypedByModel()).isEqualTo(1);
        assertThat(result.pagesFailedToPersist()).isEqualTo(1);
        // The page after the failure was still written — the whole point of a per-page transaction.
        verify(classifications, times(1)).save(any());
        verify(classifications)
                .supersedeCurrent(ORG, ClassificationResult.SUBJECT_PAGE, surviving);
    }

    // ── the refusal ledger the tuning loop reads ────────────────────────────

    @Test
    void refusals_are_counted_by_reason_so_which_gate_refused_is_answerable() {
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(pageId, "PAYSTUB", new BigDecimal("0.99"), "nowhere on page"),
                new PageTypeProposal(
                        UUID.randomUUID(), "PAYSTUB", new BigDecimal("0.99"), "EARNINGS STATEMENT"));

        AiPageClassificationService.StageResult result = service().classifyUnknownPages(PACKAGE);

        assertThat(result.pagesRefusedByGate()).isEqualTo(2);
        assertThat(result.refusalsByReason())
                .containsEntry(AiClassificationGates.Refusal.QUOTE_UNVERIFIED, 1)
                .containsEntry(AiClassificationGates.Refusal.PAGE_NOT_ELIGIBLE, 1)
                .doesNotContainKey(AiClassificationGates.Refusal.NONE);
    }

    @Test
    void a_type_outside_the_taxonomy_the_request_carried_is_refused_by_the_stage_itself() {
        // The allowlist cannot live only in the adapter: document_type_code has no foreign key,
        // so an adapter that forgot the check would write a current classification of a type no
        // document_type row defines.
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(
                        pageId, "INVENTED_TYPE", new BigDecimal("0.99"), "EARNINGS STATEMENT"));

        AiPageClassificationService.StageResult result = service().classifyUnknownPages(PACKAGE);

        assertThat(result.pagesTypedByModel()).isZero();
        assertThat(result.refusalsByReason())
                .containsEntry(AiClassificationGates.Refusal.TYPE_NOT_IN_TAXONOMY, 1);
        verify(classifications, never()).save(any());
    }

    // ── the ledger row means what every other ledger row means ──────────────

    @Test
    void the_ledger_row_names_the_provider_that_answered_not_the_evidence_source() {
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(pageId, "PAYSTUB", new BigDecimal("0.91"), "EARNINGS STATEMENT"));

        service().classifyUnknownPages(PACKAGE);

        // "AI" is the evidence-source marker; a per-provider cost rollup grouping on it would
        // never attribute a cent of this stage's spend to the model that answered.
        assertThat(ledgerArguments().get(LEDGER_PROVIDER)).isEqualTo(PROVIDER);
    }

    @Test
    void a_port_that_names_no_provider_degrades_the_way_the_app_ledger_degrades() {
        when(port.provider()).thenReturn(null);
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(pageId, "PAYSTUB", new BigDecimal("0.91"), "EARNINGS STATEMENT"));

        service().classifyUnknownPages(PACKAGE);

        assertThat(ledgerArguments().get(LEDGER_PROVIDER)).isEqualTo("unknown");
    }

    @Test
    void tokens_in_counts_cache_reads_and_writes_like_every_other_ledger_row() {
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        when(port.classify(any()))
                .thenReturn(
                        new PageTypeClassificationResult(
                                PageTypeClassificationStatus.OK,
                                List.of(
                                        new PageTypeProposal(
                                                pageId,
                                                "PAYSTUB",
                                                new BigDecimal("0.91"),
                                                "EARNINGS STATEMENT")),
                                new AiTokenCounts(120, 30, 7, 3)));

        service().classifyUnknownPages(PACKAGE);

        // Two rows in one column must not count different things: the app's ledger writes
        // input + cacheRead + cacheWrite, and the adapter does populate the cached count.
        assertThat(ledgerArguments().get(LEDGER_TOKENS_IN)).isEqualTo(130);
    }

    @Test
    void a_call_whose_every_proposal_is_refused_is_still_ledgered() {
        // Spend happens at the CALL, not at the retype: recording only what survived the gates
        // would hide the cost of exactly the packages the model is worst at.
        UUID pageId = page(0, AiClassificationGates.UNKNOWN, false);
        modelAnswers(
                new PageTypeProposal(pageId, "PAYSTUB", new BigDecimal("0.99"), "nowhere on page"));

        AiPageClassificationService.StageResult result = service().classifyUnknownPages(PACKAGE);

        assertThat(result.pagesTypedByModel()).isZero();
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }

    /** The varargs the ledger INSERT was called with, in the order the SQL binds them. */
    private List<Object> ledgerArguments() {
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), arguments.capture());
        return java.util.Arrays.asList(arguments.getValue());
    }
}
