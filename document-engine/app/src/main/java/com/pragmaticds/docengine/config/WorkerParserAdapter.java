package com.pragmaticds.docengine.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pragmaticds.docengine.ai.AiExtractionStageService;
import com.pragmaticds.docengine.classification.PackageSplitter;
import com.pragmaticds.docengine.classification.PageClassifier;
import com.pragmaticds.docengine.classification.ai.AiPageClassificationService;
import com.pragmaticds.docengine.classification.boundary.BoundaryExtractionStageService;
import com.pragmaticds.docengine.classification.domain.ClassificationResult;
import com.pragmaticds.docengine.extraction.FieldExtractionService;
import com.pragmaticds.docengine.ingestion.domain.SourceFile;
import com.pragmaticds.docengine.ingestion.repo.SourceFileRepository;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.parsing.client.LayoutPage;
import com.pragmaticds.docengine.parsing.client.LayoutRequest;
import com.pragmaticds.docengine.parsing.client.LayoutRequestPage;
import com.pragmaticds.docengine.parsing.client.LayoutRequestSpan;
import com.pragmaticds.docengine.parsing.client.LayoutResult;
import com.pragmaticds.docengine.parsing.client.OcrRequest;
import com.pragmaticds.docengine.parsing.client.OcrResult;
import com.pragmaticds.docengine.parsing.client.OcrSpan;
import com.pragmaticds.docengine.parsing.client.RenderResult;
import com.pragmaticds.docengine.parsing.client.TextPage;
import com.pragmaticds.docengine.parsing.client.TextResult;
import com.pragmaticds.docengine.parsing.client.WireBox;
import com.pragmaticds.docengine.parsing.client.WorkerBlock;
import com.pragmaticds.docengine.parsing.client.WorkerCallException;
import com.pragmaticds.docengine.parsing.client.WorkerClient;
import com.pragmaticds.docengine.parsing.domain.Page;
import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.domain.TextLayer;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.PageRepository;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.parsing.service.LayoutElementService;
import com.pragmaticds.docengine.parsing.service.NativeOverlap;
import com.pragmaticds.docengine.parsing.service.PageService;
import com.pragmaticds.docengine.parsing.service.ParserOutputService;
import com.pragmaticds.docengine.parsing.service.TextSpanService;
import com.pragmaticds.docengine.parsing.support.Digests;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The Phase 2 {@link ParserPort} adapter: executes RENDERING / TEXT_EXTRACTION / OCR_PROCESSING
 * against the Python worker (docs/WORKER_CONTRACT.md, Java-side stage mapping table) and persists
 * through the parsing services. Lives in the app layer — the one place that may see ingestion
 * (source files), orchestration (the port), and parsing (services) at once, same seam pattern as
 * {@link ProcessingSeamConfig}.
 *
 * <p>Selected by {@code docengine.processing.adapter=worker}; the stub keeps
 * {@code matchIfMissing}, so tests and the no-worker dev loop are untouched.
 *
 * <p>Idempotency under retry: every stage attempt commits — including failed ones — so each stage
 * clears its own partial output before re-persisting (pages before RENDERING, NATIVE spans before
 * TEXT_EXTRACTION, OCR spans before OCR_PROCESSING). parser_output is append-only by design and is
 * NOT cleared: raw payloads are the audit trail of what each attempt actually said.
 *
 * <p>PARSING (Phase 3; pixel path since Spec 3) calls {@code /v1/layout} once per source file —
 * spans from the DB ride in on the request (the worker stays stateless) and the ORIGINAL PDF
 * always rides along: pdfplumber rects/lines confirm rulings on native ink, and the worker
 * renders requested pages internally (200 DPI) for the checkbox/signature detectors — persists
 * the element trees and span links, then computes the page signals: span-content
 * {@code content_hash} and package-wide duplicate flags. Blank signals
 * ({@code is_blank}/{@code blank_score}) are persisted by TEXT_EXTRACTION, which owns the
 * /v1/text response they arrive on.
 *
 * <p>CLASSIFYING and SPLITTING (Phase 4) never call the worker at all: classification is Java
 * against PERSISTED spans (mortgage domain rules live in :classification, never in Python —
 * ARCHITECTURE.md 2.1). CLASSIFYING runs {@code PageClassifier} over every non-blank,
 * non-duplicate page; SPLITTING runs {@code PackageSplitter}. EXTRACTING (Phase 5) runs
 * {@code FieldExtractionService} the same way — pure Java over persisted spans and layout,
 * documents without an active schema skipped, never failed.
 *
 * <p>Errors: {@link WorkerCallException} becomes a failed outcome carrying the stable code —
 * WORKER_UNAVAILABLE / WORKER_TIMEOUT for transport, the worker's own code otherwise. Detail maps
 * carry names and counters only, NEVER document content (ARCHITECTURE.md 10).
 */
