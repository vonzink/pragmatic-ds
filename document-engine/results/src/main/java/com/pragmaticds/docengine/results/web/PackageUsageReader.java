package com.pragmaticds.docengine.results.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.domain.LogicalDocument;
import com.pragmaticds.docengine.classification.domain.LogicalDocumentPage;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentPageRepository;
import com.pragmaticds.docengine.classification.repo.LogicalDocumentRepository;
import com.pragmaticds.docengine.classification.repo.PackageRefRepository;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.orchestration.domain.StageStatus;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.TextLayer;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.results.web.PackageUsageView.AttemptsView;
import com.pragmaticds.docengine.results.web.PackageUsageView.CostProducerView;
import com.pragmaticds.docengine.results.web.PackageUsageView.DocumentUsageView;
import com.pragmaticds.docengine.results.web.PackageUsageView.ElapsedView;
import com.pragmaticds.docengine.results.web.PackageUsageView.ModelCostView;
import com.pragmaticds.docengine.results.web.PackageUsageView.ModelSpendView;
import com.pragmaticds.docengine.results.web.PackageUsageView.PageUsageView;
import com.pragmaticds.docengine.results.web.PackageUsageView.RetriedStageView;
import com.pragmaticds.docengine.results.web.PackageUsageView.StageUsageView;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Assembles {@link PackageUsageView} — the per-package cost-and-usage projection.
 *
 * <p><b>Why this lives in {@code :results} and not beside {@code DocumentFieldsReader}.</b> The
 * answer needs four modules at once: page counts and text layers from {@code :parsing}, document
 * membership from {@code :classification}, stage telemetry from {@code :orchestration}, and the
 * package guard. {@code :extraction} does not depend on {@code :orchestration}, and adding that
 * edge to serve a telemetry read would smear a module boundary that
 * {@code docs/ARCHITECTURE.md} §2.1 draws deliberately. {@code :results} already depends on all of
 * them, its stated purpose is the cross-cutting read lifecycle, and
 * {@code MachineResultSnapshotLoader} already reads this exact set of tables — so this reader adds
 * no dependency anywhere and creates no cycle.
 *
 * <p>Otherwise it follows {@code DocumentFieldsReader} exactly: the guard is here, once, and the
 * controller only resolves the tenant and delegates.
 *
 * <p>Tenancy: {@link #require} org-guards the package with
 * {@code findByIdAndOrgIdAndDeletedAtIsNull} — a cross-tenant or tombstoned id answers 404 exactly
 * like a nonexistent one. Everything below it travels through {@code @TenantId}-filtered derived
 * queries, EXCEPT the {@code ai_interpretation} rollup, which is raw SQL (that table has no entity
 * by design) and therefore carries a hand-written {@code org_id} guard.
 *
 * <p>This is a read-only projection over telemetry that already exists. It writes nothing, changes
 * no field output, and cannot perturb an extraction golden.
 */
@Service
public class PackageUsageReader {

    /** The engine itself — the only spender this database can observe. */
    static final String PRODUCER_ENGINE = "ENGINE";

    /** The analyzer in the sibling repository. Spends against its own store, not this one. */
    static final String PRODUCER_RAG_BRAIN = "RAG_BRAIN";

    private static final String ENGINE_REASON =
            "read from ai_interpretation in this database; zero rows means zero calls were made";
    private static final String RAG_BRAIN_REASON =
            "spends in another service and is not recorded here; becomes observed when it writes"
                    + " ai_interpretation rows for this package's subjects";

    /**
     * The pages the worker adapter actually sends to OCR. Mirrors
     * {@code WorkerParserAdapter.OCR_ELIGIBLE} — a count that used SCANNED alone would understate
     * the bill for every mixed page.
     */
    private static final List<TextLayer> OCR_ELIGIBLE = List.of(TextLayer.SCANNED, TextLayer.MIXED);

    /**
     * Every (provider, model) pair that spent against one of this package's subjects.
     *
     * <p>Raw SQL because {@code ai_interpretation} has no JPA entity and deliberately keeps none —
     * V8 created it TABLE-ONLY so Spec 4/5 add rules rather than migrations, and
     * {@code RlsCoverageIT} pins that. The subject join is a three-armed subselect rather than an
     * IN-list of ids, so a 26-page scanned return with hundreds of fields stays one round trip.
     *
     * <p>The {@code org_id} guard is written by hand and is load-bearing: raw SQL does not travel
     * through Hibernate's {@code @TenantId} filter.
     */
    private static final String MODEL_SPEND_SQL =
            """
            SELECT provider,
                   model,
                   COUNT(*)                       AS calls,
                   COALESCE(SUM(tokens_in),  0)   AS tokens_in,
                   COALESCE(SUM(tokens_out), 0)   AS tokens_out,
                   COALESCE(SUM(cost_usd),   0)   AS cost_usd
              FROM ai_interpretation
             WHERE org_id = ?
               AND ( (subject_type = 'PAGE'
                      AND subject_id IN (SELECT id FROM page
                                          WHERE package_id = ? AND org_id = ?))
                  OR (subject_type = 'LOGICAL_DOCUMENT'
                      AND subject_id IN (SELECT id FROM logical_document
                                          WHERE package_id = ? AND org_id = ?))
                  OR (subject_type = 'EXTRACTED_FIELD'
                      AND subject_id IN (SELECT f.id FROM extracted_field f
                                           JOIN logical_document d
                                             ON d.id = f.logical_document_id
                                          WHERE d.package_id = ? AND d.org_id = ?)) )
             GROUP BY provider, model
             ORDER BY provider, model
            """;

    private final PackageRefRepository packages;
    private final PageRepository pages;
    private final LogicalDocumentRepository documents;
    private final LogicalDocumentPageRepository links;
    private final ProcessingJobRepository jobs;
    private final ProcessingStageRepository stages;
    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    public PackageUsageReader(
            PackageRefRepository packages,
            PageRepository pages,
            LogicalDocumentRepository documents,
            LogicalDocumentPageRepository links,
            ProcessingJobRepository jobs,
            ProcessingStageRepository stages,
            JdbcTemplate jdbc) {
        this.packages = packages;
        this.pages = pages;
        this.documents = documents;
        this.links = links;
        this.jobs = jobs;
        this.stages = stages;
        this.jdbc = jdbc;
    }

    /**
     * The readable package behind an id, or an opaque 404. A tombstoned package is unreadable —
     * its usage must 404 like a nonexistent one (soft-delete read-exclusion).
     */
    public UUID require(UUID packageId) {
        UUID orgId = TenantContext.require();
        packages
                .findByIdAndOrgIdAndDeletedAtIsNull(packageId, orgId)
                .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        return orgId;
    }

    /** The full usage projection for an already-guarded package. */
    public PackageUsageView usageOf(UUID packageId, UUID orgId) {
        List<Page> packagePages = pages.findByPackageIdOrderByPackagePageIndex(packageId);
        Map<UUID, TextLayer> layerByPage =
                packagePages.stream()
                        .collect(Collectors.toMap(Page::getId, Page::getTextLayer));

        List<LogicalDocument> packageDocuments = documents.findByPackageIdOrderByOrdinal(packageId);
        Map<UUID, List<LogicalDocumentPage>> linksByDocument =
                packageDocuments.isEmpty()
                        ? Map.of()
                        : links
                                .findByLogicalDocumentIdIn(
                                        packageDocuments.stream().map(LogicalDocument::getId).toList())
                                .stream()
                                .collect(Collectors.groupingBy(LogicalDocumentPage::getLogicalDocumentId));
        long assignedPages =
                linksByDocument.values().stream().mapToLong(List::size).sum();

        List<DocumentUsageView> documentViews =
                packageDocuments.stream()
                        .map(
                                document ->
                                        documentUsage(
                                                document,
                                                linksByDocument.getOrDefault(
                                                        document.getId(), List.of()),
                                                layerByPage))
                        .toList();

        Optional<ProcessingJob> job = jobs.findByPackageIdAndOrgId(packageId, orgId);
        List<ProcessingStage> stageRows =
                job.map(row -> stages.findByJobIdOrderByCreatedAtAsc(row.getId()))
                        .orElseGet(List::of)
                        .stream()
                        .sorted(pipelineOrder())
                        .toList();

        return new PackageUsageView(
                packageId,
                pageUsage(packagePages.stream().map(Page::getTextLayer).toList()),
                documentViews,
                packagePages.size() - assignedPages,
                elapsed(job, stageRows),
                attempts(job, stageRows),
                modelCost(packageId, orgId));
    }

    // ── pages ───────────────────────────────────────────────────────────────

    private static PageUsageView pageUsage(List<TextLayer> layers) {
        Map<String, Long> byTextLayer = emptyLayerCounts();
        for (TextLayer layer : layers) {
            byTextLayer.merge(layer.name(), 1L, Long::sum);
        }
        return new PageUsageView(layers.size(), ocrCount(byTextLayer), byTextLayer);
    }

    private static DocumentUsageView documentUsage(
            LogicalDocument document,
            List<LogicalDocumentPage> documentLinks,
            Map<UUID, TextLayer> layerByPage) {
        Map<String, Long> byTextLayer = emptyLayerCounts();
        for (LogicalDocumentPage link : documentLinks) {
            TextLayer layer = layerByPage.get(link.getPageId());
            if (layer != null) {
                byTextLayer.merge(layer.name(), 1L, Long::sum);
            }
        }
        return new DocumentUsageView(
                document.getId(),
                document.getOrdinal(),
                document.getDocumentTypeCode(),
                documentLinks.size(),
                ocrCount(byTextLayer),
                byTextLayer);
    }

    /**
     * All four {@code TextLayer} values pre-seeded to zero. A written-down zero is a measurement;
     * an omitted key is a question the UI would have to answer with a guess.
     */
    private static Map<String, Long> emptyLayerCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TextLayer layer : TextLayer.values()) {
            counts.put(layer.name(), 0L);
        }
        return counts;
    }

    private static long ocrCount(Map<String, Long> byTextLayer) {
        return OCR_ELIGIBLE.stream()
                .mapToLong(layer -> byTextLayer.getOrDefault(layer.name(), 0L))
                .sum();
    }

    // ── elapsed ─────────────────────────────────────────────────────────────

    private ElapsedView elapsed(Optional<ProcessingJob> job, List<ProcessingStage> stageRows) {
        long stageElapsed =
                stageRows.stream()
                        .map(ProcessingStage::getDurationMs)
                        .filter(java.util.Objects::nonNull)
                        .mapToLong(Long::longValue)
                        .sum();
        long failedElapsed =
                stageRows.stream()
                        .filter(row -> row.getStatus() == StageStatus.FAILED)
                        .map(ProcessingStage::getDurationMs)
                        .filter(java.util.Objects::nonNull)
                        .mapToLong(Long::longValue)
                        .sum();
        Long wallClock =
                job.filter(row -> row.getStartedAt() != null && row.getFinishedAt() != null)
                        .map(row -> Duration.between(row.getStartedAt(), row.getFinishedAt()).toMillis())
                        .orElse(null);
        return new ElapsedView(
                // Constants, not computed: the scope of this number is a property of the schema
                // (a stage row is keyed by JOB), not of this package's data.
                "PACKAGE",
                false,
                wallClock,
                stageElapsed,
                failedElapsed,
                stageRows.stream().map(this::stageUsage).toList());
    }

    private StageUsageView stageUsage(ProcessingStage row) {
        return new StageUsageView(
                row.getStage().name(),
                row.getStatus().name(),
                row.getAttempt(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getDurationMs(),
                row.getSkipReason(),
                row.getErrorCode() == null ? null : row.getErrorCode().name(),
                row.getWorkerVersion(),
                parserVersions(row.getParserVersions()));
    }

    /** {@code parser_versions} is stored as a serialized-JSON String; the wire wants the object. */
    private JsonNode parserVersions(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Telemetry must never be the thing that breaks a read. An unparseable pin is
            // reported as absent rather than as a 500 over the whole usage payload.
            return null;
        }
    }

    /**
     * Pipeline position, then attempt. NOT {@code created_at}: that column defaults to
     * {@code now()}, which Postgres holds constant across a transaction, so stages written
     * together share a timestamp and ordering by it is a coin flip.
     */
    private static Comparator<ProcessingStage> pipelineOrder() {
        return Comparator.comparingInt((ProcessingStage row) -> row.getStage().ordinal())
                .thenComparingInt(ProcessingStage::getAttempt);
    }

    // ── attempts ────────────────────────────────────────────────────────────

    private static AttemptsView attempts(
            Optional<ProcessingJob> job, List<ProcessingStage> stageRows) {
        Map<ProcessingStatus, List<ProcessingStage>> byStage =
                stageRows.stream()
                        .collect(
                                Collectors.groupingBy(
                                        ProcessingStage::getStage,
                                        LinkedHashMap::new,
                                        Collectors.toList()));
        List<RetriedStageView> retries = new ArrayList<>();
        for (Map.Entry<ProcessingStatus, List<ProcessingStage>> entry : byStage.entrySet()) {
            int highestAttempt =
                    entry.getValue().stream().mapToInt(ProcessingStage::getAttempt).max().orElse(1);
            if (highestAttempt <= 1) {
                continue;
            }
            retries.add(
                    new RetriedStageView(
                            entry.getKey().name(), highestAttempt, lastErrorCode(entry.getValue())));
        }
        return new AttemptsView(
                job.map(ProcessingJob::getAttempt).orElse(null),
                job.map(row -> row.getStatus().name()).orElse(null),
                job.map(ProcessingJob::getParseGeneration).orElse(null),
                stageRows.size(),
                stageRows.stream().filter(row -> row.getAttempt() > 1).count(),
                stageRows.stream().filter(row -> row.getStatus() == StageStatus.FAILED).count(),
                List.copyOf(retries));
    }

    /** The reason the retries happened: the newest non-null code, by attempt. */
    private static String lastErrorCode(List<ProcessingStage> attemptRows) {
        return attemptRows.stream()
                .filter(row -> row.getErrorCode() != null)
                .max(Comparator.comparingInt(ProcessingStage::getAttempt))
                .map(row -> row.getErrorCode().name())
                .orElse(null);
    }

    // ── model cost ──────────────────────────────────────────────────────────

    /**
     * The money arm. Today every number here is zero, and the {@code producers} list says which
     * spenders that zero covers — a total with an unstated scope invites the reader to assume it
     * is complete.
     */
    private ModelCostView modelCost(UUID packageId, UUID orgId) {
        List<ModelSpendView> byModel =
                jdbc.query(
                        MODEL_SPEND_SQL,
                        (rs, rowNum) ->
                                new ModelSpendView(
                                        // Every row in this table was written by the engine's own
                                        // pipeline; a second producer would arrive with its own
                                        // provider and its own entry in `producers`.
                                        PRODUCER_ENGINE,
                                        rs.getString("provider"),
                                        rs.getString("model"),
                                        rs.getLong("calls"),
                                        rs.getLong("tokens_in"),
                                        rs.getLong("tokens_out"),
                                        money(rs.getBigDecimal("cost_usd"))),
                        orgId,
                        packageId,
                        orgId,
                        packageId,
                        orgId,
                        packageId,
                        orgId);

        return new ModelCostView(
                byModel.stream().mapToLong(ModelSpendView::calls).sum(),
                byModel.stream().mapToLong(ModelSpendView::inputTokens).sum(),
                byModel.stream().mapToLong(ModelSpendView::outputTokens).sum(),
                byModel.stream()
                        .map(ModelSpendView::costUsd)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(6, java.math.RoundingMode.HALF_UP),
                "USD",
                byModel,
                List.of(
                        new CostProducerView(PRODUCER_ENGINE, true, ENGINE_REASON),
                        new CostProducerView(PRODUCER_RAG_BRAIN, false, RAG_BRAIN_REASON)));
    }

    /** The ledger's own scale. Null cost on a recorded call reads as zero spend, not as absent. */
    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(6, java.math.RoundingMode.HALF_UP);
    }
}
