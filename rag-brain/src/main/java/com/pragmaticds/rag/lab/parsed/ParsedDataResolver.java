package com.pragmaticds.rag.lab.parsed;

import com.pragmaticds.rag.lab.domain.LabDocumentRegistration;
import com.pragmaticds.rag.lab.domain.LabDocumentRegistrationSource;
import com.pragmaticds.rag.lab.domain.LabEnginePackageBinding;
import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabDocumentRegistrationRepository;
import com.pragmaticds.rag.lab.repository.LabDocumentRegistrationSourceRepository;
import com.pragmaticds.rag.lab.repository.LabEnginePackageBindingRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The one place an instance's parsed input is created, verified, or read back.
 *
 * <p>Nothing the caller says is trusted. The package id, revision, and selected source ids are
 * treated as a request to be checked against the engine's own descriptor and the exact bytes it
 * returns; a caller-supplied job id, generation, or source-set digest is never accepted as
 * authority. Verification happens before anything durable is written, so a failed selection
 * leaves no binding and no registration behind.
 *
 * <p><b>Engine I/O and durable writes are separate methods on purpose.</b> {@link #verifyExisting}
 * and {@link #acceptUpload} talk to the Document Engine and write nothing; {@link #pin} and
 * {@link #claimUpload} write and make no outbound call. A caller that owns a transaction — an
 * idempotency receipt, say — can therefore bracket only the write half, instead of holding a
 * pooled connection and a receipt lock across Document Engine latency. {@link #selectExisting}
 * composes the two for callers with no such boundary of their own.
 */
public interface ParsedDataResolver {

    /**
     * Checks a caller's claim about an existing parse against the engine. Reads only.
     *
     * <p>Writes nothing, so a request that turns out to be a reused idempotency key, a wrong
     * revision, or a selection the parse cannot honour costs two idempotent engine reads and
     * leaves no binding, registration, or source row behind.
     */
    VerifiedSelection verifyExisting(
            ExistingParseRequest request, InstanceReleaseManifest.ParsedDataContract contract);

    /**
     * Makes a verified selection durable: the brain claims the package, and the selection becomes
     * a registration. Writes only — no outbound call — so it is safe inside a caller's
     * transaction. Re-pinning the same revision adopts the registration that already exists.
     */
    VerifiedParsedInput pin(VerifiedSelection verified);

    /** Verifies and pins a caller-chosen revision of an existing package. */
    default VerifiedParsedInput selectExisting(
            ExistingParseRequest request, InstanceReleaseManifest.ParsedDataContract contract) {
        return pin(verifyExisting(request, contract));
    }

    /**
     * Hands one original to the engine, which assigns the package and processing job. Engine I/O
     * only; nothing durable is written, and the upload is streamed rather than read.
     */
    DocumentEngineClient.UploadRegistration acceptUpload(UploadRequest request);

    /**
     * Claims an accepted upload's package for the brain and records the registration. Writes only.
     * A package another brain already owns is refused rather than re-bound.
     */
    RegisteredUpload claimUpload(
            UploadRequest request, DocumentEngineClient.UploadRegistration accepted);

    /** One registration's own brain-scoped metadata. No engine I/O, so no engine failure mode. */
    RegisteredInput registered(UUID brainId, String instanceSlug, UUID registrationId);

    /**
     * Re-verifies what a registration currently stands for, without pinning anything new.
     *
     * <p>A pinned registration resolves to exactly the revision and selection it was pinned to. An
     * upload that has not been pinned yet has no selection to honour, so it resolves to the whole
     * of the package's newest parse — which is how a caller discovers the sources and documents a
     * selection could name. {@link VerifiedParsedInput#registrationId()} is the registration read;
     * no row is created, in either case.
     */
    VerifiedParsedInput review(
            UUID brainId, String instanceSlug, UUID registrationId,
            InstanceReleaseManifest.ParsedDataContract contract);

    /** Re-verifies a registration that was already pinned, for a later run. */
    VerifiedParsedInput resolveRegistered(
            UUID brainId, String instanceSlug, UUID registrationId,
            InstanceReleaseManifest.ParsedDataContract contract);

    /**
     * One original to register, described by its size and a source that can be streamed once.
     *
     * <p>Deliberately not a {@code byte[]} and not a {@code MultipartFile}: the bytes go straight
     * from the servlet container's request-scoped spool to the engine. There is no member here a
     * browser filename or declared MIME type could travel in.
     */
    record UploadRequest(
            UUID brainId, String instanceSlug, long sizeBytes, InputStreamSource content,
            String idempotencyKey) {

        public UploadRequest {
            Objects.requireNonNull(content, "content");
        }
    }

    /** A claimed upload: identity, counts, and digests. Never a filename or a stored location. */
    record RegisteredUpload(
            UUID registrationId, UUID packageId, UUID processingJobId, UUID engineSourceId,
            int sourceCount, List<String> duplicateShaPrefixes, boolean created) {

        public RegisteredUpload {
            duplicateShaPrefixes = List.copyOf(
                    Objects.requireNonNull(duplicateShaPrefixes, "duplicateShaPrefixes"));
        }
    }

    /** One registration as stored: what it points at, never what it says. */
    record RegisteredInput(
            UUID registrationId, UUID brainId, String instanceSlug, UUID packageId,
            UUID processingJobId, UUID engineSourceId,
            LabDocumentRegistration.RegistrationMode registrationMode,
            Integer selectedRevision, String sourceSetSha256, List<UUID> selectedSourceIds,
            OffsetDateTime registeredAt) {

        public RegisteredInput {
            selectedSourceIds = List.copyOf(
                    Objects.requireNonNull(selectedSourceIds, "selectedSourceIds"));
        }
    }

    /**
     * A parse checked against the engine and narrowed to a selection, with nothing written yet.
     *
     * <p>This is exactly the evidence {@link #pin} needs and nothing more: the descriptor the
     * revision resolved to, the bytes it was proved to describe, the reconciled selection, and the
     * contract's verdict on it. Holding it is not a claim on the package.
     */
    record VerifiedSelection(
            UUID brainId,
            String instanceSlug,
            UUID packageId,
            int revision,
            DocumentEngineClient.RevisionDescriptor descriptor,
            DocumentEngineClient.VerifiedEnvelope fetched,
            ParsedInputSelection.SelectionResult selection,
            ParsedDataCompatibilityService.CompatibilityDecision compatibility) {

        public VerifiedSelection {
            Objects.requireNonNull(brainId, "brainId");
            Objects.requireNonNull(instanceSlug, "instanceSlug");
            Objects.requireNonNull(packageId, "packageId");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(fetched, "fetched");
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(compatibility, "compatibility");
        }
    }

    record ExistingParseRequest(
            UUID brainId, String instanceSlug, UUID packageId, int revision,
            List<UUID> selectedSourceIds) {

        public ExistingParseRequest {
            selectedSourceIds = List.copyOf(
                    Objects.requireNonNull(selectedSourceIds, "selectedSourceIds"));
        }
    }

    /**
     * One verified parsed input.
     *
     * <p>{@code envelope} is the whole revision; {@code selectedEnvelope} is the narrowing the
     * instance will actually analyze. Both are carried because provenance belongs to the package
     * while analysis belongs to the selection, and collapsing them would lose one or the other.
     */
    record VerifiedParsedInput(
            UUID registrationId,
            UUID packageId,
            int revision,
            UUID processingJobId,
            int parseGeneration,
            String envelopeVersion,
            String canonicalizationVersion,
            String envelopeSha256,
            long envelopeSizeBytes,
            String sourceSetSha256,
            List<UUID> selectedSourceIds,
            EngineResultEnvelope envelope,
            EngineResultEnvelope selectedEnvelope,
            ParsedDataCompatibilityService.CompatibilityDecision compatibility) {

        public VerifiedParsedInput {
            selectedSourceIds = List.copyOf(
                    Objects.requireNonNull(selectedSourceIds, "selectedSourceIds"));
        }
    }

    /** Stable, value-free failure vocabulary. */
    final class ParsedDataException extends RuntimeException {
        public enum Code {
            PARSE_REQUEST_INVALID,
            PARSE_REGISTRATION_NOT_FOUND,
            PARSE_REVISION_NOT_FOUND,
            PARSE_DESCRIPTOR_MISMATCH,
            PARSE_SCOPE_MISMATCH,
            PARSE_CONTRACT_UNSUPPORTED,
            /** The engine accepted an upload but described no source, so nothing can be pinned. */
            PARSE_ENGINE_RESPONSE_INVALID,
            /** No Document Engine is configured, so no existing parse can be verified. */
            PARSE_ENGINE_UNAVAILABLE,
            /** Raised by execution, not by resolution: a rejection still returns its decision. */
            PARSE_INCOMPATIBLE
        }

        private final Code code;

        public ParsedDataException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }
}


@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class DefaultParsedDataResolver implements ParsedDataResolver {

    /**
     * The engine client only exists when the Lab is enabled, while this resolver exists whenever
     * instances are. Holding a provider rather than the client keeps the two surfaces independent:
     * the admin routes still load with the Lab off, and a parsed-input request without a
     * configured engine fails closed with a code instead of the context refusing to start.
     */
    private final ObjectProvider<DocumentEngineClient> engineProvider;
    private final LabEnginePackageBindingRepository bindings;
    private final LabDocumentRegistrationRepository registrations;
    private final LabDocumentRegistrationSourceRepository registrationSources;
    private final ParsedDataCompatibilityService compatibility;
    private final TransactionTemplate persistence;

    DefaultParsedDataResolver(ObjectProvider<DocumentEngineClient> engineProvider,
                              LabEnginePackageBindingRepository bindings,
                              LabDocumentRegistrationRepository registrations,
                              LabDocumentRegistrationSourceRepository registrationSources,
                              ParsedDataCompatibilityService compatibility,
                              PlatformTransactionManager transactionManager) {
        this.persistence = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.engineProvider = Objects.requireNonNull(engineProvider, "engineProvider");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.registrationSources = Objects.requireNonNull(registrationSources, "registrationSources");
        this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
    }

    // ================================================================ existing parses

    @Override
    public VerifiedSelection verifyExisting(
            ExistingParseRequest request, InstanceReleaseManifest.ParsedDataContract contract) {
        validate(request, contract);

        // Refuse a package another brain already owns before spending any engine I/O on it. The
        // binding is only *created* by pin(), further down.
        requireUnowned(request.packageId(), request.brainId());

        Fetched fetched = fetch(request.packageId(), request.revision());
        return narrow(request.brainId(), request.instanceSlug(), fetched,
                request.selectedSourceIds(), contract);
    }

    @Override
    public VerifiedParsedInput pin(VerifiedSelection verified) {
        Objects.requireNonNull(verified, "verified");
        // Everything below is durable and nothing below is a network call: the parse is already
        // verified and the selection already reconciled. Keeping the engine reads out of this
        // transaction is what stops database capacity from being tied to Document Engine latency.
        LabDocumentRegistration registration = persistence.execute(status -> {
            claimPackage(verified.packageId(), verified.brainId());
            return registrations
                    .findByBrainIdAndInstanceSlugAndEnginePackageIdAndSelectedRevision(
                            verified.brainId(), verified.instanceSlug(), verified.packageId(),
                            verified.revision())
                    .orElseGet(() -> persist(verified));
        });
        return pinnedInput(registration.getId(), verified);
    }

    // ================================================================ uploads

    @Override
    public DocumentEngineClient.UploadRegistration acceptUpload(UploadRequest request) {
        if (request == null || request.brainId() == null || request.instanceSlug() == null
                || request.instanceSlug().isBlank() || request.sizeBytes() <= 0
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_REQUEST_INVALID);
        }
        DocumentEngineClient.UploadRegistration accepted = engine().register(
                new DocumentEngineClient.EngineUpload(request.sizeBytes(), request.content()),
                request.idempotencyKey());
        // V38 requires an UPLOAD_ONE registration to name its source. An accepted registration
        // that describes none cannot be recorded, and inventing a source id is not an option, so
        // this fails here rather than at the constraint.
        if (accepted.sources().isEmpty()) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_ENGINE_RESPONSE_INVALID);
        }
        return accepted;
    }

    @Override
    public RegisteredUpload claimUpload(
            UploadRequest request, DocumentEngineClient.UploadRegistration accepted) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(accepted, "accepted");
        if (accepted.sources().isEmpty()) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_ENGINE_RESPONSE_INVALID);
        }
        return persistence.execute(status -> {
            // An engine replay of the same key returns the package it already assigned, so an
            // existing registration for it is the expected outcome of a retry — but only when the
            // same brain and instance own it. Anything else is a claim on someone else's package.
            var existing = registrations
                    .findFirstByEnginePackageIdOrderByRegisteredAtAsc(accepted.packageId());
            if (existing.isPresent()) {
                LabDocumentRegistration bound = existing.get();
                if (!bound.getBrainId().equals(request.brainId())
                        || !bound.getInstanceSlug().equals(request.instanceSlug())) {
                    throw new ParsedDataException(ParsedDataException.Code.PARSE_SCOPE_MISMATCH);
                }
                return upload(bound, accepted, false);
            }
            claimPackage(accepted.packageId(), request.brainId());

            LabDocumentRegistration created = new LabDocumentRegistration();
            created.setBrainId(request.brainId());
            created.setInstanceSlug(request.instanceSlug());
            created.setEnginePackageId(accepted.packageId());
            created.setEngineJobId(accepted.jobId());
            created.setEngineSourceId(accepted.sources().getFirst().id());
            created.setRegistrationMode(LabDocumentRegistration.RegistrationMode.UPLOAD_ONE);
            return upload(registrations.saveAndFlush(created), accepted, true);
        });
    }

    // ================================================================ reads

    @Override
    public RegisteredInput registered(UUID brainId, String instanceSlug, UUID registrationId) {
        LabDocumentRegistration registration = requireScoped(brainId, instanceSlug, registrationId);
        return new RegisteredInput(
                registration.getId(),
                registration.getBrainId(),
                registration.getInstanceSlug(),
                registration.getEnginePackageId(),
                registration.getEngineJobId(),
                registration.getEngineSourceId(),
                registration.getRegistrationMode(),
                registration.getSelectedRevision(),
                registration.getSourceSetSha256(),
                storedSources(registration.getId()),
                registration.getRegisteredAt());
    }

    @Override
    public VerifiedParsedInput review(
            UUID brainId, String instanceSlug, UUID registrationId,
            InstanceReleaseManifest.ParsedDataContract contract) {
        Objects.requireNonNull(contract, "contract");
        LabDocumentRegistration registration = requireScoped(brainId, instanceSlug, registrationId);
        requireUnowned(registration.getEnginePackageId(), brainId);

        boolean pinned = registration.getRegistrationMode()
                == LabDocumentRegistration.RegistrationMode.EXISTING_PARSE
                && registration.getSelectedRevision() != null;
        // A null revision asks the engine for the package's newest parse, which is the only thing
        // an upload nothing has pinned yet can honestly resolve to.
        Fetched fetched = fetch(registration.getEnginePackageId(),
                pinned ? registration.getSelectedRevision() : null);
        // A pinned registration is reviewed as what it was pinned to. An unpinned upload has no
        // selection of its own, so the whole parse is the only honest answer — and it is the one
        // a caller needs in order to choose a selection at all.
        List<UUID> sources = pinned
                ? storedSources(registrationId)
                : fetched.parse().envelope().sources().stream()
                        .sorted(Comparator.comparingInt(EngineResultEnvelope.SourceFile::ordinal))
                        .map(EngineResultEnvelope.SourceFile::id)
                        .toList();
        return pinnedInput(registrationId,
                narrow(brainId, instanceSlug, fetched, sources, contract));
    }

    @Override
    public VerifiedParsedInput resolveRegistered(
            UUID brainId, String instanceSlug, UUID registrationId,
            InstanceReleaseManifest.ParsedDataContract contract) {
        Objects.requireNonNull(contract, "contract");
        if (brainId == null || instanceSlug == null || registrationId == null) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_REQUEST_INVALID);
        }
        LabDocumentRegistration registration = registrations.findById(registrationId)
                .orElseThrow(() -> new ParsedDataException(
                        ParsedDataException.Code.PARSE_SCOPE_MISMATCH));
        if (!brainId.equals(registration.getBrainId())
                || !instanceSlug.equals(registration.getInstanceSlug())
                || registration.getRegistrationMode()
                        != LabDocumentRegistration.RegistrationMode.EXISTING_PARSE
                || registration.getSelectedRevision() == null) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_SCOPE_MISMATCH);
        }

        // Re-verify from the stored selection rather than trusting the row: the engine is still
        // the authority, and a run must fail closed if the parse it was pinned to has moved.
        return selectExisting(new ExistingParseRequest(brainId, instanceSlug,
                registration.getEnginePackageId(), registration.getSelectedRevision(),
                storedSources(registrationId)), contract);
    }

    // ================================================================ internals

    /** One revision's descriptor together with the exact bytes it was proved to describe. */
    private record Fetched(
            DocumentEngineClient.RevisionDescriptor descriptor,
            DocumentEngineClient.VerifiedEnvelope parse) {}

    /** Fetches and verifies one revision; a null {@code revision} means the package's newest. */
    private Fetched fetch(UUID packageId, Integer revision) {
        DocumentEngineClient engine = engine();
        List<DocumentEngineClient.RevisionDescriptor> history = engine.revisionHistory(packageId);
        DocumentEngineClient.RevisionDescriptor descriptor = (revision == null
                ? history.stream().max(Comparator.comparingInt(
                        DocumentEngineClient.RevisionDescriptor::revision))
                : history.stream()
                        .filter(candidate -> candidate.revision() == revision)
                        .findFirst())
                .orElseThrow(() -> new ParsedDataException(
                        ParsedDataException.Code.PARSE_REVISION_NOT_FOUND));

        DocumentEngineClient.VerifiedEnvelope fetched =
                engine.envelopeRevision(packageId, descriptor.revision());
        // The descriptor the caller's revision resolved to must describe the exact bytes returned.
        // A caller-supplied job id or source-set digest never substitutes for this comparison.
        if (!descriptor.describes(fetched)) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_DESCRIPTOR_MISMATCH);
        }
        if (!packageId.equals(fetched.envelope().packageId())) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_SCOPE_MISMATCH);
        }
        return new Fetched(descriptor, fetched);
    }

    private VerifiedSelection narrow(UUID brainId, String instanceSlug,
                                     Fetched fetched, List<UUID> selectedSourceIds,
                                     InstanceReleaseManifest.ParsedDataContract contract) {
        ParsedInputSelection.SelectionResult selection;
        try {
            selection = ParsedInputSelection.select(
                    fetched.parse().envelope(), selectedSourceIds);
        } catch (ParsedInputSelection.SelectionException refused) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_SCOPE_MISMATCH);
        }
        // Compatibility is judged on what will actually be analyzed, not on the whole package.
        ParsedDataCompatibilityService.CompatibilityDecision decision = compatibility.evaluate(
                selection.selectedEnvelope(),
                ParsedDataCompatibilityService.Policy.of(
                        contract, ParsedDataCompatibilityService.REVIEW_WARNING_VALIDATION_STATUSES));
        return new VerifiedSelection(brainId, instanceSlug,
                fetched.parse().envelope().packageId(), fetched.descriptor().revision(),
                fetched.descriptor(), fetched.parse(), selection, decision);
    }

    private DocumentEngineClient engine() {
        DocumentEngineClient engine = engineProvider.getIfAvailable();
        if (engine == null) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_ENGINE_UNAVAILABLE);
        }
        return engine;
    }

    private LabDocumentRegistration requireScoped(
            UUID brainId, String instanceSlug, UUID registrationId) {
        if (brainId == null || instanceSlug == null || instanceSlug.isBlank()
                || registrationId == null) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_REQUEST_INVALID);
        }
        LabDocumentRegistration registration = registrations.findById(registrationId)
                .orElseThrow(() -> new ParsedDataException(
                        ParsedDataException.Code.PARSE_REGISTRATION_NOT_FOUND));
        // A registration another brain or another instance owns is reported as absent, not as
        // forbidden: an admin key for one brain must not be able to probe another brain's ids.
        if (!brainId.equals(registration.getBrainId())
                || !instanceSlug.equals(registration.getInstanceSlug())) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_REGISTRATION_NOT_FOUND);
        }
        return registration;
    }

    private List<UUID> storedSources(UUID registrationId) {
        return registrationSources
                .findByIdRegistrationIdOrderBySourcePositionAsc(registrationId).stream()
                .map(LabDocumentRegistrationSource::getEngineSourceId)
                .toList();
    }

    private void requireUnowned(UUID packageId, UUID brainId) {
        bindings.findById(packageId).ifPresent(binding -> {
            if (!binding.getBrainId().equals(brainId)) {
                throw new ParsedDataException(ParsedDataException.Code.PARSE_SCOPE_MISMATCH);
            }
        });
    }

    private void claimPackage(UUID packageId, UUID brainId) {
        bindings.findById(packageId).ifPresentOrElse(
                binding -> {
                    if (!binding.getBrainId().equals(brainId)) {
                        throw new ParsedDataException(
                                ParsedDataException.Code.PARSE_SCOPE_MISMATCH);
                    }
                },
                () -> bindings.saveAndFlush(new LabEnginePackageBinding(packageId, brainId)));
    }

    private LabDocumentRegistration persist(VerifiedSelection verified) {
        LabDocumentRegistration registration = new LabDocumentRegistration();
        registration.setBrainId(verified.brainId());
        registration.setInstanceSlug(verified.instanceSlug());
        registration.setEnginePackageId(verified.packageId());
        registration.setEngineJobId(verified.descriptor().processingJobId());
        registration.setEngineSourceId(null);
        registration.setRegistrationMode(LabDocumentRegistration.RegistrationMode.EXISTING_PARSE);
        registration.setSelectedRevision(verified.revision());
        registration.setSourceSetSha256(verified.selection().sourceSetSha256());
        LabDocumentRegistration saved = registrations.saveAndFlush(registration);

        registrationSources.saveAll(verified.selection().selectedSources().stream()
                .map(source -> new LabDocumentRegistrationSource(saved.getId(), source.sourceId(),
                        source.contentSha256(), source.position()))
                .toList());
        return saved;
    }

    private static RegisteredUpload upload(LabDocumentRegistration registration,
                                           DocumentEngineClient.UploadRegistration accepted,
                                           boolean created) {
        return new RegisteredUpload(
                registration.getId(),
                accepted.packageId(),
                accepted.jobId(),
                registration.getEngineSourceId(),
                accepted.sources().size(),
                accepted.duplicateShaPrefixes(),
                created);
    }

    private static VerifiedParsedInput pinnedInput(
            UUID registrationId, VerifiedSelection verified) {
        DocumentEngineClient.VerifiedEnvelope fetched = verified.fetched();
        return new VerifiedParsedInput(
                registrationId,
                fetched.envelope().packageId(),
                verified.revision(),
                verified.descriptor().processingJobId(),
                verified.descriptor().parseGeneration(),
                fetched.envelope().envelopeVersion(),
                fetched.envelope().canonicalizationVersion(),
                fetched.artifact().sha256(),
                fetched.artifact().byteCount(),
                verified.selection().sourceSetSha256(),
                verified.selection().selectedSources().stream()
                        .map(ParsedInputSelection.SelectedSource::sourceId).toList(),
                fetched.envelope(),
                verified.selection().selectedEnvelope(),
                verified.compatibility());
    }

    private static void validate(
            ExistingParseRequest request, InstanceReleaseManifest.ParsedDataContract contract) {
        if (contract == null) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_CONTRACT_UNSUPPORTED);
        }
        if (request == null || request.brainId() == null || request.instanceSlug() == null
                || request.instanceSlug().isBlank() || request.packageId() == null
                || request.revision() < 1 || request.selectedSourceIds().isEmpty()) {
            throw new ParsedDataException(ParsedDataException.Code.PARSE_REQUEST_INVALID);
        }
    }
}
