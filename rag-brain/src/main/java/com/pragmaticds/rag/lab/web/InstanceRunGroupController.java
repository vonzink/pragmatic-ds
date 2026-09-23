package com.pragmaticds.rag.lab.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.domain.LabRunPayload;
import com.pragmaticds.rag.lab.model.InstanceModelCatalogService;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.RunGroupCommand;
import com.pragmaticds.rag.lab.run.RunGroupPreflightService;
import com.pragmaticds.rag.lab.run.RunGroupService;
import com.pragmaticds.rag.lab.run.RunGroupStatusService;
import com.pragmaticds.rag.lab.run.domain.LabModelUsage;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.repository.LabModelUsageRepository;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.lab.service.InstanceRetentionService;
import com.pragmaticds.rag.lab.service.LabAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The brain-scoped control surface for run groups.
 *
 * <p><b>Preflight is explicitly non-mutating and takes no idempotency key.</b> It creates nothing,
 * so requiring a key would be theatre and would push clients into inventing one per keystroke.
 * Create and cancel both require one.
 *
 * <p><b>A listing can never become a way to read answers.</b> Only the single-group detail route
 * decrypts a member's output, and only for members that succeeded. Every other shape carries
 * identifiers, statuses, counts, and money.
 *
 * <p><b>Feature-off means unmapped.</b> Conditional on {@code ragbrain.instances}, so with the flag
 * absent these paths have no mapping and an authenticated admin gets a bare 404 that reveals
 * nothing about the flag or the configuration behind it.
 */
@RestController
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
@RequestMapping("/api/ai/admin/instances")
public final class InstanceRunGroupController {

    private final RunGroupPreflightService preflight;
    private final RunGroupService groupService;
    private final RunGroupStatusService status;
    private final InstanceRetentionService retention;
    private final InstanceModelCatalogService catalog;
    private final LabRunGroupRepository groups;
    private final LabRunRepository runs;
    private final LabModelUsageRepository usage;
    private final LabRunPayloadRepository payloads;
    /**
     * Only exists while the Lab is enabled, and only this route needs it: a deployment running
     * instances without the Lab still lists and cancels groups, it simply cannot open a stored
     * result, which is reported as an absent one rather than as a failure.
     */
    private final ObjectProvider<LabPayloadCipher> cipher;
    private final ObjectMapper mapper;

    public InstanceRunGroupController(RunGroupPreflightService preflight,
                                      RunGroupService groupService,
                                      RunGroupStatusService status,
                                      InstanceRetentionService retention,
                                      InstanceModelCatalogService catalog,
                                      LabRunGroupRepository groups,
                                      LabRunRepository runs,
                                      LabModelUsageRepository usage,
                                      LabRunPayloadRepository payloads,
                                      ObjectProvider<LabPayloadCipher> cipher,
                                      ObjectMapper mapper) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.groupService = Objects.requireNonNull(groupService, "groupService");
        this.status = Objects.requireNonNull(status, "status");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Every model this deployment can offer: configuration intersected with credentials. */
    @GetMapping(value = "/model-catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<InstanceRunGroupDtos.CatalogModelView> modelCatalog() {
        return catalog.available().stream().map(InstanceRunGroupDtos::catalogModel).toList();
    }

