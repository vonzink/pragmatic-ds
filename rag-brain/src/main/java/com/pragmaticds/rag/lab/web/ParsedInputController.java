package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.engine.DocumentEngineFailure;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceRegistryService;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pragmaticds.rag.lab.parsed.RegistrationLoanFactsService;
import com.pragmaticds.rag.service.analyze.calc.LoanBasis;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Brain-scoped administration for the parsed input an instance analyzes.
 *
 * <p>Four things a caller can do: hand over one original, pin one revision of an existing parse,
 * read back what a registration points at, and read the structure of the parse it resolves to.
 * There is no route here that runs a model, and none that returns an extracted value —
 * {@link ParsedInputDtos} says exactly what does and does not cross this boundary.
 *
 * <p><b>Feature-off means unmapped.</b> The bean is conditional on {@code ragbrain.instances}, so
 * with the flag absent these paths have no mapping at all and an authenticated admin gets a bare
 * 404 that reveals nothing about the flag, the engine, or any other configuration.
 *
 * <p><b>The idempotency key is bound to the request before anything leaves this process.</b> Both
 * POSTs canonicalize their request, hash it, and check that hash against any receipt the key
 * already carries — {@link LabIdempotencyService#requireUnusedOrMatching} — before the Document
 * Engine is called. A client reusing a key for a different body is therefore refused rather than
 * having the second body acted on. The authoritative comparison still happens under the receipt
 * lock inside {@link LabIdempotencyService#execute}, which is what protects durable state.
 *
 * <p><b>No transaction is held across the engine.</b> Each POST verifies first — engine reads that
 * write nothing — and only then opens the idempotent transaction around the durable half. A
 * refused request costs two idempotent reads and leaves no binding, registration, or receipt.
 */
@RestController
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
@RequestMapping("/api/ai/admin/instances")
public final class ParsedInputController {
    private static final String SELECT_OPERATION = "instance.parsedInput.select";
    private static final String UPLOAD_OPERATION = "instance.parsedInput.upload";
    private static final String RESULT_KIND = "PARSED_INPUT_REGISTRATION";

    private final ParsedDataResolver parsedInputs;
    private final InstanceReleaseResolver releases;
    private final LabIdempotencyService idempotency;
    private final RegistrationLoanFactsService loanFacts;

    public ParsedInputController(ParsedDataResolver parsedInputs,
                                 InstanceReleaseResolver releases,
                                 LabIdempotencyService idempotency,
                                 RegistrationLoanFactsService loanFacts) {
        this.parsedInputs = Objects.requireNonNull(parsedInputs, "parsedInputs");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.loanFacts = Objects.requireNonNull(loanFacts, "loanFacts");
    }

    /**
     * Pins one existing revision of one package, narrowed to the sources named.
     *
     * <p>The revision and the selection are a request, not a fact: both are checked against the
     * engine's descriptor and the exact bytes it returns before any row is written.
     */
    @PostMapping(value = "/{instance}/parsed-inputs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ParsedInputDtos.PinnedParsedInput> select(
            @RequestParam UUID brain,
            @PathVariable String instance,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody ParsedInputDtos.SelectParsedInputRequest request) {
        requireKey(key);
        requireSlug(instance);
        List<UUID> sources = selectedSources(request);
        int revision = revision(request);

        // Canonical form is the sorted selection, because the parse's own ordinal — not the order
        // the caller listed ids in — decides what gets analyzed. The same selection expressed two
        // ways is the same request and must hash the same.
        List<UUID> canonicalSources = sources.stream().sorted().toList();
        String[] fields = new String[5 + canonicalSources.size()];
        fields[0] = "select";
        fields[1] = brain.toString();
        fields[2] = instance;
        fields[3] = request.packageId().toString();
        fields[4] = Integer.toString(revision);
        for (int index = 0; index < canonicalSources.size(); index++) {
            fields[5 + index] = canonicalSources.get(index).toString();
        }
        String requestSha256 = hash(fields);
        idempotency.requireUnusedOrMatching(brain, SELECT_OPERATION, key, requestSha256);

        InstanceReleaseManifest.ParsedDataContract contract = contract(brain, instance);
        // Engine I/O, outside every transaction. Nothing durable exists yet.
        ParsedDataResolver.VerifiedSelection verified = parsedInputs.verifyExisting(
                new ParsedDataResolver.ExistingParseRequest(
                        brain, instance, request.packageId(), revision, sources),
                contract);

        boolean[] created = {false};
        ParsedDataResolver.VerifiedParsedInput pinned = idempotency.execute(
                new LabIdempotencyService.IdempotentCommand<>(
                        brain, SELECT_OPERATION, key, requestSha256,
                        () -> {
                            created[0] = true;
                            return parsedInputs.pin(verified);
                        },
                        result -> new LabIdempotencyService.IdempotencyResult(
                                RESULT_KIND, result.registrationId(), 1L),
                        receipt -> parsedInputs.review(
                                brain, instance, registrationOf(receipt), contract)));

        // Outside the idempotent command on purpose. The facts are not part of what a
        // registration IS, so they are absent from the request digest and a replay does not
        // re-run the command that would have written them — a retry that carries them must
        // still attach them. Writing the same facts twice is a no-op; writing different ones
        // is a refusal, so this can never quietly change the basis under a queued run.
        loanFacts.store(brain, pinned.registrationId(), loanBasis(request));

        return ResponseEntity.status(created[0] ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ParsedInputDtos.pinned(brain, instance, pinned));
    }

    /**
     * Hands the engine exactly one original and claims the package it assigns.
     *
     * <p>The bytes are streamed from the servlet container's request-scoped spool straight to the
     * engine: {@code getSize()} and {@code getResource()}, never {@code getBytes()}, and no
     * application-owned temporary file. The canonical request cannot include a content digest for
     * that same reason — reading the upload to hash it is precisely what this refuses to do — so
     * it binds the key to the brain, the instance, and the declared size, and the engine's own
     * key-forwarded idempotency is what keeps a retry from becoming a second package.
     */
    @PostMapping(value = "/{instance}/parsed-inputs/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ParsedInputDtos.UploadedParsedInput> upload(
            @RequestParam UUID brain,
            @PathVariable String instance,
            @RequestParam(value = "file", required = false) List<MultipartFile> files,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireKey(key);
        requireSlug(instance);
        if (files == null || files.size() != 1 || files.getFirst() == null
                || files.getFirst().isEmpty()) {
            throw invalid();
        }
        MultipartFile file = files.getFirst();
        String requestSha256 = hash("upload", brain.toString(), instance,
                Long.toString(file.getSize()));
        idempotency.requireUnusedOrMatching(brain, UPLOAD_OPERATION, key, requestSha256);

        // Resolved for its refusals, not for its value: an instance with no live pinnable
        // release never reaches the engine, because a package nothing can analyze is not worth
        // creating. There is no parse to evaluate the contract against yet.
        contract(brain, instance);

        ParsedDataResolver.UploadRequest request = new ParsedDataResolver.UploadRequest(
                brain, instance, file.getSize(), file.getResource(), key);
        // Engine I/O, outside every transaction. The engine assigns the package; nothing here has
        // claimed it yet.
        var accepted = parsedInputs.acceptUpload(request);

        ParsedDataResolver.RegisteredUpload registered = idempotency.execute(
                new LabIdempotencyService.IdempotentCommand<>(
                        brain, UPLOAD_OPERATION, key, requestSha256,
                        () -> parsedInputs.claimUpload(request, accepted),
                        result -> new LabIdempotencyService.IdempotencyResult(
                                RESULT_KIND, result.registrationId(), 1L),
                        receipt -> replayUpload(brain, instance, receipt, accepted)));
        return ResponseEntity.status(registered.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ParsedInputDtos.uploaded(registered));
    }

    /** What one registration points at. Reads no engine, so no engine outage can break it. */
    @GetMapping(value = "/{instance}/parsed-inputs/{registrationId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ParsedInputDtos.ParsedInputSummary read(
            @RequestParam UUID brain,
            @PathVariable String instance,
            @PathVariable UUID registrationId) {
        requireSlug(instance);
        return ParsedInputDtos.summary(parsedInputs.registered(brain, instance, registrationId));
    }

    /**
     * The structure of the parse a registration resolves to, re-verified against the engine.
     *
     * <p>A pinned registration resolves to exactly what it was pinned to. An upload nothing has
     * pinned yet resolves to the whole of the package's newest parse, which is how a caller
     * discovers the sources a selection could name. Either way this is a read: no revision is
     * pinned and no row is created as a side effect of looking.
     */
    @GetMapping(value = "/{instance}/parsed-inputs/{registrationId}/envelope",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ParsedInputDtos.ParsedInputEnvelope envelope(
            @RequestParam UUID brain,
            @PathVariable String instance,
            @PathVariable UUID registrationId) {
        requireSlug(instance);
        ParsedDataResolver.RegisteredInput registered =
                parsedInputs.registered(brain, instance, registrationId);
        ParsedDataResolver.VerifiedParsedInput verified = parsedInputs.review(
                brain, instance, registrationId, contract(brain, instance));
        return ParsedInputDtos.envelope(verified, registered.selectedRevision() != null);
    }

    // ================================================================ internals

    private InstanceReleaseManifest.ParsedDataContract contract(UUID brain, String instance) {
        var resolved = releases.live(new InstanceKey(brain, instance));
        if (resolved.manifest() instanceof DecodedInstanceManifest.V2 v2) {
            return v2.manifest().parsedData();
        }
        // A v1 Income release has no generalized parsed-data contract to evaluate against, and
        // substituting a default would silently widen what the release said it accepts.
        throw new ParsedInputException(ParsedInputException.Code.INSTANCE_RELEASE_NOT_PINNABLE);
    }

    private ParsedDataResolver.RegisteredUpload replayUpload(
            UUID brain, String instance, LabIdempotencyService.IdempotencyResult receipt,
            DocumentEngineClient.UploadRegistration accepted) {
        ParsedDataResolver.RegisteredInput registered =
                parsedInputs.registered(brain, instance, registrationOf(receipt));
        // The engine replayed the same key onto the same package, so the receipt must point at the
        // registration that already claimed it. A disagreement means the two are out of step, and
        // guessing which one is right is not an option.
        if (!registered.packageId().equals(accepted.packageId())) {
            throw failed();
        }
        return new ParsedDataResolver.RegisteredUpload(
                registered.registrationId(), registered.packageId(), registered.processingJobId(),
                registered.engineSourceId(), accepted.sources().size(),
                accepted.duplicateShaPrefixes(), false);
    }

    private static UUID registrationOf(LabIdempotencyService.IdempotencyResult receipt) {
        if (!RESULT_KIND.equals(receipt.kind()) || receipt.id() == null
                || receipt.version() == null || receipt.version() != 1L) {
            throw failed();
        }
        return receipt.id();
    }

    /**
     * The loan facts this request carried, or null when it carried none.
     *
     * <p>Values are passed through exactly as sent. An unrecognized program or purpose becomes
     * null rather than an error, and {@code LoanBasis} then makes the threshold report itself
     * unavailable — a suite sending "Conventional" gets a visible refusal, not a silent guess.
     */
    private static LoanBasis loanBasis(ParsedInputDtos.SelectParsedInputRequest request) {
        ParsedInputDtos.LoanFactsRequest facts = request.loanFacts();
        if (facts == null) {
            return null;
        }
        return new LoanBasis(
                LoanBasis.programOf(facts.program()),
                LoanBasis.purposeOf(facts.loanPurpose()),
                facts.qualifyingMonthlyIncome(),
                facts.adjustedValue());
    }

    private static List<UUID> selectedSources(ParsedInputDtos.SelectParsedInputRequest request) {
        if (request == null || request.packageId() == null || request.selectedSourceIds() == null
                || request.selectedSourceIds().isEmpty()) {
            throw invalid();
        }
        Set<UUID> seen = new HashSet<>();
        List<UUID> sources = new ArrayList<>(request.selectedSourceIds().size());
        for (UUID source : request.selectedSourceIds()) {
            // A duplicate id has no canonical form — sorting would silently collapse it — so it is
            // refused here rather than hashed into a request that means something else.
            if (source == null || !seen.add(source)) {
                throw invalid();
            }
            sources.add(source);
        }
        return List.copyOf(sources);
    }

    private static int revision(ParsedInputDtos.SelectParsedInputRequest request) {
        Integer revision = request.revision();
        if (revision == null || revision < 1) {
            throw invalid();
        }
        return revision;
    }

    private static void requireSlug(String instance) {
        if (instance == null || instance.isBlank()) {
            throw invalid();
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ParsedInputException(ParsedInputException.Code.IDEMPOTENCY_KEY_REQUIRED);
        }
    }

    private static ParsedInputException invalid() {
        return new ParsedInputException(ParsedInputException.Code.PARSED_INPUT_REQUEST_INVALID);
    }

    private static ParsedInputException failed() {
        return new ParsedInputException(ParsedInputException.Code.PARSED_INPUT_REQUEST_FAILED);
    }

    private static String hash(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = Objects.requireNonNull(field, "canonical field")
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    static final class ParsedInputException extends RuntimeException {
        enum Code {
            IDEMPOTENCY_KEY_REQUIRED,
            PARSED_INPUT_REQUEST_INVALID,
            INSTANCE_RELEASE_NOT_PINNABLE,
            PARSED_INPUT_REQUEST_FAILED
        }

        private final Code code;

        ParsedInputException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        Code code() { return code; }
    }
}

/** Safe, payload-free error taxonomy for only the parsed-input routes. */
@RestControllerAdvice(assignableTypes = ParsedInputController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
final class ParsedInputExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ParsedInputExceptionHandler.class);
    record ErrorResponse(String code) {}

    @ExceptionHandler(ParsedInputController.ParsedInputException.class)
    ResponseEntity<ErrorResponse> handleAdmin(ParsedInputController.ParsedInputException failure) {
        HttpStatus status = switch (failure.code()) {
            case IDEMPOTENCY_KEY_REQUIRED, PARSED_INPUT_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case INSTANCE_RELEASE_NOT_PINNABLE -> HttpStatus.CONFLICT;
            case PARSED_INPUT_REQUEST_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, failure.code().name());
    }

    @ExceptionHandler(ParsedDataResolver.ParsedDataException.class)
    ResponseEntity<ErrorResponse> handleParsedData(
            ParsedDataResolver.ParsedDataException failure) {
        HttpStatus status = switch (failure.code()) {
            case PARSE_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case PARSE_REGISTRATION_NOT_FOUND, PARSE_REVISION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PARSE_DESCRIPTOR_MISMATCH, PARSE_SCOPE_MISMATCH, PARSE_CONTRACT_UNSUPPORTED ->
                    HttpStatus.CONFLICT;
            case PARSE_INCOMPATIBLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PARSE_ENGINE_RESPONSE_INVALID -> HttpStatus.BAD_GATEWAY;
            case PARSE_ENGINE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(status, failure.code().name());
    }

    /**
     * A registration already carrying different loan facts is a conflict, not a bad request.
     *
     * <p>The caller's request is well-formed; it disagrees with what is already stored, and the
     * stored value wins because a queued run may already have been priced against it.
     */
    @ExceptionHandler(RegistrationLoanFactsService.LoanFactsException.class)
    ResponseEntity<ErrorResponse> handleLoanFacts(
            RegistrationLoanFactsService.LoanFactsException failure) {
        return response(HttpStatus.CONFLICT, failure.code().name());
    }

    @ExceptionHandler(InstanceRegistryService.InstanceException.class)
    ResponseEntity<ErrorResponse> handleRegistry(InstanceRegistryService.InstanceException failure) {
        HttpStatus status =
                failure.code() == InstanceRegistryService.InstanceException.Code.INSTANCE_DISABLED
                        ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND;
        return response(status, failure.code().name());
    }

    @ExceptionHandler(InstanceReleaseResolver.ReleaseResolutionException.class)
    ResponseEntity<ErrorResponse> handleRelease(
            InstanceReleaseResolver.ReleaseResolutionException failure) {
        HttpStatus status = switch (failure.code()) {
            case LIVE_RELEASE_NOT_FOUND, RELEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RELEASE_SCOPE_MISMATCH -> HttpStatus.CONFLICT;
        };
        return response(status, failure.code().name());
    }

    /**
     * The engine's own codes are enum names and carry no request URI, response body, or header.
     * The exception's message and cause never reach the wire.
     */
    @ExceptionHandler(DocumentEngineFailure.class)
    ResponseEntity<ErrorResponse> handleEngine(DocumentEngineFailure failure) {
        HttpStatus status = switch (failure.code()) {
            case UPLOAD_EMPTY, UPLOAD_TOO_LARGE, IDEMPOTENCY_KEY_INVALID, REQUEST_ARGUMENT_INVALID ->
                    HttpStatus.BAD_REQUEST;
            case ENGINE_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return response(status, failure.code().name());
    }

    @ExceptionHandler(LabIdempotencyService.IdempotencyException.class)
    ResponseEntity<ErrorResponse> handleIdempotency(
            LabIdempotencyService.IdempotencyException failure) {
        return switch (failure.code()) {
            case IDEMPOTENCY_KEY_REUSED -> response(HttpStatus.CONFLICT, failure.code().name());
            case IDEMPOTENCY_COMMAND_INVALID -> response(
                    HttpStatus.BAD_REQUEST, "PARSED_INPUT_REQUEST_INVALID");
            case IDEMPOTENCY_RECEIPT_CONFLICT -> response(
                    HttpStatus.INTERNAL_SERVER_ERROR, "PARSED_INPUT_REQUEST_FAILED");
        };
    }

    @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.multipart.MultipartException.class
    })
    ResponseEntity<ErrorResponse> handleUnreadable(Exception failure) {
        return response(HttpStatus.BAD_REQUEST, "PARSED_INPUT_REQUEST_INVALID");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception failure) {
        // Class name and correlation id only. Not the message — that is the payload.
        log.error("Unexpected parsed input request failure ({}) [{}]",
                failure.getClass().getSimpleName(), LabAuditService.correlationId());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "PARSED_INPUT_REQUEST_FAILED");
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new ErrorResponse(code));
    }
}
