package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.analyze.ParsedIncomeAnalysisService;
import com.pragmaticds.rag.lab.engine.DocumentEngineFailure;
import com.pragmaticds.rag.lab.engine.LabContractException;
import com.pragmaticds.rag.lab.release.IncomeLabReleaseService;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.security.LabCryptoException;
import com.pragmaticds.rag.lab.service.IncomeLabService;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.analyze.AnalysisRunRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * Maps every Lab failure taxonomy to HTTP — and stops all of them before the global handler.
 *
 * <p><b>Why a second advice at all.</b> {@code GlobalExceptionHandler} answers with
 * {@code e.getMessage()}. On a Lab route that message could be a provider's response body, an
 * engine request URI, a persistence exception quoting a row, or a filename. Scoping this advice to
 * {@link IncomeLabController} and ordering it {@link Ordered#HIGHEST_PRECEDENCE} means the Lab path
 * never reaches that behaviour, while every other surface keeps it exactly as it was.
 *
 * <p><b>The body is a closed shape.</b> {@link LabDtos.ErrorResponse} has three members: a stable
 * code, the request's correlation id, and numeric/boolean counts. There is no {@code message},
 * {@code error}, {@code detail}, or {@code cause} member, so a leak would require adding a field —
 * not merely forgetting to sanitize one.
 *
 * <p><b>Eight taxonomies, and no string matching anywhere.</b> Each status comes from an enum
 * constant. In particular {@link LabCryptoException#isConfigurationUnavailable()} is what selects
 * the safe 503 for a missing or invalid payload key; the alternative — matching on a message — is
 * both fragile and the exact habit this handler exists to break.
 *
 * <table><caption>Status map</caption>
 * <tr><th>Taxonomy</th><th>Status</th></tr>
 * <tr><td>{@code LabRequestException}</td><td>400 / 404 / 409 / 413 / 503 per code</td></tr>
 * <tr><td>{@code DocumentEngineFailure}</td><td>400 / 413 / 502 / 504 per code</td></tr>
 * <tr><td>{@code LabContractException}</td><td>502 (the engine's bytes, not the caller's fault)</td></tr>
 * <tr><td>{@code ParsedAnalysisException}</td><td>422 (well-formed request, unusable parse)</td></tr>
 * <tr><td>{@code ReleaseException}</td><td>404 for unknown identities, 409 for drift</td></tr>
 * <tr><td>{@code ManifestException}</td><td>500 (a stored manifest we wrote is unreadable)</td></tr>
 * <tr><td>{@code RecorderException}</td><td>500 (the analyzer row could not be proven)</td></tr>
 * <tr><td>{@code LabCryptoException}</td><td>503 when unavailable, else 500</td></tr>
 * <tr><td>{@code SanitizedProviderException}</td><td>502</td></tr>
 * <tr><td>anything else</td><td>500 {@code LAB_REQUEST_FAILED}</td></tr>
 * </table>
 */
@RestControllerAdvice(assignableTypes = IncomeLabController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "ragbrain.lab", name = "enabled", havingValue = "true")
public class LabExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LabExceptionHandler.class);

    /** The code for a failure that carries no Lab taxonomy of its own. */
    private static final String UNEXPECTED = "LAB_REQUEST_FAILED";

    @ExceptionHandler(IncomeLabService.LabRequestException.class)
    public ResponseEntity<LabDtos.ErrorResponse> handleRequest(
            IncomeLabService.LabRequestException failure) {
        HttpStatus status = switch (failure.code()) {
            case IDEMPOTENCY_KEY_REQUIRED, UPLOAD_FILE_COUNT_INVALID, DISCUSSION_QUESTION_REQUIRED ->
                    HttpStatus.BAD_REQUEST;
            case REGISTRATION_NOT_FOUND, RUN_NOT_FOUND, REVISION_NOT_FOUND, JOB_IDENTITY_MISMATCH ->
                    HttpStatus.NOT_FOUND;
            case REGISTRATION_CONFLICT, RUN_IN_PROGRESS, RUN_IN_GROUP, RUN_NOT_SUCCEEDED,
                 RUN_ALREADY_TERMINAL, DISCUSSION_EXCHANGE_IN_PROGRESS,
                 DISCUSSION_EXCHANGE_INTERRUPTED ->
                    HttpStatus.CONFLICT;
            case DISCUSSION_CONTEXT_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case RETENTION_NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case RUN_SOURCE_ABSENT, RUN_PAYLOAD_ABSENT, RUN_PAYLOAD_UNREADABLE,
                 DISCUSSION_EXCHANGE_ABSENT, AUDIT_WRITE_FAILED ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return respond(status, failure.code().name(), failure.counts());
    }

    @ExceptionHandler(DocumentEngineFailure.class)
    public ResponseEntity<LabDtos.ErrorResponse> handleEngine(DocumentEngineFailure failure) {
        HttpStatus status = switch (failure.code()) {
            case UPLOAD_EMPTY, IDEMPOTENCY_KEY_INVALID, REQUEST_ARGUMENT_INVALID ->
                    HttpStatus.BAD_REQUEST;
            case UPLOAD_TOO_LARGE, ENGINE_ENVELOPE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case ENGINE_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            // Everything else is the engine failing its own contract: a redirect, an unexpected
            // status or media type, a digest or length that did not verify. The caller did nothing
            // wrong, so it is a bad gateway rather than a bad request.
            default -> HttpStatus.BAD_GATEWAY;
        };
        // The engine's own status is a count, not a message — safe to surface for triage.
        Map<String, Object> counts = failure.httpStatus() == DocumentEngineFailure.NO_HTTP_STATUS
                ? Map.of()
                : Map.of("engineHttpStatus", failure.httpStatus());
        return respond(status, failure.code().name(), counts);
    }

    @ExceptionHandler(LabContractException.class)
    public ResponseEntity<LabDtos.ErrorResponse> handleContract(LabContractException failure) {
        return respond(HttpStatus.BAD_GATEWAY, failure.code().name(), Map.of());
    }

    @ExceptionHandler(ParsedIncomeAnalysisService.ParsedAnalysisException.class)
    public ResponseEntity<LabDtos.ErrorResponse> handleParsed(
            ParsedIncomeAnalysisService.ParsedAnalysisException failure) {
        // 422: the request was well-formed and authorized; the parse simply cannot be analyzed.
        // There is no raw-document fallback to offer, so this is a stop, not a retry hint.
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, failure.code().name(), Map.of());
    }

    @ExceptionHandler(IncomeLabReleaseService.ReleaseException.class)
    public ResponseEntity<LabDtos.ErrorResponse> handleRelease(
            IncomeLabReleaseService.ReleaseException failure) {
        HttpStatus status = switch (failure.code()) {
            case INSTANCE_UNKNOWN, BRAIN_UNKNOWN, RELEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            // Drift and bootstrap contention are states of the world, not caller errors.
            case ANALYZER_ABSENT, RELEASE_DIGEST_MISMATCH, RELEASE_SCHEMA_DRIFTED,
                 RELEASE_CALCULATOR_DRIFTED, RELEASE_BOOTSTRAP_CONFLICT -> HttpStatus.CONFLICT;
        };
        return respond(status, failure.code().name(), Map.of());
    }

    @ExceptionHandler(LabManifestWriter.ManifestException.class)
    public ResponseEntity<LabDtos.ErrorResponse> handleManifest(
            LabManifestWriter.ManifestException failure) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, failure.code().name(), Map.of());
    }

    @ExceptionHandler(AnalysisRunRecorder.RecorderException.class)
    public ResponseEntity<LabDtos.ErrorResponse> handleRecorder(
            AnalysisRunRecorder.RecorderException failure) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, failure.code().name(), Map.of());
    }

    @ExceptionHandler(LabCryptoException.class)
    public ResponseEntity<LabDtos.ErrorResponse> handleCrypto(LabCryptoException failure) {
        // The safe 503 for a missing or invalid payload key, decided by the taxonomy itself.
        HttpStatus status = failure.isConfigurationUnavailable()
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.INTERNAL_SERVER_ERROR;
        return respond(status, failure.code().name(), Map.of());
    }

    @ExceptionHandler(ModelRouterService.SanitizedProviderException.class)
    public ResponseEntity<LabDtos.ErrorResponse> handleProvider(
            ModelRouterService.SanitizedProviderException failure) {
        // The provider NAME is already part of this sanitized taxonomy and is safe; its message
        // and cause do not exist on this exception at all, so there is nothing to strip.
        return respond(HttpStatus.BAD_GATEWAY, failure.code().name(), Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<LabDtos.ErrorResponse> handleServletUploadCeiling(
            MaxUploadSizeExceededException failure) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE,
                DocumentEngineFailure.Code.UPLOAD_TOO_LARGE.name(), Map.of());
    }

    /**
     * The catch-all for the Lab path, which is the whole reason this advice outranks the global
     * one: an unexpected exception here would otherwise be answered by
     * {@code GlobalExceptionHandler} using its message.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<LabDtos.ErrorResponse> handleUnexpected(Exception failure) {
        // Class name and correlation id only. Not the message — that is the payload.
        log.error("Unexpected Lab failure ({}) [{}]", failure.getClass().getSimpleName(),
                LabAuditService.correlationId());
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED, Map.of());
    }

    private static ResponseEntity<LabDtos.ErrorResponse> respond(HttpStatus status, String code,
                                                                 Map<String, Object> counts) {
        return ResponseEntity.status(status)
                .body(new LabDtos.ErrorResponse(code, LabAuditService.correlationId(), counts));
    }
}
