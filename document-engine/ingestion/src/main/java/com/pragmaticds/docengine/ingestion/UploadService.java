package com.pragmaticds.docengine.ingestion;

import com.pragmaticds.docengine.ingestion.domain.DocumentPackage;
import com.pragmaticds.docengine.ingestion.domain.MalwareScanStatus;
import com.pragmaticds.docengine.ingestion.domain.SourceFile;
import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.ingestion.repo.SourceFileRepository;
import com.pragmaticds.docengine.ingestion.scan.MalwareScanPort;
import com.pragmaticds.docengine.ingestion.validate.ImageProbe;
import com.pragmaticds.docengine.ingestion.validate.MimeSniffer;
import com.pragmaticds.docengine.ingestion.validate.PdfProbe;
import com.pragmaticds.docengine.platform.audit.AuditEvent;
import com.pragmaticds.docengine.platform.audit.AuditService;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.storage.BlobStoragePort;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The upload path: validate every file (magic bytes, caps, PDF probe, hash, malware port), persist
 * the package and its files, store the original bytes, and hand the package across the seam to
 * processing.
 *
 * <p>Validation runs to completion over ALL files before anything is persisted, so a rejection is
 * a clean no-op — no half-written package to garbage-collect.
 *
 * <p>Blob writes happen inside the transaction but are not transactional themselves: a storage
 * write followed by a rolled-back commit leaves an orphan blob. Acceptable in Phase 1 — orphans
 * are unreachable (no row points at them) and a later janitor can sweep keys with no matching
 * {@code source_file}. The reverse order (commit rows, then write blobs) would be worse: a row
 * pointing at bytes that do not exist.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    /** shaPrefix length in error params and warnings: enough to correlate, not the full digest. */
    private static final int SHA_PREFIX_CHARS = 12;

    private static final String MIME_PDF = "application/pdf";

    private final DocumentPackageRepository packages;
    private final SourceFileRepository sourceFiles;
    private final BlobStoragePort storage;
    private final MalwareScanPort malwareScan;
    private final ProcessingStarter processingStarter;
    private final ReuseProbePort reuseProbe;
    private final AuditService audit;
    private final MimeSniffer mimeSniffer;
    private final HashingService hashing;
    private final long maxFileBytes;
    private final PdfProbe pdfProbe;
    private final ImageProbe imageProbe;

    public UploadService(
            DocumentPackageRepository packages,
            SourceFileRepository sourceFiles,
            BlobStoragePort storage,
            MalwareScanPort malwareScan,
            ProcessingStarter processingStarter,
            ReuseProbePort reuseProbe,
            AuditService audit,
            MimeSniffer mimeSniffer,
            HashingService hashing,
            @Value("${docengine.ingest.max-file-bytes:104857600}") long maxFileBytes,
            @Value("${docengine.ingest.max-pages:500}") int maxPages) {
        this.packages = packages;
        this.sourceFiles = sourceFiles;
        this.storage = storage;
        this.malwareScan = malwareScan;
        this.processingStarter = processingStarter;
        this.reuseProbe = reuseProbe;
        this.audit = audit;
        this.mimeSniffer = mimeSniffer;
        this.hashing = hashing;
        this.maxFileBytes = maxFileBytes;
        this.pdfProbe = new PdfProbe(maxPages);
        this.imageProbe = new ImageProbe(maxPages);
    }

    @Transactional
    public UploadResult upload(
            List<FileUpload> files,
            UUID loanId,
            String name,
            String clientIdempotencyKey,
            boolean forceReparse) {
        // Idempotency FIRST, before any validation or persistence: a replayed key means the
        // client already got (or lost) an answer, and the contract is to return that original
        // answer — not to create a second package, write duplicate blobs, and then discover the
        // replay at job-insert time, where the unique violation aborts this whole transaction
        // (25P02; review finding, confirmed). It also runs BEFORE the reuse probe: the replay
        // contract predates reuse and must keep answering exactly as it always has.
        if (clientIdempotencyKey != null && !clientIdempotencyKey.isBlank()) {
            var existing = processingStarter.findExisting(clientIdempotencyKey);
            if (existing.isPresent()) {
                return replayResult(existing.get());
            }
        }

        if (files == null
                || files.isEmpty()
                || files.stream().allMatch(f -> f.bytes() == null || f.bytes().length == 0)) {
            throw DomainException.badRequest(
                    ErrorCode.EMPTY_UPLOAD, Map.of("fileCount", files == null ? 0 : files.size()));
        }

        List<ValidatedFile> validated = validateAll(files);

        // Parse-once reuse (validated, nothing persisted — a hit is a clean no-op). The whole
        // probe fails OPEN to a normal parse: reuse may save work, it may never break an upload.
        //
        // This value is a PREDICTION of the behavior a parse started now would execute under, and
        // it is used for one thing: matching candidates. It is never stored. The parse stamps
        // itself at FINALIZING from what it actually ran under, because upload cannot know that —
        // the pipeline runs asynchronously and from caches, and a prediction stored as fact is
        // how one fingerprint came to describe two different outputs.
        String behaviorFingerprint = null;
        try {
            behaviorFingerprint = reuseProbe.behaviorFingerprint().orElse(null);
            if (behaviorFingerprint != null && !forceReparse) {
                Optional<ReuseProbePort.ReuseHit> hit =
                        reuseProbe.findReusable(identities(validated), behaviorFingerprint);
                if (hit.isPresent()) {
                    return reusedResult(hit.get(), behaviorFingerprint);
                }
            }
        } catch (RuntimeException probeFailure) {
            // Class name only — never file names or content.
            log.warn(
                    "reuse probe failed open to a normal parse exception={}",
                    probeFailure.getClass().getSimpleName());
        }

        UUID orgId = TenantContext.require();
        DocumentPackage pkg = new DocumentPackage(loanId, name);
        pkg.setPageCount(
                validated.stream()
                        .map(ValidatedFile::pageCount)
                        .filter(count -> count != null)
                        .mapToInt(Integer::intValue)
                        .sum());
        packages.save(pkg);

        // Duplicate probes run before any SourceFile persist so the auto-flush a query
        // triggers never flushes a row whose storage key is not yet assigned.
        List<UploadResult.DuplicateWarning> warnings = new ArrayList<>();
        for (ValidatedFile file : validated) {
            // The warning names the hash prefix only. It deliberately does NOT name the other
            // package's source-file id: same-org or not, an upload response should not be a
            // catalogue of where else this document lives (review finding).
            sourceFiles
                    .findFirstByOrgIdAndSha256AndPackageIdNot(orgId, file.sha256(), pkg.getId())
                    .ifPresent(
                            existing ->
                                    warnings.add(
                                            new UploadResult.DuplicateWarning(
                                                    shaPrefix(file.sha256()))));
        }

        List<UploadResult.FileResult> results = new ArrayList<>();
        for (ValidatedFile file : validated) {
            SourceFile entity =
                    new SourceFile(
                            pkg.getId(),
                            file.ordinal(),
                            file.upload().filename(),
                            file.contentType(),
                            file.upload().declaredContentType(),
                            file.upload().bytes().length,
                            file.sha256(),
                            file.pageCount(),
                            file.encrypted(),
                            file.scanStatus());
            sourceFiles.save(entity);
            String key = orgId + "/" + pkg.getId() + "/" + entity.getId() + "/original";
            entity.assignStorageKey(key);
            storage.put(key, file.upload().bytes());
            results.add(
                    new UploadResult.FileResult(
                            entity.getId(),
                            entity.getOriginalFilename(),
                            entity.getContentType(),
                            entity.getSizeBytes(),
                            entity.getSha256(),
                            entity.getPageCount()));
        }

        String idempotencyKey =
                (clientIdempotencyKey == null || clientIdempotencyKey.isBlank())
                        ? pkg.getId().toString()
                        : clientIdempotencyKey;
        UUID jobId = processingStarter.startJob(pkg.getId(), idempotencyKey);

        return new UploadResult(
                pkg.getId(), jobId, List.copyOf(results), List.copyOf(warnings), null);
    }

    /**
     * Rebuilds the original upload response from persisted state, for an idempotency replay. The
     * package the key resolved to is re-read (org-scoped) along with its files; warnings are not
     * reconstructed — they were advisory at first submission and are not re-derivable cheaply.
     */
    private UploadResult replayResult(ProcessingStarter.ExistingJob existing) {
        return priorPackageResult(existing.packageId(), existing.jobId(), null);
    }

    /**
     * A parse-once reuse hit: the PRIOR package is the answer — no new package, no new job, no
     * row copies, ZERO worker calls. Serving the same package is what keeps every read model, the
     * review flow, and the effectiveStatus overlay working unchanged, and what keeps a reviewer's
     * append-only decisions (keyed on the prior rows' UUIDs) reachable. The durable record is the
     * audit event — no stage row is written, because a stage row never claims work that did not
     * run.
     */
    private UploadResult reusedResult(ReuseProbePort.ReuseHit hit, String behaviorFingerprint) {
        audit.record(
                AuditEvent.ACTION_PACKAGE_REUSE_SERVED,
                "DOCUMENT_PACKAGE",
                hit.packageId(),
                Map.of(
                        "reusedPackageId", hit.packageId().toString(),
                        "engineResultRevision", hit.engineResultRevision(),
                        "behaviorFingerprint", behaviorFingerprint));
        log.info(
                "reuse served package={} job={} revision={}",
                hit.packageId(),
                hit.jobId(),
                hit.engineResultRevision());
        return priorPackageResult(
                hit.packageId(),
                hit.jobId(),
                new UploadResult.Reused(hit.packageId(), hit.jobId(), hit.engineResultRevision()));
    }

    private UploadResult priorPackageResult(
            UUID packageId, UUID jobId, UploadResult.Reused reused) {
        UUID orgId = TenantContext.require();
        DocumentPackage pkg =
                packages
                        .findByIdAndOrgId(packageId, orgId)
                        .orElseThrow(() -> DomainException.notFound(ErrorCode.NOT_FOUND));
        List<UploadResult.FileResult> files =
                sourceFiles.findByPackageIdOrderByOrdinal(pkg.getId()).stream()
                        .map(
                                file ->
                                        new UploadResult.FileResult(
                                                file.getId(),
                                                file.getOriginalFilename(),
                                                file.getContentType(),
                                                file.getSizeBytes(),
                                                file.getSha256(),
                                                file.getPageCount()))
                        .toList();
        return new UploadResult(pkg.getId(), jobId, files, List.of(), reused);
    }

    private static List<ReuseProbePort.FileIdentity> identities(List<ValidatedFile> validated) {
        return validated.stream()
                .map(
                        file ->
                                new ReuseProbePort.FileIdentity(
                                        file.ordinal(),
                                        file.sha256(),
                                        file.upload().bytes().length,
                                        file.contentType()))
                .toList();
    }

    /** Everything ingestion decided about one accepted file, before persistence. */
    private record ValidatedFile(
            int ordinal,
            FileUpload upload,
            String contentType,
            String sha256,
            Integer pageCount,
            boolean encrypted,
            MalwareScanStatus scanStatus) {}

    private List<ValidatedFile> validateAll(List<FileUpload> files) {
        List<ValidatedFile> validated = new ArrayList<>(files.size());
        Set<String> seenShas = new HashSet<>();
        for (int ordinal = 0; ordinal < files.size(); ordinal++) {
            FileUpload upload = files.get(ordinal);
            byte[] bytes = upload.bytes() == null ? new byte[0] : upload.bytes();

            if (bytes.length > maxFileBytes) {
                throw DomainException.badRequest(
                        ErrorCode.FILE_TOO_LARGE,
                        Map.of("sizeBytes", bytes.length, "maxBytes", maxFileBytes));
            }

            // Magic bytes decide; the client's claim is recorded for audit but trusted for
            // nothing. The error param says "unknown", never the filename or the claim.
            String contentType =
                    mimeSniffer
                            .sniff(bytes)
                            .orElseThrow(
                                    () ->
                                            DomainException.badRequest(
                                                    ErrorCode.UNSUPPORTED_MIME,
                                                    Map.of("sniffed", "unknown")));

            // Every accepted type is probed, and every accepted file therefore has a page count.
            // Images used to skip this entirely ("until the Phase 2 normalization worker converts
            // and counts them" — a worker that never shipped), which left page_count NULL on four
            // real paystub photos and let an undecodable image through to fail as a CORRUPT_PDF
            // five stages later.
            Integer pageCount;
            // Owner-password-only PDFs are accepted (they read fine) but the fact is recorded.
            // Images are never encrypted in any sense this column tracks.
            boolean encrypted = false;
            if (MIME_PDF.equals(contentType)) {
                PdfProbe.Result probed = pdfProbe.probe(bytes);
                pageCount = probed.pageCount();
                encrypted = probed.encrypted();
            } else {
                // A single JPEG/PNG is one page; a multi-frame TIFF is a multi-page scan. The
                // sniffed type goes with the bytes because HEIC has no JVM decoder and is
                // therefore counted rather than read — see the ImageProbe javadoc.
                pageCount = imageProbe.probe(bytes, contentType).pageCount();
            }

            String sha256 = hashing.sha256Hex(bytes);
            if (!seenShas.add(sha256)) {
                throw DomainException.conflict(
                        ErrorCode.DUPLICATE_FILE, Map.of("shaPrefix", shaPrefix(sha256)));
            }

            validated.add(
                    new ValidatedFile(
                            ordinal,
                            upload,
                            contentType,
                            sha256,
                            pageCount,
                            encrypted,
                            malwareScan.scan(bytes)));
        }
        return validated;
    }

    private static String shaPrefix(String sha256) {
        return sha256.substring(0, SHA_PREFIX_CHARS);
    }
}