@Component
@ConditionalOnProperty(name = "docengine.processing.adapter", havingValue = "worker")
public class WorkerParserAdapter implements ParserPort {

    private static final Logger log = LoggerFactory.getLogger(WorkerParserAdapter.class);

    /** Contract: render all pages at 200 DPI; 300 is the reprocess escalation (Phase 6). */
    private static final int RENDER_DPI = 200;

    private static final String NOT_YET_IMPLEMENTED_NOTE = "{\"phase2\":\"not-yet-implemented\"}";

    /** Pages whose text layer sends them to OCR. NATIVE and NONE never go. */
    private static final Set<TextLayer> OCR_ELIGIBLE = EnumSet.of(TextLayer.SCANNED, TextLayer.MIXED);

    private final WorkerClient worker;
    private final SourceFileRepository sourceFiles;
    private final PageRepository pages;
    private final PageService pageService;
    private final TextSpanService textSpanService;
    private final LayoutElementService layoutElementService;
    private final ParserOutputService parserOutputService;
    private final BlobStoragePort storage;
    private final TextSpanRepository textSpans;
    private final PageClassifier pageClassifier;
    private final PackageSplitter packageSplitter;
    private final FieldExtractionService fieldExtractionService;
    private final AiExtractionStageService aiExtractionStageService;
    private final BoundaryExtractionStageService boundaryExtractionStageService;
    private final AiPageClassificationService aiPageClassificationService;

    /**
     * Pages per /v1/render call. Whole-package renders buffered every PNG in memory on
     * both sides (Phase 2 review): a 500-page package at 200 DPI is gigabytes. Batching
     * bounds it at batch-size pages per request; ingestion's PdfProbe page count drives
     * the chunking (images have no count yet — they render whole, they are one page).
     */
    private final int renderBatchSize;
    private final ObjectMapper mapper;

    public WorkerParserAdapter(
            WorkerClient worker,
            SourceFileRepository sourceFiles,
            PageRepository pages,
            PageService pageService,
            TextSpanService textSpanService,
            LayoutElementService layoutElementService,
            ParserOutputService parserOutputService,
            BlobStoragePort storage,
            TextSpanRepository textSpans,
            PageClassifier pageClassifier,
            PackageSplitter packageSplitter,
            FieldExtractionService fieldExtractionService,
            AiExtractionStageService aiExtractionStageService,
            BoundaryExtractionStageService boundaryExtractionStageService,
            AiPageClassificationService aiPageClassificationService,
            @org.springframework.beans.factory.annotation.Value(
                            "${docengine.worker.render-batch-size:8}")
                    int renderBatchSize) {
        this.renderBatchSize = Math.max(1, renderBatchSize);
        this.worker = worker;
        this.sourceFiles = sourceFiles;
        this.pages = pages;
        this.pageService = pageService;
        this.textSpanService = textSpanService;
        this.layoutElementService = layoutElementService;
        this.parserOutputService = parserOutputService;
        this.storage = storage;
        this.textSpans = textSpans;
        this.pageClassifier = pageClassifier;
        this.packageSplitter = packageSplitter;
        this.fieldExtractionService = fieldExtractionService;
        this.aiExtractionStageService = aiExtractionStageService;
        this.boundaryExtractionStageService = boundaryExtractionStageService;
        this.aiPageClassificationService = aiPageClassificationService;
        // BigDecimal end to end: uncovered regions round-trip page row -> /v1/ocr verbatim.
        this.mapper =
                JsonMapper.builder()
                        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .build();
    }

    /**
     * A real parse of the bytes, so it has an honest identity to contribute. Only the ADAPTER is
     * named here — the worker's own version and pinned libraries already enter the fingerprint
     * through the {@code /version} probe, and naming the base URL as well would break reuse across
     * an ordinary redeploy without describing any behavior the probe misses.
     */
    @Override
    public java.util.Optional<String> behaviorIdentity() {
        // ANY enabled model stage makes a run non-describable: a fingerprint's whole job is to
        // vouch that a reuse would reproduce the parse, and a model call cannot promise that.
        // The page-classification fallback belongs in this OR for a reason worth naming: it is
        // the stage that can turn an UNKNOWN page into a typed one, so a parse the model enriched
        // would otherwise be served by parse-once reuse to a later upload that ran without it —
        // the same bytes coming back better-typed than the pipeline can currently reproduce.
        return aiExtractionStageService.enabled()
                        || boundaryExtractionStageService.enabled()
                        || aiPageClassificationService.enabled()
                ? java.util.Optional.empty()
                : java.util.Optional.of("worker");
    }

