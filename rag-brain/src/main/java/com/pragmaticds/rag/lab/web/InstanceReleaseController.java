package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.eval.InstanceEvaluationService;
import com.pragmaticds.rag.lab.instance.InstanceCandidateService;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.CreateInstanceCommand;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.WizardValidationScope;
import com.pragmaticds.rag.lab.instance.InstancePromotionService;
import com.pragmaticds.rag.lab.instance.InstancePromotionService.PromotionCommand;
import com.pragmaticds.rag.lab.instance.InstanceRegistryService;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoring, evaluating, and shipping releases.
 *
 * <p><b>Validation is the only route here that neither writes nor requires a key.</b> A wizard
 * calls it per step and again for the whole form; it creates nothing, so a key would be theatre.
 * Everything else — create, candidate, evaluate, promote, rollback — mutates and requires one.
 *
 * <p><b>The canonical request for a pointer move includes the caller's expectation.</b> Promoting
 * release B while believing A is live is a different request from promoting B while believing C
 * is, so the same key cannot be used to replay one as the other. The change reason is hashed too:
 * two promotions of the same release for different stated reasons are two decisions.
 */
@RestController
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
@RequestMapping("/api/ai/admin/instances")
public final class InstanceReleaseController {

    private final InstanceCandidateService candidates;
    private final InstanceEvaluationService evaluations;
    private final InstancePromotionService promotions;
    private final LabIdempotencyService idempotency;

