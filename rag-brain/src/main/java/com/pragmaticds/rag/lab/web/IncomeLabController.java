package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.engine.DocumentEngineClient;
import com.pragmaticds.rag.lab.release.IncomeLabReleaseService;
import com.pragmaticds.rag.lab.service.IncomeLabService;
import com.pragmaticds.rag.service.BrainResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * The Income Lab prototype's HTTP surface.
 *
 * <p><b>Auth posture: inherited, not invented.</b> Every route lives under {@code /api/ai/admin/**},
 * which {@code AdminApiKeyFilter} already gates — a missing or wrong {@code X-Admin-Api-Key} is 401
 * before any method here runs. This plan does not modify that filter, {@code AnalyzeApiKeyFilter},
 * the public matchers, or rate-limit routing; the Lab simply moved INTO the existing gate. The only
 * CORS change anywhere is one added allowed header, {@code Idempotency-Key}.
 *
 * <p><b>Feature-off means unmapped, not disabled.</b> The bean is conditional on
 * {@code ragbrain.lab.enabled}, so with the flag off these paths have no mapping at all: an
 * authenticated caller gets a bare 404 that reveals nothing about the flag, the engine, or any
 * other configuration.
 *
 * <p><b>The upload is forwarded, never read.</b> {@link #register} hands the service
 * {@code MultipartFile.getSize()} and {@code getResource()} — no {@code getBytes()}, no
 * application-owned temp file, and no member anywhere that could carry the browser's filename or
 * declared MIME type. The servlet container's bounded request-scoped spool is transient
 * infrastructure; it is not a Lab copy of the document.
 *
 * <p>This class contains no business rules. Ordering, idempotency, scoping, and refusals all belong
 * to {@link IncomeLabService}; what lives here is the HTTP shape and the brain resolution every
 * other admin controller already does.
 */
@RestController
@ConditionalOnProperty(prefix = "ragbrain.lab", name = "enabled", havingValue = "true")
@RequestMapping("/api/ai/admin/lab")
public class IncomeLabController {

    /** The one Lab instance this prototype hosts. */
    private static final String INCOME = IncomeLabReleaseService.INCOME_INSTANCE_SLUG;

    private final IncomeLabService lab;
    private final BrainResolver brainResolver;

    public IncomeLabController(IncomeLabService lab, BrainResolver brainResolver) {
        this.lab = lab;
        this.brainResolver = brainResolver;
    }

    // ================================================================ instances

    @GetMapping(value = "/instances", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LabDtos.InstancesResponse> instances(
            @RequestParam(value = "brain", required = false) String brain) {
        return ResponseEntity.ok(lab.instances(brainId(brain)));
    }

    // ================================================================ documents

    /**
     * Registers exactly one original with Document Engine.
     *
     * <p>Zero or multiple files are refused here, before the service and therefore before any
     * engine call: the engine's current prototype contract is one original per package, and
     * additional documents are added through separate uploads and separate packages.
     */
    @PostMapping(value = "/instances/{instance}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LabDtos.RegistrationResponse> register(
            @PathVariable("instance") String instance,
            @RequestParam(value = "brain", required = false) String brain,
            @RequestParam(value = "file", required = false) List<MultipartFile> files,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        requireIdempotencyKey(idempotencyKey);
        if (files == null || files.size() != 1) {
            throw new IncomeLabService.LabRequestException(
                    IncomeLabService.LabRequestException.Code.UPLOAD_FILE_COUNT_INVALID,
                    java.util.Map.of("files", files == null ? 0 : files.size(), "expected", 1));
        }
        MultipartFile file = files.get(0);
        // Size from getSize(), stream from getResource(): the bytes are never materialized here.
        DocumentEngineClient.EngineUpload upload =
                new DocumentEngineClient.EngineUpload(file.getSize(), file.getResource());

        LabDtos.RegistrationResponse response =
                lab.registerDocument(brainId(brain), instance, upload, idempotencyKey);
        return ResponseEntity.status(response.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(response);
    }

    @GetMapping(value = "/documents/{packageId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LabDtos.DocumentStatusResponse> documentStatus(
            @PathVariable("packageId") UUID packageId,
            @RequestParam(value = "brain", required = false) String brain,
            @RequestParam(value = "jobId", required = false) UUID jobId) {
        return ResponseEntity.ok(lab.documentStatus(brainId(brain), packageId, jobId));
    }

    @GetMapping(value = "/documents/{packageId}/envelope",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LabDtos.EnvelopeResponse> envelope(
            @PathVariable("packageId") UUID packageId,
            @RequestParam(value = "brain", required = false) String brain,
            @RequestParam(value = "revision", required = false) Integer revision) {
        return ResponseEntity.ok(lab.envelope(brainId(brain), packageId, revision));
    }

    // ================================================================ runs

    @PostMapping(value = "/instances/{instance}/runs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LabDtos.RunResponse> startRun(
            @PathVariable("instance") String instance,
            @RequestParam(value = "brain", required = false) String brain,
            @RequestBody LabDtos.RunRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        LabDtos.RunResponse response =
                lab.startRun(brainId(brain), instance, request, idempotencyKey);
        // 200 on replay makes the idempotent case visible to a client without reading the body.
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LabDtos.RunHistoryResponse> history(
            @RequestParam(value = "brain", required = false) String brain,
            @RequestParam(value = "instance", defaultValue = INCOME) String instance) {
        return ResponseEntity.ok(lab.history(brainId(brain), instance));
    }

    @GetMapping(value = "/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LabDtos.RunResponse> runDetail(
            @PathVariable("runId") UUID runId,
            @RequestParam(value = "brain", required = false) String brain) {
        return ResponseEntity.ok(lab.runDetail(brainId(brain), runId));
    }

    /** Authorized idempotent purge. It never deletes the Document Engine package. */
    @DeleteMapping(value = "/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LabDtos.PurgeResponse> purge(
            @PathVariable("runId") UUID runId,
            @RequestParam(value = "brain", required = false) String brain) {
        return ResponseEntity.ok(lab.purgeRun(brainId(brain), runId));
    }

    // ================================================================ discussion

    /**
     * Appends one run-pinned discussion turn. The child inference reuses this run's exact inputs;
     * a question needing different inputs requires a NEW run, which is why no package, revision, or
     * release can be named here.
     */
    @PostMapping(value = "/runs/{runId}/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LabDtos.DiscussionResponse> postMessage(
            @PathVariable("runId") UUID runId,
            @RequestParam(value = "brain", required = false) String brain,
            @RequestBody LabDtos.MessageRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        LabDtos.DiscussionResponse response =
                lab.postMessage(brainId(brain), runId, request, idempotencyKey);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    /** The read sibling of the route above: the stored, decrypted transcript for one run. */
    @GetMapping(value = "/runs/{runId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LabDtos.DiscussionResponse> discussion(
            @PathVariable("runId") UUID runId,
            @RequestParam(value = "brain", required = false) String brain) {
        return ResponseEntity.ok(lab.discussion(brainId(brain), runId));
    }

    // ================================================================ helpers

    /** The same brain resolution every other admin controller performs. */
    private UUID brainId(String brain) {
        return brainResolver.resolve(brain).getId();
    }

    /**
     * Every mutating Lab route requires {@code Idempotency-Key}, refused at the edge.
     *
     * <p>Duplicated with {@link IncomeLabService}'s own check on purpose: this one is the ROUTE's
     * requirement — an HTTP caller that omits the header gets a 400 before any brain is resolved or
     * any collaborator runs — while the service's is an invariant that holds for any caller,
     * including a future non-HTTP one. Neither is redundant with the other.
     */
    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IncomeLabService.LabRequestException(
                    IncomeLabService.LabRequestException.Code.IDEMPOTENCY_KEY_REQUIRED);
        }
    }
}