    @Override
    public StageOutcome run(StageRequest request) {
        try {
            return switch (request.stage()) {
                case RENDERING -> renderStage(request);
                case TEXT_EXTRACTION -> textStage(request);
                case OCR_PROCESSING -> ocrStage(request);
                case PARSING -> parsingStage(request);
                case CLASSIFYING -> classifyingStage(request);
                case SPLITTING -> splittingStage(request);
                case BOUNDARY_EXTRACTION -> boundaryExtractionStage(request);
                case EXTRACTING -> extractingStage(request);
                case AI_EXTRACTION -> aiExtractionStage(request);
                default -> notYetImplemented(request);
            };
        } catch (DomainException e) {
            // Classification failures (NO_RULE_PACK, unparseable pack → INTERNAL) carry a stable
            // code and non-sensitive params by construction — surface them as the stage error.
            log.warn(
                    "stage domain failure job={} stage={} attempt={} code={}",
                    request.jobId(),
                    request.stage(),
                    request.attempt(),
                    e.code());
            Map<String, Object> detail = new HashMap<>(e.params());
            detail.put("stage", request.stage().name());
            detail.put("attempt", request.attempt());
            return new StageOutcome(false, null, e.code(), Map.copyOf(detail));
        } catch (WorkerCallException e) {
            log.warn(
                    "worker call failed job={} stage={} attempt={} code={} http={}",
                    request.jobId(),
                    request.stage(),
                    request.attempt(),
                    e.errorCode(),
                    e.httpStatus());
            // Stable codes and counters only — never body text or document content.
            Map<String, Object> detail =
                    Map.of(
                            "stage", request.stage().name(),
                            "attempt", request.attempt(),
                            "httpStatus", e.httpStatus(),
                            "workerCode", e.workerCode() == null ? "NONE" : e.workerCode());
            if (e.errorCode() == ErrorCode.WORKER_TIMEOUT) {
                // A call that ran out its whole timeout is the document's size or a stuck worker,
                // not a blip: an identical attempt waits the same time for the same answer
                // (2026-09-16: three 10-minute attempts per stage on an 83-page scan). Unavailable
                // — the worker restarting — stays retryable.
                return StageOutcome.nonRetryableFailure(e.errorCode(), detail);
            }
            return new StageOutcome(false, null, e.errorCode(), detail);
        }
    }

    // ── RENDERING ───────────────────────────────────────────────────────────

    private StageOutcome renderStage(StageRequest request) {
        List<SourceFile> files = sourceFiles.findByPackageIdOrderByOrdinal(request.packageId());
        // Retry idempotency: a failed attempt's partial pages committed with its FAILED row.
        pageService.deleteAllForPackage(request.packageId());

        StringBuilder contentHashes = new StringBuilder();
        WorkerBlock lastWorker = null;
        int packagePageIndex = 0;
        int pageCount = 0;
        for (SourceFile file : files) {
            byte[] pdf = storage.get(file.getStorageKeyOriginal());
            for (List<Integer> batch : renderBatches(file.getPageCount(), renderBatchSize)) {
                RenderResult render = worker.render(pdf, batch, RENDER_DPI);
                List<Page> persisted =
                        pageService.persistRenderedPages(
                                request.packageId(), file.getId(), packagePageIndex, render);
                packagePageIndex += persisted.size();
                pageCount += persisted.size();
                persisted.forEach(page -> contentHashes.append(page.getContentHash()));
                lastWorker = render.worker();
            }
        }
        log.info(
                "rendered package={} files={} pages={}",
                request.packageId(),
                files.size(),
                pageCount);
        // Stage digest = sha256 over the page content hashes in package order: replaying the
        // stage on identical inputs is detectable without re-reading a single blob.
        return success(Digests.sha256Hex(contentHashes.toString()), lastWorker);
    }

    /**
     * Page-index batches for one file: [[0..b-1], [b..2b-1], ...]. An unknown page
     * count (images at Phase 2 ingestion) yields one whole-file call — a single
     * image is a single page, exactly the case batching does not need.
     */
    public static List<List<Integer>> renderBatches(Integer filePageCount, int batchSize) {
        if (filePageCount == null || filePageCount <= 0) {
            return List.of(List.of());
        }
        List<List<Integer>> batches = new java.util.ArrayList<>();
        for (int start = 0; start < filePageCount; start += batchSize) {
            batches.add(
                    java.util.stream.IntStream.range(
                                    start, Math.min(start + batchSize, filePageCount))
                            .boxed()
                            .toList());
        }
        return batches;
    }

    // ── TEXT_EXTRACTION ─────────────────────────────────────────────────────

