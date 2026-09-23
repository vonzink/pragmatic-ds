package com.pragmaticds.rag.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.dto.DocumentManagerInstanceDtos;
import com.pragmaticds.rag.lab.connect.DocumentManagerRunCommand;
import com.pragmaticds.rag.lab.connect.DocumentManagerRunService;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.domain.LabRunPayload;
import com.pragmaticds.rag.lab.instance.InstanceRegistryService;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.parsed.RegistrationLoanFactsService;
import com.pragmaticds.rag.service.analyze.calc.LoanBasis;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.RunGroupService;
import com.pragmaticds.rag.lab.run.RunGroupService.RunGroupException;
import com.pragmaticds.rag.lab.run.domain.LabConnectorRunGroupContext;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.domain.LabModelUsage;
import com.pragmaticds.rag.lab.run.repository.LabConnectorRunGroupContextRepository;
import com.pragmaticds.rag.lab.run.repository.LabModelUsageRepository;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.connect.ConnectorAuthService;
import com.pragmaticds.rag.service.connect.ConnectorPermission;
import com.pragmaticds.rag.service.connect.ConnectorScope;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The Document Manager surface: launch a run of the live release, and poll it.
 *
 * <p>Everything here sits behind three gates stacked in order. The connector interceptor
 * authenticates every {@code /api/connect/**} request before this controller exists to it; the
 * rate limiter covers the same prefix; and each route then authorizes with
 * {@code requireForTenant} — scope, permission, brain, tenant, peer host and origin together.
 * Browser or session claims are never consulted; tenant authority comes from the token's
 * allow-list alone.
 *
 * <p><b>POST answers {@code 202}, never waits.</b> Creating a group schedules work; the dispatcher
 * runs it under its own concurrency limits. The response carries the polling URL (header and
 * body), the member ids, and the priced estimates — enough for an integrator to decide whether to
 * keep waiting, and nothing that required a provider call.
 *
 * <p><b>GET authorizes against the ownership row, then answers 404.</b> The group must belong to
 * the path's brain, the authenticated connector must be the one that created it, and the
 * {@code X-Tenant-Id} must equal the stored tenant. Any mismatch is the same {@code 404} as a
 * group that never existed, because a {@code 403} would confirm to the wrong tenant that the group
 * is real.
 *
 * <p>The whole surface is absent unless {@code ragbrain.instances.connector-enabled} is true.
 * The flag is one-directional on purpose: enabling the admin surface never opens this one as a
 * side effect, while this one additionally requires the admin flag because every service it
 * composes lives behind it.
 */
@RestController
// Both flags, deliberately: every service this depends on — the registry, the resolvers, the
// run-group machinery — is gated on `enabled`, so `connector-enabled` alone would fail bean
// wiring at boot. The two-name condition keeps that from ever being a wiring stacktrace, and
// InstanceControlStartupValidator turns the misconfiguration into a startup refusal that names
// the property keys.
@ConditionalOnProperty(prefix = "ragbrain.instances", name = {"enabled", "connector-enabled"},
        havingValue = "true")
@RequestMapping("/api/connect/v1/brains/{brain}")
public class DocumentManagerInstanceController {

    private final BrainRepository brains;
    private final ConnectorAuthService auth;
    private final DocumentManagerRunService runs;
    private final LabRunGroupRepository groups;
    private final LabRunRepository members;
    private final LabModelUsageRepository usage;
    private final LabConnectorRunGroupContextRepository contexts;
    private final LabRunPayloadRepository payloads;
    private final ObjectProvider<LabPayloadCipher> cipher;
    private final ObjectMapper mapper;

    public DocumentManagerInstanceController(BrainRepository brains,
                                             ConnectorAuthService auth,
                                             DocumentManagerRunService runs,
                                             LabRunGroupRepository groups,
                                             LabRunRepository members,
                                             LabModelUsageRepository usage,
                                             LabConnectorRunGroupContextRepository contexts,
                                             LabRunPayloadRepository payloads,
                                             ObjectProvider<LabPayloadCipher> cipher,
                                             ObjectMapper mapper) {
        this.brains = Objects.requireNonNull(brains, "brains");
        this.auth = Objects.requireNonNull(auth, "auth");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.members = Objects.requireNonNull(members, "members");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @PostMapping(value = "/instances/{instance}/run-groups",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentManagerInstanceDtos.AcceptedRunGroup> start(
            @PathVariable("brain") String brainSlug,
            @PathVariable String instance,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody DocumentManagerInstanceDtos.StartLiveInstanceRequest request) {
        Brain brain = requireBrain(brainSlug);
        if (request == null) {
            throw new RunGroupException(RunGroupException.Code.RUN_GROUP_REQUEST_INVALID);
        }
        ConnectorAuthService.AuthorizedConnector connector = auth.requireForTenant(
                authorization, ConnectorScope.INSTANCE_RUN, ConnectorPermission.INSTANCE_RUN_LIVE,
                brain.getId(), request.tenantId(), host, origin, "INSTANCE_RUN");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new RunGroupException(RunGroupException.Code.RUN_GROUP_REQUEST_INVALID);
        }

        RunGroupService.CreatedRunGroup created = runs.start(new DocumentManagerRunCommand(
                        connector.client().getId(), brain.getId(), instance,
                        connector.tenantId(), request.externalRequestId(), request.packageId(),
                        request.revision() == null ? 0 : request.revision(),
                        request.selectedSourceIds(), loanBasis(request.loanFacts()),
                        request.subjectScope()),
                idempotencyKey);

        String statusUrl = "/api/connect/v1/brains/" + brainSlug
                + "/instance-run-groups/" + created.groupId();
        String status = groups.findByIdAndBrainId(created.groupId(), brain.getId())
                .map(group -> group.getStatus().name())
                .orElse(LabRunGroup.Status.QUEUED.name());

        List<DocumentManagerInstanceDtos.MemberEstimateDto> estimates =
                usage.findByRunIdInAndBrainId(created.memberRunIds(), brain.getId()).stream()
                        .map(DocumentManagerInstanceController::estimate)
                        .toList();

        // 202 whether created or replayed: either way the work is accepted and the answer is at
        // the polling URL. The response never waits for a provider.
        return ResponseEntity.accepted()
                .location(URI.create(statusUrl))
                .body(new DocumentManagerInstanceDtos.AcceptedRunGroup(
                        created.groupId(), created.memberRunIds(), status, statusUrl, estimates));
    }

    @GetMapping(value = "/instance-run-groups/{runGroupId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DocumentManagerInstanceDtos.RunGroupStatusDto poll(
            @PathVariable("brain") String brainSlug,
            @PathVariable UUID runGroupId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Host", required = false) String host,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        Brain brain = requireBrain(brainSlug);
        ConnectorAuthService.AuthorizedConnector connector = auth.requireForTenant(
                authorization, ConnectorScope.INSTANCE_RUN_READ,
                ConnectorPermission.INSTANCE_RUN_READ,
                brain.getId(), tenantId, host, origin, "INSTANCE_RUN_READ");

        LabRunGroup group = groups.findByIdAndBrainId(runGroupId, brain.getId())
                .orElseThrow(DocumentManagerInstanceController::notFound);
        LabConnectorRunGroupContext context = contexts.findById(group.getId())
                .orElseThrow(DocumentManagerInstanceController::notFound);
        // Owner and tenant must both match, and a mismatch answers exactly like absence: a 403
        // here would confirm to the wrong tenant that the group exists.
        if (!context.getConnectorClientId().equals(connector.client().getId())
                || !context.getTenantId().equals(connector.tenantId())) {
            throw notFound();
        }

        List<LabRun> rows = members.findByRunGroupIdOrderByMemberIndexAsc(group.getId());
        Map<UUID, LabModelUsage> usageByRun = new HashMap<>();
        usage.findByRunIdInAndBrainId(rows.stream().map(LabRun::getId).toList(), brain.getId())
                .forEach(row -> usageByRun.put(row.getRunId(), row));

        List<DocumentManagerInstanceDtos.MemberStatusDto> memberViews = rows.stream()
                .map(member -> member(member, usageByRun.get(member.getId()), brain.getId()))
                .toList();

        return new DocumentManagerInstanceDtos.RunGroupStatusDto(
                group.getId(), group.getStatus().name(), group.getCreatedAt(),
                group.getTerminalAt(), memberViews);
    }

    // ================================================================ internals

    private Brain requireBrain(String slug) {
        return brains.findBySlug(slug)
                .filter(Brain::isActive)
                .orElseThrow(DocumentManagerInstanceController::notFound);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }

    private DocumentManagerInstanceDtos.MemberStatusDto member(LabRun run, LabModelUsage used,
                                                               UUID brainId) {
        Long latency = null;
        if (run.getTerminalAt() != null && run.getCreatedAt() != null) {
            latency = Duration.between(run.getCreatedAt(), run.getTerminalAt()).toMillis();
        }
        return new DocumentManagerInstanceDtos.MemberStatusDto(
                run.getId(), run.getStatus().name(),
                // The failure taxonomy is value-free by design; the code is the disclosure.
                run.getFailureCode(),
                run.getRequestedProvider(), run.getRequestedModel(), run.getPricingVersionId(),
                used == null ? 0 : used.getExpectedInputMin(),
                used == null ? 0 : used.getExpectedInputMax(),
                used == null ? 0 : used.getExpectedOutputMin(),
                used == null ? 0 : used.getExpectedOutputMax(),
                used == null ? null : used.getExpectedCostUsdMin(),
                used == null ? null : used.getExpectedCostUsdMax(),
                used == null ? null : used.getEstimateQuality().name(),
                used == null ? null : used.getActualInputTokens(),
                used == null ? null : used.getActualCachedTokens(),
                used == null ? null : used.getActualOutputTokens(),
                used == null ? null : used.getActualTotalTokens(),
                used == null ? null : used.getActualCostUsd(),
                used == null ? null : used.getUsageQuality().name(),
                run.getCreatedAt(), run.getTerminalAt(), latency,
                result(run, brainId));
    }

    /** Decrypted output for a member that succeeded; null otherwise, and null on any failure. */
    private Map<String, Object> result(LabRun member, UUID brainId) {
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
            byte[] plaintext = opener.open(brainId, member.getId(), payload.get().getId(),
                    LabPayloadCipher.RecordType.ANALYSIS_OUTPUT,
                    new LabPayloadCipher.SealedPayload(
                            payload.get().getNonce(), payload.get().getCiphertext()));
            return mapper.readValue(plaintext, new TypeReference<Map<String, Object>>() {});
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static DocumentManagerInstanceDtos.MemberEstimateDto estimate(LabModelUsage used) {
        return new DocumentManagerInstanceDtos.MemberEstimateDto(
                used.getRunId(), used.getProvider(), used.getModel(), used.getPricingVersionId(),
                used.getExpectedInputMin(), used.getExpectedInputMax(),
                used.getExpectedOutputMin(), used.getExpectedOutputMax(),
                used.getExpectedCostUsdMin(), used.getExpectedCostUsdMax(),
                used.getEstimateQuality().name());
    }

    /**
     * The loan facts this launch carried, or null when it carried none.
     *
     * <p>Values pass through exactly as sent. An unrecognized program or purpose becomes null and
     * the threshold then reports itself unavailable — a caller sending "Conventional" gets a
     * visible refusal in the report rather than a silently applied guess.
     */
    private static LoanBasis loanBasis(DocumentManagerInstanceDtos.LoanFactsDto facts) {
        if (facts == null) {
            return null;
        }
        return new LoanBasis(
                LoanBasis.programOf(facts.program()),
                LoanBasis.purposeOf(facts.loanPurpose()),
                facts.qualifyingMonthlyIncome(),
                facts.adjustedValue());
    }

}

/**
 * Safe, payload-free failures for only the Document Manager routes.
 *
 * <p>Every mapping answers with a stable code and, for a blocked submission, the blocking codes —
 * never a provider body, an engine URI, a tenant, or anything from the request.
 */
@RestControllerAdvice(assignableTypes = DocumentManagerInstanceController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
// Both flags, deliberately: every service this depends on — the registry, the resolvers, the
// run-group machinery — is gated on `enabled`, so `connector-enabled` alone would fail bean
// wiring at boot. The two-name condition keeps that from ever being a wiring stacktrace, and
// InstanceControlStartupValidator turns the misconfiguration into a startup refusal that names
// the property keys.
@ConditionalOnProperty(prefix = "ragbrain.instances", name = {"enabled", "connector-enabled"},
        havingValue = "true")
class DocumentManagerInstanceExceptionHandler {

    private final org.springframework.beans.factory.ObjectProvider<
            com.pragmaticds.rag.lab.ops.InstanceControlMetrics> metrics;

    DocumentManagerInstanceExceptionHandler(
            org.springframework.beans.factory.ObjectProvider<
                    com.pragmaticds.rag.lab.ops.InstanceControlMetrics> metrics) {
        this.metrics = metrics;
    }

    /** Counts one refusal by its public code — the exact word the caller was told, no more. */
    private void count(String code) {
        com.pragmaticds.rag.lab.ops.InstanceControlMetrics present = metrics.getIfAvailable();
        if (present != null) {
            present.connectorRefusal(code);
        }
    }

    @ExceptionHandler(RunGroupException.class)
    ResponseEntity<DocumentManagerInstanceDtos.ErrorResponse> handleRunGroup(
            RunGroupException failure) {
        HttpStatus status = switch (failure.code()) {
            case RUN_GROUP_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            // The key exists and is attached to something else. 409, not 422: the request might
            // be fine under a fresh key.
            case IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
            case RUN_GROUP_BLOCKED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        count(failure.code().name());
        return ResponseEntity.status(status).body(new DocumentManagerInstanceDtos.ErrorResponse(
                failure.code().name(), failure.blockingCodes()));
    }

    @ExceptionHandler(InstanceRegistryService.InstanceException.class)
    ResponseEntity<DocumentManagerInstanceDtos.ErrorResponse> handleInstance(
            InstanceRegistryService.InstanceException failure) {
        HttpStatus status = failure.code()
                == InstanceRegistryService.InstanceException.Code.INSTANCE_NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        count(failure.code().name());
        return ResponseEntity.status(status)
                .body(new DocumentManagerInstanceDtos.ErrorResponse(
                        failure.code().name(), List.of()));
    }

    @ExceptionHandler(InstanceReleaseResolver.ReleaseResolutionException.class)
    ResponseEntity<DocumentManagerInstanceDtos.ErrorResponse> handleRelease(
            InstanceReleaseResolver.ReleaseResolutionException failure) {
        // No live release is a deployment state, not a caller mistake: 409 says try again once
        // something is promoted, where 404 would read as a wrong URL.
        count(failure.code().name());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new DocumentManagerInstanceDtos.ErrorResponse(
                        failure.code().name(), List.of()));
    }

    @ExceptionHandler(ParsedDataResolver.ParsedDataException.class)
    ResponseEntity<DocumentManagerInstanceDtos.ErrorResponse> handleParsedData(
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
        count(failure.code().name());
        return ResponseEntity.status(status)
                .body(new DocumentManagerInstanceDtos.ErrorResponse(
                        failure.code().name(), List.of()));
    }

    /**
     * Loan facts that disagree with what this package already carries.
     *
     * <p>A conflict rather than a caller error: the request is well-formed, and the stored facts
     * win because a run already submitted against this registration was priced under them.
     */
    @ExceptionHandler(RegistrationLoanFactsService.LoanFactsException.class)
    ResponseEntity<DocumentManagerInstanceDtos.ErrorResponse> handleLoanFacts(
            RegistrationLoanFactsService.LoanFactsException failure) {
        count(failure.code().name());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new DocumentManagerInstanceDtos.ErrorResponse(
                        failure.code().name(), List.of()));
    }

    @ExceptionHandler(CorpusSnapshotService.SnapshotException.class)
    ResponseEntity<DocumentManagerInstanceDtos.ErrorResponse> handleSnapshot(
            CorpusSnapshotService.SnapshotException failure) {
        // A stale collection version or a missing collection is the live release's problem to fix
        // by promoting a release that pins current versions — a conflict, not a caller error.
        count(failure.code().name());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new DocumentManagerInstanceDtos.ErrorResponse(
                        failure.code().name(), List.of()));
    }

    @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<DocumentManagerInstanceDtos.ErrorResponse> handleUnreadable(Exception failure) {
        count("RUN_GROUP_REQUEST_INVALID");
        return ResponseEntity.badRequest()
                .body(new DocumentManagerInstanceDtos.ErrorResponse(
                        "RUN_GROUP_REQUEST_INVALID", List.of()));
    }
}
