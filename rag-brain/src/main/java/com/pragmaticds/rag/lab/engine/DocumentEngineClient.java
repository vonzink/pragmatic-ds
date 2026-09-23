package com.pragmaticds.rag.lab.engine;

import org.springframework.core.io.InputStreamSource;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The narrow backend-only port onto Pragmatic DS Document Engine.
 *
 * <p>Five operations, nothing more: register one original, read that registration's job, list a
 * package's immutable revision descriptors, and read the exact bytes of the current or of one
 * historical revision. There is no reparse, regroup, correction, review, or delete operation here
 * — the prototype consumes the engine's immutable results and never mutates them.
 *
 * <p>Every record below is value-free in the privacy sense: identifiers, digests, counts, sizes,
 * and statuses. No implementation of this port may accept or return document bytes as a
 * {@code byte[]}, a filename, or a browser-declared MIME type. The one exception is
 * {@link VerifiedEnvelope}, whose parsed envelope is the point of the Lab.
 */
public interface DocumentEngineClient {

    /**
     * Registers exactly one original with the engine, forwarding the caller's idempotency key
     * unchanged so a replay returns the same package and job.
     *
     * @throws DocumentEngineFailure with a value-free code; an empty or oversized upload and an
     *     unusable idempotency key all fail before any byte leaves this process
     */
    UploadRegistration register(EngineUpload upload, String idempotencyKey);

    /** Reads one processing job's current status. Never uploads, resumes, or reparses. */
    JobSnapshot job(UUID jobId);

    /** Ascending immutable revision descriptors for one package; metadata only. */
    List<RevisionDescriptor> revisionHistory(UUID packageId);

    /** The current parse generation's exact verified bytes. */
    VerifiedEnvelope currentEnvelope(UUID packageId);

    /** One historical revision's exact verified bytes. */
    VerifiedEnvelope envelopeRevision(UUID packageId, int revision);

    /**
     * One document's CURRENT reviewed field values from the engine read model: masked,
     * review-aware ({@code effectiveStatus}), and not byte-frozen. Values only; the envelope
     * remains the citation.
     */
    ReviewedFields reviewedFields(UUID documentId);

    /**
     * One request-scoped upload, described by its size and a source that can be streamed once.
     *
     * <p>Deliberately not a {@code byte[]}, not a {@code File}, and not a {@code MultipartFile}:
     * the caller passes {@code multipartFile.getSize()} and {@code multipartFile.getResource()},
     * so the document is forwarded straight from the servlet container's bounded request-scoped
     * spool without {@code getBytes()} and without any application-owned temporary file. The
     * browser filename and declared MIME type have no member to travel in.
     */
    record EngineUpload(long sizeBytes, InputStreamSource content) {

        public EngineUpload {
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * What an accepted registration yields. The engine's {@code originalFilename} is read by
     * nobody and modelled by nothing; only content digests and counts survive.
     */
    record UploadRegistration(
            UUID packageId,
            UUID jobId,
            List<RegisteredSource> sources,
            List<String> duplicateShaPrefixes) {

        public UploadRegistration {
            Objects.requireNonNull(packageId, "packageId");
            Objects.requireNonNull(jobId, "jobId");
            sources = List.copyOf(sources);
            duplicateShaPrefixes = List.copyOf(duplicateShaPrefixes);
        }
    }

    /** One registered original, identified by content digest — never by filename. */
    record RegisteredSource(UUID id, String contentSha256, long sizeBytes, Integer pageCount) {

        public RegisteredSource {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(contentSha256, "contentSha256");
        }
    }

    /** A processing job's status snapshot; stage error detail stays inside the engine. */
    record JobSnapshot(UUID jobId, UUID packageId, String status, String currentStage) {

        public JobSnapshot {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(packageId, "packageId");
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * One immutable engine-result descriptor, mirroring the engine's approved metadata view
     * exactly. Storage identity is absent there and absent here.
     */
    record RevisionDescriptor(
            int revision,
            UUID processingJobId,
            int parseGeneration,
            int materializedJobAttempt,
            String envelopeSchemaVersion,
            String sourceSetSha256,
            String provenanceSha256,
            String envelopeSha256,
            long envelopeSizeBytes,
            String reuseEligibility,
            Instant createdAt) {

        public RevisionDescriptor {
            Objects.requireNonNull(processingJobId, "processingJobId");
            Objects.requireNonNull(envelopeSchemaVersion, "envelopeSchemaVersion");
            Objects.requireNonNull(envelopeSha256, "envelopeSha256");
            Objects.requireNonNull(createdAt, "createdAt");
        }

        /**
         * True when this descriptor and a fetched revision describe the same immutable bytes.
         * Task 6 pins a run against the descriptor it selected; this is that equality.
         */
        public boolean describes(VerifiedEnvelope fetched) {
            Objects.requireNonNull(fetched, "fetched");
            return revision == fetched.revision()
                    && envelopeSha256.equals(fetched.artifact().sha256())
                    && envelopeSizeBytes == fetched.artifact().byteCount()
                    && processingJobId.equals(fetched.envelope().generation().processingJobId())
                    && parseGeneration == fetched.envelope().generation().parseGeneration()
                    && Objects.equals(
                            sourceSetSha256, fetched.envelope().generation().sourceSetSha256());
        }
    }

    /**
     * One exact-content read whose bytes were verified against the engine's quoted {@code ETag}
     * and {@code Content-Length} before the strict parser ever saw them.
     */
    record VerifiedEnvelope(
            EngineArtifactDescriptor artifact, EngineResultEnvelope envelope, int revision) {

        public VerifiedEnvelope {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(envelope, "envelope");
        }
    }
}