    private StageOutcome textStage(StageRequest request) {
        List<SourceFile> files = sourceFiles.findByPackageIdOrderByOrdinal(request.packageId());
        textSpanService.deleteBySource(request.packageId(), SpanSource.NATIVE);

        StringBuilder rawDigests = new StringBuilder();
        WorkerBlock lastWorker = null;
        for (SourceFile file : files) {
            byte[] pdf = storage.get(file.getStorageKeyOriginal());
            TextResult text = worker.text(pdf);
            for (TextPage textPage : text.pages()) {
                Page page =
                        pages.findBySourceFileIdAndPageIndex(file.getId(), textPage.pageIndex())
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "text page without rendered page row"));
                page.applyTextVerdict(
                        TextLayer.valueOf(textPage.verdict()),
                        toJsonOrNull(textPage.uncoveredRegions()),
                        textPage.inkFraction());
                if (!textPage.spans().isEmpty()) {
                    textSpanService.persistNativeSpans(page, textPage.spans());
                }
                pages.save(page);
                // One line per page, numbers only (never text): the verdict is what routes a
                // page to OCR, and a MIXED verdict on a native-looking page was undiagnosable
                // from the stage summary alone.
                log.info(
                        "text verdict package={} file={} page={} verdict={} words={}"
                                + " uncoveredRegions={} inkFraction={}",
                        request.packageId(),
                        file.getId(),
                        textPage.pageIndex(),
                        textPage.verdict(),
                        textPage.spans().size(),
                        textPage.uncoveredRegions() == null ? 0 : textPage.uncoveredRegions().size(),
                        textPage.inkFraction());
            }
            byte[] raw = text.rawJson().getBytes(StandardCharsets.UTF_8);
            parserOutputService.persist(
                    file.getId(),
                    null,
                    request.stage().name(),
                    "pdfplumber",
                    text.worker().libraryOr("pdfplumber", text.worker().version()),
                    raw);
            rawDigests.append(Digests.sha256Hex(raw));
            lastWorker = text.worker();
        }
        return success(Digests.sha256Hex(rawDigests.toString()), lastWorker);
    }

    /** The job's wall-clock budget ran out mid-stage. Counts only — never content. */
    private static StageOutcome budgetExhausted(
            StageRequest request, String unit, int done, int total) {
        log.warn(
                "job time budget exhausted job={} stage={} {}s done={} of {}",
                request.jobId(),
                request.stage(),
                unit,
                done,
                total);
        return StageOutcome.nonRetryableFailure(
                ErrorCode.JOB_TIME_BUDGET_EXCEEDED,
                Map.of(
                        "stage", request.stage().name(),
                        "checkpoint", "between " + unit + "s",
                        "done", done,
                        "total", total));
    }

    // ── OCR_PROCESSING ──────────────────────────────────────────────────────

    private StageOutcome ocrStage(StageRequest request) {
        List<Page> eligible =
                pages.findByPackageIdOrderByPackagePageIndex(request.packageId()).stream()
                        .filter(page -> OCR_ELIGIBLE.contains(page.getTextLayer()))
                        .toList();
        textSpanService.deleteBySource(request.packageId(), SpanSource.OCR);

        StringBuilder rawDigests = new StringBuilder();
        WorkerBlock lastWorker = null;
        int pagesDone = 0;
        for (Page page : eligible) {
            // One OCR call per page, each within its own timeout, so a 75-page scan is legal for
            // over an hour unless the JOB's budget is checked between pages (2026-09-16).
            if (request.pastDeadline(Instant.now())) {
                return budgetExhausted(request, "OCR page", pagesDone, eligible.size());
            }
            if (request.cancelled()) {
                return StageOutcome.nonRetryableFailure(ErrorCode.JOB_CANCELLED,
                        Map.of("stage", request.stage().name(), "checkpoint", "between OCR pages", "done", pagesDone, "total", eligible.size()));
            }
            byte[] png = storage.get(page.getRenderStorageKey());
            OcrResult ocr =
                    worker.ocr(
                            png,
                            new OcrRequest(
                                    page.getPageIndex(),
                                    page.getWidthPt(),
                                    page.getHeightPt(),
                                    page.getRenderDpi() == null ? RENDER_DPI : page.getRenderDpi(),
                                    page.getRotation(),
                                    regionsOf(page)));

            // Engine NONE = neither engine produced usable text. Not a stage failure. On a
            // SCANNED page that is the whole page unread, so it is flagged OCR_LOW_CONFIDENCE
            // (the dedicated review flag, DATA_MODEL page.ocr columns) and the job continues to
            // HUMAN_REVIEW_REQUIRED, which review reads. On a MIXED page only the image
            // remainders were OCR'd and the native text layer — already judged meaningful, or
            // the page would be SCANNED — is what the reader trusts; a logo or chart that
            // yields no words is not a low-confidence page. The tripped gate stays on record.
            boolean unreadPage =
                    "NONE".equals(ocr.engine()) && page.getTextLayer() != TextLayer.MIXED;
            page.applyOcrOutcome(
                    ocr.detectedRotation(),
                    ocr.osdConfidence(),
                    ocr.engine(),
                    unreadPage ? "OCR_LOW_CONFIDENCE" : ocr.fallbackReason(),
                    ocr.confidenceMedian());
            // A MIXED page's region can be a whole form image (worker text.py _print_images),
            // whose OCR re-reads the native values drawn over it; the native reading wins.
            List<OcrSpan> ocrSpans =
                    page.getTextLayer() == TextLayer.MIXED
                            ? NativeOverlap.withoutNativeDuplicates(
                                    ocr.spans(),
                                    textSpans.findByPageIdOrderByOrdinal(page.getId()).stream()
                                            .filter(s -> s.getSource() == SpanSource.NATIVE)
                                            .toList())
                            : ocr.spans();
            if (!ocrSpans.isEmpty()) {
                textSpanService.persistOcrSpans(page, ocrSpans);
            }
            pages.save(page);

            // BOTH engines' raws persist whenever they exist, regardless of who won.
            if (ocr.rawRapidocrJson() != null) {
                parserOutputService.persist(
                        page.getSourceFileId(),
                        page.getId(),
                        request.stage().name(),
                        "rapidocr",
                        ocr.worker().libraryOr("rapidocr-onnxruntime", ocr.worker().version()),
                        ocr.rawRapidocrJson().getBytes(StandardCharsets.UTF_8));
            }
            if (ocr.rawTesseractJson() != null) {
                parserOutputService.persist(
                        page.getSourceFileId(),
                        page.getId(),
                        request.stage().name(),
                        "tesseract",
                        ocr.worker().libraryOr("pytesseract", ocr.worker().version()),
                        ocr.rawTesseractJson().getBytes(StandardCharsets.UTF_8));
            }
            rawDigests.append(Digests.sha256Hex(ocr.rawJson()));
            lastWorker = ocr.worker();
            pagesDone++;
        }
        log.info(
                "ocr package={} pages={} mixed={} scanned={}",
                request.packageId(),
                eligible.size(),
                eligible.stream().filter(p -> p.getTextLayer() == TextLayer.MIXED).count(),
                eligible.stream().filter(p -> p.getTextLayer() == TextLayer.SCANNED).count());
        return success(Digests.sha256Hex(rawDigests.toString()), lastWorker);
    }

    // ── PARSING ─────────────────────────────────────────────────────────────

    private StageOutcome parsingStage(StageRequest request) {
        List<SourceFile> files = sourceFiles.findByPackageIdOrderByOrdinal(request.packageId());
        List<Page> packagePages =
                pages.findByPackageIdOrderByPackagePageIndex(request.packageId());
        // Retry idempotency: a failed attempt's partial elements committed with its FAILED row.
        layoutElementService.deleteAllForPackage(request.packageId());
        // The coverage declaration is stage-owned state, cleared exactly like the element trees:
        // a page missing from THIS attempt's layout responses must read "unknown", not keep a
        // previous attempt's claim (full-capture P2.4; the honesty marker WORKER_CONTRACT.md
        // defines — "an empty result must stay distinguishable from 'did not look'").
        packagePages.forEach(page -> page.applyLayoutCoverage(null));

        Map<UUID, List<TextSpan>> requestSpansByPage = new HashMap<>();
        StringBuilder rawDigests = new StringBuilder();
        WorkerBlock lastWorker = null;
        for (SourceFile file : files) {
            List<Page> filePages =
                    packagePages.stream()
                            .filter(page -> page.getSourceFileId().equals(file.getId()))
                            .sorted(Comparator.comparingInt(Page::getPageIndex))
                            .toList();
            List<LayoutRequestPage> requestPages = new ArrayList<>(filePages.size());
            for (Page page : filePages) {
                List<TextSpan> requestSpans = layoutElementService.requestSpansFor(page.getId());
                requestSpansByPage.put(page.getId(), requestSpans);
                List<LayoutRequestSpan> spans = new ArrayList<>(requestSpans.size());
                for (int ordinal = 0; ordinal < requestSpans.size(); ordinal++) {
                    TextSpan span = requestSpans.get(ordinal);
                    spans.add(
                            new LayoutRequestSpan(
                                    ordinal,
                                    span.getText(),
                                    span.getX(),
                                    span.getY(),
                                    span.getWidth(),
                                    span.getHeight(),
                                    span.getFontSize(),
                                    span.getFontName()));
                }
                requestPages.add(
                        new LayoutRequestPage(
                                page.getPageIndex(), page.getWidthPt(), page.getHeightPt(), spans));
            }

            // Spec 3 pixel path: the ORIGINAL PDF always rides along — the worker's
            // checkbox/signature detectors need pixels and rendering happens worker-side
            // (200 DPI). Rulings remain a worker-side concern: pdfplumber rects/lines
            // still only confirm tables on native ink.
            if (request.pastDeadline(Instant.now())) {
                return budgetExhausted(request, "layout file", files.indexOf(file), files.size());
            }
            if (request.cancelled()) {
                return StageOutcome.nonRetryableFailure(ErrorCode.JOB_CANCELLED,
                        Map.of("stage", request.stage().name(), "checkpoint", "between layout files", "done", files.indexOf(file), "total", files.size()));
            }
            byte[] pdf = storage.get(file.getStorageKeyOriginal());
            LayoutResult layout = worker.layout(pdf, new LayoutRequest(requestPages));

            for (LayoutPage layoutPage : layout.pages()) {
                Page page =
                        filePages.stream()
                                .filter(p -> p.getPageIndex() == layoutPage.pageIndex())
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "layout page without page row"));
                if (!layoutPage.elements().isEmpty()) {
                    layoutElementService.persistPageElements(
                            page, layoutPage.elements(), requestSpansByPage.get(page.getId()));
                }
                // Persist the worker's per-page notImplemented declaration VERBATIM — parsed by
                // WorkerClient since Phase 3 and dropped until now (design §13.1). Recorded even
                // when the element list is empty: that is precisely the page where "looked and
                // found none" versus "did not look" matters. The page rows save in the signals
                // loop below.
                page.applyLayoutCoverage(coverageJson(layoutPage.notImplemented()));
            }

            byte[] raw = layout.rawJson().getBytes(StandardCharsets.UTF_8);
            parserOutputService.persist(
                    file.getId(),
                    null,
                    request.stage().name(),
                    "clustering",
                    layout.worker().version() == null ? "unknown" : layout.worker().version(),
                    raw);
            rawDigests.append(Digests.sha256Hex(raw));
            lastWorker = layout.worker();
        }

        // ── page signals: span-content hash, then package-wide duplicate resolution ──
        Map<String, UUID> firstPageByHash = new LinkedHashMap<>();
        for (Page page : packagePages) {
            String hash = contentHash(requestSpansByPage.getOrDefault(page.getId(), List.of()));
            UUID duplicateOf = hash == null ? null : firstPageByHash.putIfAbsent(hash, page.getId());
            page.applyContentSignals(hash, duplicateOf);
            pages.save(page);
        }
        log.info(
                "parsed package={} files={} pages={}",
                request.packageId(),
                files.size(),
                packagePages.size());
        return success(Digests.sha256Hex(rawDigests.toString()), lastWorker);
    }

    /**
     * The normalized duplicate-detection hash: sha256 over the ordered span
     * {@code (text, x, y, width, height)} tuples at their stored scale-2 representation — text and
     * geometry, deliberately NOT the raster (DATA_MODEL: near-identical forms must not collide;
     * re-scans of the same content must). Fields join on U+001F, spans close with U+001E, so
     * content containing digits or separator-lookalikes cannot alias. Pages with NO spans hash to
     * null: blank pages are not "duplicates" of each other.
     */
    static String contentHash(List<TextSpan> spansInReadingOrder) {
        if (spansInReadingOrder.isEmpty()) {
            return null;
        }
        StringBuilder tuples = new StringBuilder();
        for (TextSpan span : spansInReadingOrder) {
            tuples.append(span.getText())
                    .append('\u001F')
                    .append(stored(span.getX()))
                    .append('\u001F')
                    .append(stored(span.getY()))
                    .append('\u001F')
                    .append(stored(span.getWidth()))
                    .append('\u001F')
                    .append(stored(span.getHeight()))
                    .append('\u001E');
        }
        return Digests.sha256Hex(tuples.toString());
    }

    /** numeric(10,2) is the stored form; normalize in case the entity still carries wire scale. */
    private static String stored(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The wire's notImplemented list as the stored JSON array — or NULL when the worker never
     * sent the key. Inventing {@code []} there would record "looked for everything" on a
     * declaration nobody made (V18's own rule: a wrong coverage claim is worse than an honest
     * unknown), so an absent declaration persists as NULL and L2 serves {@code UNKNOWN}.
     */
    private String coverageJson(List<String> notImplemented) {
        if (notImplemented == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(notImplemented);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unserializable notImplemented list", e);
        }
    }

    // ── CLASSIFYING (Phase 4 — no worker call: Java rules over persisted spans) ──

    private StageOutcome classifyingStage(StageRequest request) {
        List<Page> packagePages =
                pages.findByPackageIdOrderByPackagePageIndex(request.packageId());
        StringBuilder digest = new StringBuilder();
        int classified = 0;
        int skipped = 0;
        for (Page page : packagePages) {
            // Blank and duplicate pages are skipped ENTIRELY: no result row, and they stay
            // transparent through SPLITTING too.
            if (page.isBlank() || page.getDuplicateOfPageId() != null) {
                skipped++;
                continue;
            }
            ClassificationResult result =
                    pageClassifier.classify(
                            // (source, ordinal) — MIXED pages have two ordinal-0
                            // sequences; ordinal-only order is nondeterministic there.
                            page, textSpans.findByPageIdOrderBySourceAscOrdinalAsc(page.getId()));
            digest.append(page.getId())
                    .append('\u001F')
                    .append(result.getDocumentTypeCode())
                    .append('\u001F')
                    .append(result.getConfidence().toPlainString())
                    .append('\u001E');
            classified++;
        }
        // The model fallback runs LAST and only over what the packs left UNKNOWN, so everything
        // above is byte-identical whether the stage is on or off. Its outcome cannot fail the
        // stage: an ERROR or DISABLED answer leaves the deterministic classification standing,
        // which is exactly the result this stage produced before the fallback existed.
        AiPageClassificationService.StageResult ai =
                aiPageClassificationService.enabled()
                        ? aiPageClassificationService.classifyUnknownPages(request.packageId())
                        : null;
        if (ai != null) {
            // Folded into the digest ONLY when the stage ran, so an off deployment's digest is
            // unchanged. The deterministic tuples above were built before the retypes, so without
            // this a replay whose model typed a page would digest identically to one where it
            // typed nothing — and the digest's whole job is to make a differing stage detectable.
            digest.append("AI")
                    .append('\u001F')
                    .append(ai.status())
                    .append('\u001F')
                    .append(ai.pagesTypedByModel())
                    .append('\u001F')
                    .append(ai.pagesRefusedByGate())
                    .append('\u001F')
                    .append(ai.pagesFailedToPersist())
                    .append('\u001E');
        }
        log.info(
                "classified package={} pages={} skipped={} modelTyped={}",
                request.packageId(),
                classified,
                skipped,
                ai == null ? 0 : ai.pagesTypedByModel());
        // Digest = sha256 over the ordered (pageId, type, confidence) tuples: a replayed stage
        // on identical spans and packs is detectable without re-reading evidence.
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("pagesClassified", classified);
        detail.put("pagesSkipped", skipped);
        detail.put("aiStatus", ai == null ? "OFF" : ai.status().name());
        detail.put("pagesTypedByModel", ai == null ? 0 : ai.pagesTypedByModel());
        detail.put("pagesRefusedByGate", ai == null ? 0 : ai.pagesRefusedByGate());
        // A paid, believed answer the engine then failed to write is neither a retype nor a
        // refusal, and folding it into either would hide the one number that means the stage is
        // losing work rather than doing its job.
        detail.put("pagesFailedToPersist", ai == null ? 0 : ai.pagesFailedToPersist());
        // "Which gate refused this page" is the first question the tuning loop asks. Counts by
        // reason only — the quote is never recorded anywhere, least of all in a stage detail.
        detail.put("refusalsByReason", ai == null ? Map.of() : namedRefusals(ai));
        return new StageOutcome(true, Digests.sha256Hex(digest.toString()), null, detail, null);
    }

    /**
     * The refusal breakdown keyed by reason NAME, because the detail map is serialized to jsonb
     * and an enum key would serialize by whatever Jackson decides rather than by the constant the
     * gates actually named.
     */
    private static Map<String, Integer> namedRefusals(AiPageClassificationService.StageResult ai) {
        Map<String, Integer> named = new LinkedHashMap<>();
        ai.refusalsByReason()
                .forEach((reason, howMany) -> named.put(reason.name(), howMany));
        return named;
    }

    // ── SPLITTING (Phase 4 — no worker call) ───────────────────────────────

    private StageOutcome splittingStage(StageRequest request) {
        List<PackageSplitter.SplitDocument> documents =
                packageSplitter.split(request.packageId());
        StringBuilder digest = new StringBuilder();
        int assignedPages = 0;
        for (PackageSplitter.SplitDocument document : documents) {
            digest.append(document.document().getDocumentTypeCode());
            for (UUID pageId : document.pageIds()) {
                digest.append('\u001F').append(pageId);
            }
            digest.append('\u001E');
            assignedPages += document.pageIds().size();
        }
        log.info(
                "split package={} documents={} assignedPages={}",
                request.packageId(),
                documents.size(),
                assignedPages);
        // Digest over the ordered (type, pageIds) list — the split's identity.
        return new StageOutcome(
                true,
                Digests.sha256Hex(digest.toString()),
                null,
                Map.of("documents", documents.size(), "assignedPages", assignedPages),
                null);
    }

    // ── BOUNDARY_EXTRACTION (Phase D — gated model boundary proposals, no worker call) ──

    private StageOutcome boundaryExtractionStage(StageRequest request) {
        BoundaryExtractionStageService.StageResult result;
        try {
            result =
                    boundaryExtractionStageService.extractForPackage(
                            request.packageId(), request.jobId());
        } catch (RuntimeException e) {
            // The service unwinds its transaction with a typed failure so half-recorded windows
            // roll back; toResult converts the two provider shapes and rethrows anything else.
            result = boundaryExtractionStageService.toResult(e);
        }
        return switch (result.status()) {
            case SKIPPED -> StageOutcome.skipped(result.reason());
            case ERROR ->
                    new StageOutcome(
                            false,
                            null,
                            com.pragmaticds.docengine.platform.error.ErrorCode.BOUNDARY_EXTRACTION_FAILED,
                            Map.of(
                                    "reason", result.reason(),
                                    "windows", result.windows()));
            case APPLIED ->
                    new StageOutcome(
                            true,
                            result.digest(),
                            null,
                            // Counters and token totals only — never quotes, never page text.
                            Map.of(
                                    "windows", result.windows(),
                                    "proposals", result.proposals(),
                                    "accepted", result.accepted(),
                                    "inputTokens", result.inputTokens(),
                                    "outputTokens", result.outputTokens(),
                                    "windowsSkippedByBudget", result.windowsSkippedByBudget()));
        };
    }

    // ── EXTRACTING (Phase 5 — no worker call: engine over persisted rows) ──

    private StageOutcome extractingStage(StageRequest request) {
        FieldExtractionService.ExtractionSummary summary =
                fieldExtractionService.extractForPackage(request.packageId());
        StringBuilder digest = new StringBuilder();
        for (FieldExtractionService.FieldDigest field : summary.fields()) {
            digest.append(field.documentId()).append('/').append(field.fieldName());
            if (field.groupKey() != null) {
                // Appended ONLY for a grouped occurrence, so an ungrouped package's digest is
                // byte-identical to what it was before Spec 5a — the plan's binding contract
                // that no existing single-valued field changes behaviour.
                digest.append('#').append(field.groupKey());
            }
            digest.append('/')
                    .append(field.method())
                    .append('/')
                    .append(field.confidence().toPlainString())
                    .append('\u001E');
        }
        log.info(
                "extracting stage package={} documents={} skipped={} found={} missing={}",
                request.packageId(),
                summary.documentsProcessed(),
                summary.documentsSkipped(),
                summary.fieldsExtracted(),
                summary.fieldsMissing());
        // Digest over the ordered (docId, fieldName[#groupKey], method, confidence) tuples — a
        // replayed stage on identical spans, layout, and schemas is detectable without
        // re-reading rows. The group key is part of the tuple because three occurrences of one
        // field name otherwise digest identically, and a column swap would be invisible.
        return new StageOutcome(
                true,
                Digests.sha256Hex(digest.toString()),
                null,
                Map.of(
                        "documents", summary.documentsProcessed(),
                        "documentsSkipped", summary.documentsSkipped(),
                        "fieldsExtracted", summary.fieldsExtracted(),
                        "fieldsMissing", summary.fieldsMissing()),
                null);
    }

    // ── AI_EXTRACTION (Phase 3 — optional, provider-neutral enrichment) ────

    private StageOutcome aiExtractionStage(StageRequest request) {
        AiExtractionStageService.StageResult result =
                aiExtractionStageService.extractForPackage(request.packageId());
        return switch (result.status()) {
            case SKIPPED -> StageOutcome.skipped(result.reason());
            case ERROR ->
                    result.retryable()
                            ? new StageOutcome(
                                    false,
                                    null,
                                    com.pragmaticds.docengine.platform.error.ErrorCode.AI_EXTRACTION_FAILED,
                                    Map.of(
                                            "reason", result.reason(),
                                            "documents", result.documents()))
                            : StageOutcome.nonRetryableFailure(
                                    com.pragmaticds.docengine.platform.error.ErrorCode.AI_EXTRACTION_FAILED,
                                    Map.of(
                                            "reason", result.reason(),
                                            "documents", result.documents()));
            case APPLIED -> new StageOutcome(true, result.digest(), null, appliedDetail(result));
        };
    }

    /**
     * Counters only, never document text. {@code failureReason} rides along only when a document
     * actually failed — a per-document failure inside an APPLIED stage (issue #58) is otherwise
     * invisible from the stage row.
     */
    private static Map<String, Object> appliedDetail(AiExtractionStageService.StageResult result) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("documents", result.documents());
        detail.put("documentsFailed", result.documentsFailed());
        detail.put("fieldsInserted", result.fieldsInserted());
        detail.put("conflicts", result.conflicts());
        if (result.failureReason() != null) {
            detail.put("failureReason", result.failureReason());
        }
        return Map.copyOf(detail);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private StageOutcome success(String digest, WorkerBlock workerBlock) {
        return new StageOutcome(
                true,
                digest,
                null,
                Map.of(),
                workerBlock == null
                        ? null
                        : new WorkerVersions(workerBlock.version(), workerBlock.libraries()));
    }

    private StageOutcome notYetImplemented(StageRequest request) {
        return new StageOutcome(
                true,
                Digests.sha256Hex(NOT_YET_IMPLEMENTED_NOTE),
                null,
                Map.of("phase2", "not-yet-implemented"),
                null);
    }

    private String toJsonOrNull(List<WireBox> regions) {
        if (regions == null || regions.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(regions);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unserializable uncovered regions", e);
        }
    }

    /** MIXED pages re-read their stored uncovered regions; SCANNED pages OCR the whole page. */
    private List<WireBox> regionsOf(Page page) {
        if (page.getTextLayer() != TextLayer.MIXED || page.getUncoveredRegions() == null) {
            return List.of();
        }
        try {
            return List.of(mapper.readValue(page.getUncoveredRegions(), WireBox[].class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unreadable uncovered regions", e);
        }
    }
}
