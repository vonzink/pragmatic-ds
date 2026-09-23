package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.service.InstanceDiscussionService;
import com.pragmaticds.rag.lab.service.LabAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Follow-up questions about one run's answer.
 *
 * <p>Both routes are scoped by group and member, so a run id alone is not an access path: a member
 * reached through the wrong group resolves to nothing rather than to another group's transcript.
 *
 * <p>A question needing different inputs is a new run, not a turn. There is deliberately no way to
 * name a package, a revision, or a release here — the turn answers from what the run saved.
 */
@RestController
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
@RequestMapping("/api/ai/admin/instances/run-groups/{groupId}/members/{runId}/messages")
public final class InstanceDiscussionController {

    private final InstanceDiscussionService discussions;

    public InstanceDiscussionController(InstanceDiscussionService discussions) {
        this.discussions = Objects.requireNonNull(discussions, "discussions");
    }

    /**
     * One turn: the question, the answer, and how it ended.
     *
     * <p>A failed turn carries no bodies — nothing was stored for it — so both are null and the
     * code says why. That is deliberately visible rather than omitted.
     */
    public record TurnView(int sequenceNumber, String question, String answer,
                           String status, String failureCode) {}

    /**
     * The transcript, plus what it was answered by and what it has cost.
     *
     * <p>{@code estimatedCostUsd} is null when no turn has been measured, and
     * {@code usageQuality} is {@code UNAVAILABLE} when any turn was not — a total that omits an
     * unmeasured turn is an understatement, and says so rather than reading as complete.
     */
    public record DiscussionView(
            UUID runId, List<TurnView> turns, String provider, String model,
            String usageQuality, BigDecimal estimatedCostUsd) {}

    /** One question. There is no field here for an input the run was not already pinned to. */
    public record AskRequest(String question) {}

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public DiscussionView read(@RequestParam UUID brain,
                               @PathVariable UUID groupId, @PathVariable UUID runId) {
        return view(discussions.read(brain, runId));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DiscussionView ask(@RequestParam UUID brain,
                              @PathVariable UUID groupId, @PathVariable UUID runId,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key,
                              @RequestBody AskRequest request) {
        if (key == null || key.isBlank()) {
            throw new InstanceDiscussionService.DiscussionException(
                    InstanceDiscussionService.DiscussionException.Code.DISCUSSION_REQUEST_INVALID);
        }
        return view(discussions.ask(brain, runId,
                request == null ? null : request.question(), key));
    }

    private static DiscussionView view(InstanceDiscussionService.DiscussionView model) {
        return new DiscussionView(model.runId(),
                model.turns().stream()
                        .map(turn -> new TurnView(turn.sequenceNumber(), turn.question(),
                                turn.answer(), turn.status(), turn.failureCode()))
                        .toList(),
                model.provider(), model.model(), model.usageQuality(), model.estimatedCostUsd());
    }
}

/** Safe, payload-free error taxonomy for only the discussion routes. */
@RestControllerAdvice(assignableTypes = InstanceDiscussionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
final class InstanceDiscussionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InstanceDiscussionExceptionHandler.class);
    record ErrorResponse(String code) {}

    @ExceptionHandler(InstanceDiscussionService.DiscussionException.class)
    ResponseEntity<ErrorResponse> handle(InstanceDiscussionService.DiscussionException failure) {
        HttpStatus status = switch (failure.code()) {
            case DISCUSSION_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case DISCUSSION_RUN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RUN_NOT_SUCCEEDED, RUN_PROVENANCE_ABSENT, RUN_OUTPUT_ABSENT,
                 INSTANCE_RELEASE_NOT_PINNABLE -> HttpStatus.CONFLICT;
            // Refused rather than trimmed: the caller needs to know the turn was not answered
            // from less material, it was not answered at all.
            case DISCUSSION_CONTEXT_TOO_LARGE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case DISCUSSION_PROVIDER_FAILED -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(failure.code().name()));
    }

    @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> handleUnreadable(Exception failure) {
        return ResponseEntity.badRequest().body(new ErrorResponse("DISCUSSION_REQUEST_INVALID"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception failure) {
        // Class name and correlation id only. Not the message — that is the payload.
        log.error("Unexpected discussion request failure ({}) [{}]",
                failure.getClass().getSimpleName(), LabAuditService.correlationId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("DISCUSSION_REQUEST_FAILED"));
    }
}