    public InstanceReleaseController(InstanceCandidateService candidates,
                                     InstanceEvaluationService evaluations,
                                     InstancePromotionService promotions,
                                     LabIdempotencyService idempotency) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.evaluations = Objects.requireNonNull(evaluations, "evaluations");
        this.promotions = Objects.requireNonNull(promotions, "promotions");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    }

    /**
     * Validates a definition without writing anything.
     *
     * <p>{@code scope} lets a wizard check one section; omitting it checks the whole form. Both
     * run the same code, so a section that passes alone passes identically inside a complete check.
     */
    @PostMapping(value = "/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InstanceReleaseDtos.ValidationView validate(
            @RequestParam UUID brain,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestBody InstanceReleaseDtos.CreateInstanceRequest request) {
        return InstanceReleaseDtos.validation(
                candidates.validate(scopeOf(scope), command(brain, request)));
    }

    /** Creates the instance and its first candidate release. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InstanceReleaseDtos.CandidateReleaseView> create(
            @RequestParam UUID brain,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody InstanceReleaseDtos.CreateInstanceRequest request) {
        requireKey(key);
        CreateInstanceCommand command = command(brain, request);
        InstanceReleaseDtos.CandidateReleaseView created = idempotency.execute(
                new LabIdempotencyService.IdempotentCommand<>(brain, "instance.create", key,
                        hash("create", brain.toString(), nullSafe(request.slug()),
                                manifestDigest(request)),
                        () -> InstanceReleaseDtos.candidate(candidates.create(command)),
                        result -> new LabIdempotencyService.IdempotencyResult(
                                "INSTANCE_CANDIDATE", result.releaseId(), 1L),
                        receipt -> { throw new ReleaseWebException(
                                ReleaseWebException.Code.RELEASE_REQUEST_REPLAYED); }));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Appends the next candidate release to an existing instance. */
    @PostMapping(value = "/{instance}/candidates",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InstanceReleaseDtos.CandidateReleaseView> addCandidate(
            @RequestParam UUID brain, @PathVariable String instance,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody InstanceReleaseDtos.CreateInstanceRequest request) {
        requireKey(key);
        CreateInstanceCommand command = new CreateInstanceCommand(brain, instance,
                request.displayName(), request.purpose(), request.manifest());
        InstanceReleaseDtos.CandidateReleaseView created = idempotency.execute(
                new LabIdempotencyService.IdempotentCommand<>(brain, "instance.candidate", key,
                        hash("candidate", brain.toString(), instance, manifestDigest(request)),
                        () -> InstanceReleaseDtos.candidate(candidates.addCandidate(command)),
                        result -> new LabIdempotencyService.IdempotencyResult(
                                "INSTANCE_CANDIDATE", result.releaseId(), 1L),
                        receipt -> { throw new ReleaseWebException(
                                ReleaseWebException.Code.RELEASE_REQUEST_REPLAYED); }));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Runs the release's scenario set and records the verdict. Never repeats on replay. */
    @PostMapping(value = "/{instance}/releases/{releaseId}/evaluate",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InstanceReleaseDtos.EvaluationView evaluate(
            @RequestParam UUID brain, @PathVariable String instance,
            @PathVariable UUID releaseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireKey(key);
        return idempotency.execute(new LabIdempotencyService.IdempotentCommand<>(
                brain, "instance.evaluate", key,
                hash("evaluate", brain.toString(), instance, releaseId.toString()),
                () -> InstanceReleaseDtos.evaluation(
                        evaluations.evaluate(brain, instance, releaseId)),
                result -> new LabIdempotencyService.IdempotencyResult(
                        "INSTANCE_EVALUATION", result.evaluationId(), 1L),
                // An evaluation is a model run against fixtures; replaying the key must not run
                // it a second time, and returning a stale verdict as if it were fresh would be
                // worse than telling the caller the key is spent.
                receipt -> { throw new ReleaseWebException(
                        ReleaseWebException.Code.RELEASE_REQUEST_REPLAYED); }));
    }

    /** Checks the promotion gate without moving anything. */
    @PostMapping(value = "/{instance}/releases/{releaseId}/promotion-check",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InstanceReleaseDtos.PromotionDecisionView check(
            @RequestParam UUID brain, @PathVariable String instance,
            @PathVariable UUID releaseId,
            @RequestBody InstanceReleaseDtos.PointerMoveRequest request) {
        return InstanceReleaseDtos.decision(
                promotions.evaluateGate(move(brain, instance, releaseId, request)));
    }

    /** Points live at a candidate, if the gate passes and the caller's view still holds. */
    @PostMapping(value = "/{instance}/releases/{releaseId}/apply-to-live",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InstanceReleaseDtos.PointerStateView promote(
            @RequestParam UUID brain, @PathVariable String instance,
            @PathVariable UUID releaseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody InstanceReleaseDtos.PointerMoveRequest request) {
        return pointerMove(brain, instance, releaseId, key, request, "promote");
    }

    /** Points live back at an earlier release, through the same gate and the same check. */
    @PostMapping(value = "/{instance}/releases/{releaseId}/rollback",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InstanceReleaseDtos.PointerStateView rollback(
            @RequestParam UUID brain, @PathVariable String instance,
            @PathVariable UUID releaseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody InstanceReleaseDtos.PointerMoveRequest request) {
        return pointerMove(brain, instance, releaseId, key, request, "rollback");
    }

    // ================================================================ internals

    private InstanceReleaseDtos.PointerStateView pointerMove(
            UUID brain, String instance, UUID releaseId, String key,
            InstanceReleaseDtos.PointerMoveRequest request, String operation) {
        requireKey(key);
        PromotionCommand command = move(brain, instance, releaseId, request);
        return idempotency.execute(new LabIdempotencyService.IdempotentCommand<>(
                brain, "instance." + operation, key,
                // The caller's expectation is part of the request: promoting B believing A is
                // live is a different decision from promoting B believing C is.
                hash(operation, brain.toString(), instance, releaseId.toString(),
                        command.expectedLiveReleaseId() == null
                                ? "" : command.expectedLiveReleaseId().toString(),
                        Long.toString(command.expectedPointerVersion()),
                        command.changeReason()),
                () -> InstanceReleaseDtos.pointer("rollback".equals(operation)
                        ? promotions.rollback(command) : promotions.promote(command)),
                result -> new LabIdempotencyService.IdempotencyResult(
                        "INSTANCE_POINTER", releaseId, result.pointerVersion()),
                // Replaying returns what the receipt recorded rather than moving again: a second
                // move would fail its own compare-and-set anyway, and reporting that as a
                // conflict would be misleading for what is really a retry.
                receipt -> new InstanceReleaseDtos.PointerStateView(
                        receipt.id(), receipt.version() == null ? 0L : receipt.version())));
    }

    private static PromotionCommand move(UUID brain, String instance, UUID releaseId,
                                         InstanceReleaseDtos.PointerMoveRequest request) {
        if (request == null) {
            throw new ReleaseWebException(ReleaseWebException.Code.RELEASE_REQUEST_INVALID);
        }
        return new PromotionCommand(brain, instance, releaseId, request.expectedLiveReleaseId(),
                request.expectedPointerVersion() == null ? 0L : request.expectedPointerVersion(),
                request.actorId(), request.changeReason());
    }

    private static CreateInstanceCommand command(
            UUID brain, InstanceReleaseDtos.CreateInstanceRequest request) {
        if (request == null) {
            throw new ReleaseWebException(ReleaseWebException.Code.RELEASE_REQUEST_INVALID);
        }
        return new CreateInstanceCommand(brain, request.slug(), request.displayName(),
                request.purpose(), request.manifest());
    }

    /**
     * A stand-in digest for the manifest in the canonical request.
     *
     * <p>Uses the manifest's own {@code toString}, which for a record is a structural rendering of
     * its components. It binds the key to this definition without the controller having to
     * re-implement canonical manifest encoding, and the authoritative digest is the one the codec
     * computes when the release is written.
     */
    private static String manifestDigest(InstanceReleaseDtos.CreateInstanceRequest request) {
        return request.manifest() == null ? "" : hash(request.manifest().toString());
    }

    private static WizardValidationScope scopeOf(String scope) {
        if (scope == null || scope.isBlank()) {
            return WizardValidationScope.COMPLETE;
        }
        try {
            return WizardValidationScope.valueOf(scope);
        } catch (IllegalArgumentException unrecognised) {
            throw new ReleaseWebException(ReleaseWebException.Code.RELEASE_REQUEST_INVALID);
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ReleaseWebException(ReleaseWebException.Code.IDEMPOTENCY_KEY_REQUIRED);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String hash(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = nullSafe(field).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    static final class ReleaseWebException extends RuntimeException {
        enum Code {
            IDEMPOTENCY_KEY_REQUIRED,
            RELEASE_REQUEST_INVALID,
            /** The key is spent on an operation that must not be performed twice. */
            RELEASE_REQUEST_REPLAYED,
            RELEASE_REQUEST_FAILED
        }

        private final Code code;

        ReleaseWebException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        Code code() { return code; }
    }
}

/** Safe, payload-free error taxonomy for only the release routes. */
@RestControllerAdvice(assignableTypes = InstanceReleaseController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
final class InstanceReleaseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InstanceReleaseExceptionHandler.class);
    record ErrorResponse(String code, List<String> blockingCodes) {}

    @ExceptionHandler(InstanceReleaseController.ReleaseWebException.class)
    ResponseEntity<ErrorResponse> handleWeb(InstanceReleaseController.ReleaseWebException failure) {
        HttpStatus status = switch (failure.code()) {
            case IDEMPOTENCY_KEY_REQUIRED, RELEASE_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case RELEASE_REQUEST_REPLAYED -> HttpStatus.CONFLICT;
            case RELEASE_REQUEST_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, failure.code().name(), List.of());
    }

    /** An invalid definition answers 422 with every violation, so one round of fixes suffices. */
    @ExceptionHandler(InstanceCandidateService.CandidateException.class)
    ResponseEntity<ErrorResponse> handleCandidate(
            InstanceCandidateService.CandidateException failure) {
        HttpStatus status = switch (failure.code()) {
            case INSTANCE_DEFINITION_INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INSTANCE_ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case INSTANCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return response(status, failure.code().name(), failure.violations());
    }

    @ExceptionHandler(InstancePromotionService.PromotionException.class)
    ResponseEntity<ErrorResponse> handlePromotion(
            InstancePromotionService.PromotionException failure) {
        HttpStatus status = switch (failure.code()) {
            case PROMOTION_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case INSTANCE_PROMOTION_DISABLED -> HttpStatus.FORBIDDEN;
            case LIVE_POINTER_CHANGED -> HttpStatus.CONFLICT;
            case PROMOTION_BLOCKED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return response(status, failure.code().name(), failure.blockingCodes());
    }

    @ExceptionHandler(InstanceEvaluationService.EvaluationException.class)
    ResponseEntity<ErrorResponse> handleEvaluation(
            InstanceEvaluationService.EvaluationException failure) {
        HttpStatus status = switch (failure.code()) {
            case EVALUATION_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case EVALUATION_RELEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            // All three are a conflict between what the release pinned and what this brain or
            // this build currently has, and all three are fixed by changing one of the two —
            // never by retrying the same request.
            case EVALUATION_RELEASE_NOT_PINNABLE, EVALUATION_SCENARIO_SET_UNKNOWN,
                 EVALUATION_CORPUS_UNAVAILABLE -> HttpStatus.CONFLICT;
            // The set names a fixture nothing in this build can serve: a deployment gap, not
            // something the operator can correct from here.
            case EVALUATION_RUNNER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            // The brain's own quota, not the server's capacity — 503 would tell an operator to
            // page someone about a service that is working exactly as configured.
            case EVALUATION_BUDGET_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
        };
        return response(status, failure.code().name(), List.of());
    }

    @ExceptionHandler(InstanceRegistryService.InstanceException.class)
    ResponseEntity<ErrorResponse> handleRegistry(InstanceRegistryService.InstanceException f) {
        HttpStatus status = f.code() == InstanceRegistryService.InstanceException.Code
                .INSTANCE_DISABLED ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND;
        return response(status, f.code().name(), List.of());
    }

    @ExceptionHandler(LabIdempotencyService.IdempotencyException.class)
    ResponseEntity<ErrorResponse> handleIdempotency(
            LabIdempotencyService.IdempotencyException failure) {
        HttpStatus status = switch (failure.code()) {
            case IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
            case IDEMPOTENCY_COMMAND_INVALID -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_RECEIPT_CONFLICT -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, failure.code().name(), List.of());
    }

    @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> handleUnreadable(Exception failure) {
        return response(HttpStatus.BAD_REQUEST, "RELEASE_REQUEST_INVALID", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception failure) {
        // Class name and correlation id only. Not the message — that is the payload.
        log.error("Unexpected instance release request failure ({}) [{}]",
                failure.getClass().getSimpleName(), LabAuditService.correlationId());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "RELEASE_REQUEST_FAILED", List.of());
    }

    private static ResponseEntity<ErrorResponse> response(
            HttpStatus status, String code, List<String> blockingCodes) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, blockingCodes));
    }
}