    /**
     * Prices a submission without creating anything.
     *
     * <p>No idempotency key: this writes nothing, so there is nothing to make idempotent.
     */
    @PostMapping(value = "/run-groups/preflight",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InstanceRunGroupDtos.PreflightView preflight(
            @RequestParam UUID brain,
            @RequestBody InstanceRunGroupDtos.CreateRunGroupRequest request) {
        return InstanceRunGroupDtos.preflight(
                preflight.preflight(InstanceRunGroupDtos.command(brain, request)));
    }

    /** Creates the group and queues its members, or replays the one this key already produced. */
    @PostMapping(value = "/run-groups",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InstanceRunGroupDtos.CreatedRunGroupView> create(
            @RequestParam UUID brain,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody InstanceRunGroupDtos.CreateRunGroupRequest request) {
        requireKey(key);
        RunGroupCommand command = InstanceRunGroupDtos.command(brain, request);
        RunGroupService.CreatedRunGroup created = groupService.create(command, key);
        // 200 on replay makes the idempotent case visible without reading the body.
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new InstanceRunGroupDtos.CreatedRunGroupView(
                        created.groupId(), created.created(), created.memberRunIds()));
    }

    /** The brain's groups, newest first, optionally filtered by status. */
    @GetMapping(value = "/run-groups", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<InstanceRunGroupDtos.RunGroupSummaryView> list(
            @RequestParam UUID brain,
            @RequestParam(value = "status", required = false) String statusFilter) {
        List<LabRunGroup> found = statusFilter == null || statusFilter.isBlank()
                ? groups.findByBrainIdOrderByCreatedAtDesc(brain)
                : groups.findByBrainIdAndStatusOrderByCreatedAtDesc(brain, groupStatus(statusFilter));
        return found.stream()
                .map(group -> InstanceRunGroupDtos.summary(group,
                        runs.findByRunGroupIdOrderByMemberIndexAsc(group.getId()).size()))
                .toList();
    }

    /**
     * One group with its members, decrypting the output of those that succeeded.
     *
     * <p>The only route in this controller that reads a result, and the only one that can: an
     * authorized detail request for one group is a materially narrower surface than a listing.
     */
    @GetMapping(value = "/run-groups/{groupId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InstanceRunGroupDtos.RunGroupDetailView detail(
            @RequestParam UUID brain, @PathVariable UUID groupId) {
        LabRunGroup group = groups.findByIdAndBrainId(groupId, brain)
                .orElseThrow(() -> new RunGroupWebException(
                        RunGroupWebException.Code.RUN_GROUP_NOT_FOUND));
        List<LabRun> members = runs.findByRunGroupIdOrderByMemberIndexAsc(groupId);

        Map<UUID, LabModelUsage> usageByRun = new HashMap<>();
        usage.findByRunIdInAndBrainId(members.stream().map(LabRun::getId).toList(), brain)
                .forEach(row -> usageByRun.put(row.getRunId(), row));

        List<InstanceRunGroupDtos.MemberDetailView> views = new ArrayList<>(members.size());
        for (LabRun member : members) {
            views.add(InstanceRunGroupDtos.member(member, usageByRun.get(member.getId()),
                    result(member, brain)));
        }
        return new InstanceRunGroupDtos.RunGroupDetailView(
                InstanceRunGroupDtos.summary(group, members.size()), views);
    }

    /** Cancels the members that have not started; reports the ones already running. */
    @PostMapping(value = "/run-groups/{groupId}/cancel",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InstanceRunGroupDtos.CancellationView cancel(
            @RequestParam UUID brain, @PathVariable UUID groupId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireKey(key);
        try {
            RunGroupStatusService.CancellationOutcome outcome = status.cancel(brain, groupId);
            return new InstanceRunGroupDtos.CancellationView(
                    outcome.cancelledMembers(), outcome.stillProcessingMembers());
        } catch (IllegalArgumentException absent) {
            throw new RunGroupWebException(RunGroupWebException.Code.RUN_GROUP_NOT_FOUND);
        }
    }

    /**
     * Authorized idempotent purge of one terminal group and everything its runs own. Naturally
     * idempotent like the legacy run purge, so no idempotency key is demanded; an active group
     * refuses with {@code GROUP_STILL_ACTIVE} rather than being torn down mid-flight.
     */
    @DeleteMapping(value = "/run-groups/{groupId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public InstanceRunGroupDtos.GroupPurgeView purge(
            @RequestParam UUID brain, @PathVariable UUID groupId) {
        InstanceRetentionService.GroupPurgeOutcome outcome = retention.purgeGroup(brain, groupId);
        return new InstanceRunGroupDtos.GroupPurgeView(
                outcome.deleted(), outcome.runsDeleted(), outcome.connectorContextDeleted());
    }

    // ================================================================ internals

    /**
     * A succeeded member's decrypted output, or null.
     *
     * <p>Anything that goes wrong reading a payload yields null rather than an error: a member's
     * status and cost are still worth showing when its stored output cannot be opened, and the
     * decryption failure's cause carries the run's own identifiers.
     */
    private Map<String, Object> result(LabRun member, UUID brain) {
        if (member.getStatus() != LabRun.Status.SUCCEEDED) {
            return null;
        }
        LabPayloadCipher opener = cipher.getIfAvailable();
        if (opener == null) {
            return null;
        }
        try {
            Optional<LabRunPayload> payload = payloads.findByRunIdAndPayloadType(
                    member.getId(), LabRunPayload.PayloadType.ANALYSIS_OUTPUT);
            if (payload.isEmpty()) {
                return null;
            }
            byte[] plaintext = opener.open(brain, member.getId(), payload.get().getId(),
                    LabPayloadCipher.RecordType.ANALYSIS_OUTPUT,
                    new LabPayloadCipher.SealedPayload(
                            payload.get().getNonce(), payload.get().getCiphertext()));
            return mapper.readValue(plaintext, new TypeReference<Map<String, Object>>() {});
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static LabRunGroup.Status groupStatus(String value) {
        try {
            return LabRunGroup.Status.valueOf(value);
        } catch (IllegalArgumentException unrecognised) {
            throw new RunGroupWebException(RunGroupWebException.Code.RUN_GROUP_REQUEST_INVALID);
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new RunGroupWebException(RunGroupWebException.Code.IDEMPOTENCY_KEY_REQUIRED);
        }
    }

    static final class RunGroupWebException extends RuntimeException {
        enum Code {
            IDEMPOTENCY_KEY_REQUIRED,
            RUN_GROUP_REQUEST_INVALID,
            RUN_GROUP_NOT_FOUND,
            RUN_GROUP_REQUEST_FAILED
        }

        private final Code code;

        RunGroupWebException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        Code code() { return code; }
    }
}

/** Safe, payload-free error taxonomy for only the run-group routes. */
@RestControllerAdvice(assignableTypes = InstanceRunGroupController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
final class InstanceRunGroupExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InstanceRunGroupExceptionHandler.class);
    record ErrorResponse(String code, List<String> blockingCodes) {}

    @ExceptionHandler(InstanceRunGroupController.RunGroupWebException.class)
    ResponseEntity<ErrorResponse> handleWeb(
            InstanceRunGroupController.RunGroupWebException failure) {
        HttpStatus status = switch (failure.code()) {
            case IDEMPOTENCY_KEY_REQUIRED, RUN_GROUP_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case RUN_GROUP_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RUN_GROUP_REQUEST_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, failure.code().name(), List.of());
    }

    /**
     * A blocked submission answers 422 with every reason.
     *
     * <p>Not 400: the request was well-formed and the client could not have known it would be
     * refused, so the useful answer is the list of what to fix.
     */
    /** An active group is a conflict with its own execution, not a bad request. */
    @ExceptionHandler(InstanceRetentionService.RetentionException.class)
    ResponseEntity<ErrorResponse> handleRetention(
            InstanceRetentionService.RetentionException failure) {
        return response(HttpStatus.CONFLICT, failure.code().name(), List.of());
    }

    @ExceptionHandler(RunGroupService.RunGroupException.class)
    ResponseEntity<ErrorResponse> handleGroup(RunGroupService.RunGroupException failure) {
        HttpStatus status = switch (failure.code()) {
            case RUN_GROUP_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
            case RUN_GROUP_BLOCKED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return response(status, failure.code().name(), failure.blockingCodes());
    }

    @ExceptionHandler(InstanceModelCatalogService.ModelCatalogException.class)
    ResponseEntity<ErrorResponse> handleCatalog(
            InstanceModelCatalogService.ModelCatalogException failure) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, failure.code().name(), List.of());
    }

    @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> handleUnreadable(Exception failure) {
        return response(HttpStatus.BAD_REQUEST, "RUN_GROUP_REQUEST_INVALID", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception failure) {
        // Class name and correlation id only. Not the message — that is the payload.
        log.error("Unexpected run group request failure ({}) [{}]",
                failure.getClass().getSimpleName(), LabAuditService.correlationId());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "RUN_GROUP_REQUEST_FAILED", List.of());
    }

    private static ResponseEntity<ErrorResponse> response(
            HttpStatus status, String code, List<String> blockingCodes) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, blockingCodes));
    }
}
