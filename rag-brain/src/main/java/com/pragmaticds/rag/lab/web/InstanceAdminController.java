package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceCommandResult;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceRegistryService;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.InstanceSnapshotService;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.repository.LabInstanceCommandResultRepository;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

/** Generalized, explicitly brain-scoped administrative registry surface. */
@RestController
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
@RequestMapping("/api/ai/admin/instances")
public final class InstanceAdminController {
    private final InstanceRegistryService registry;
    private final InstanceSnapshotService snapshots;
    private final LabIdempotencyService idempotency;
    private final LabInstanceCommandResultRepository results;

    public InstanceAdminController(InstanceRegistryService registry, InstanceSnapshotService snapshots,
                                   LabIdempotencyService idempotency,
                                   LabInstanceCommandResultRepository results) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.results = Objects.requireNonNull(results, "results");
    }

    @GetMapping
    public List<InstanceAdminDtos.InstanceSummary> list(@RequestParam UUID brain) {
        return snapshots.list(brain).stream().map(InstanceAdminDtos::summary).toList();
    }

    @GetMapping("/{slug}")
    public InstanceAdminDtos.InstanceDetail read(@RequestParam UUID brain, @PathVariable String slug) {
        return detail(new InstanceKey(brain, slug));
    }

    @PatchMapping("/{slug}")
    public InstanceAdminDtos.InstanceDetail update(@RequestParam UUID brain, @PathVariable String slug,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody InstanceAdminDtos.UpdateInstanceRequest request) {
        requireKey(key);
        validate(request);
        InstanceKey instance = new InstanceKey(brain, slug);
        String displayName = Objects.requireNonNull(request.displayName(), "displayName");
        String purpose = Objects.requireNonNull(request.purpose(), "purpose");
        return idempotent(brain, "instance.update", key,
                canonical("PATCH", brain.toString(), slug, displayName, purpose),
                () -> {
                    registry.updateMetadata(instance, displayName, purpose);
                    return store(detail(instance));
                });
    }

    @PostMapping("/{slug}/disable")
    public InstanceAdminDtos.InstanceDetail disable(@RequestParam UUID brain, @PathVariable String slug,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireKey(key);
        InstanceKey instance = new InstanceKey(brain, slug);
        return idempotent(brain, "instance.disable", key,
                canonical("POST", brain.toString(), slug, "disable"),
                () -> {
                    registry.disable(instance);
                    return store(detail(instance));
                });
    }

    @PostMapping("/{slug}/restore")
    public InstanceAdminDtos.InstanceDetail restore(@RequestParam UUID brain, @PathVariable String slug,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        requireKey(key);
        InstanceKey instance = new InstanceKey(brain, slug);
        return idempotent(brain, "instance.restore", key,
                canonical("POST", brain.toString(), slug, "restore"),
                () -> {
                    registry.restore(instance);
                    return store(detail(instance));
                });
    }

    @GetMapping("/{slug}/releases")
    public List<InstanceAdminDtos.ReleaseSummary> releases(@RequestParam UUID brain,
                                                             @PathVariable String slug) {
        return snapshots.detail(new InstanceKey(brain, slug)).history().stream().map(InstanceAdminDtos::release).toList();
    }

    private InstanceAdminDtos.InstanceDetail detail(InstanceKey key) {
        return InstanceAdminDtos.detail(snapshots.detail(key));
    }

    private InstanceAdminDtos.InstanceDetail idempotent(UUID brain, String operation, String key,
            String canonicalRequest, java.util.function.Supplier<MutationResult> action) {
        return idempotency.execute(new LabIdempotencyService.IdempotentCommand<>(brain, operation, key,
                sha256(canonicalRequest), action,
                result -> new LabIdempotencyService.IdempotencyResult("INSTANCE_COMMAND_RESULT", result.id(), 1L),
                receipt -> replay(brain, receipt))).detail();
    }

    private MutationResult store(InstanceAdminDtos.InstanceDetail detail) {
        LabInstanceCommandResult saved = results.saveAndFlush(InstanceAdminDtos.result(detail));
        return new MutationResult(saved.getId(), InstanceAdminDtos.detail(saved));
    }

    private MutationResult replay(UUID brain, LabIdempotencyService.IdempotencyResult receipt) {
        if (!"INSTANCE_COMMAND_RESULT".equals(receipt.kind()) || receipt.id() == null
                || receipt.version() == null || receipt.version() != 1L) {
            throw new InstanceAdminException(InstanceAdminException.Code.INSTANCE_REQUEST_FAILED);
        }
        LabInstanceCommandResult result = results.findByIdAndBrainId(receipt.id(), brain)
                .orElseThrow(() -> new InstanceAdminException(InstanceAdminException.Code.INSTANCE_REQUEST_FAILED));
        return new MutationResult(result.getId(), InstanceAdminDtos.detail(result));
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new InstanceAdminException(InstanceAdminException.Code.IDEMPOTENCY_KEY_REQUIRED);
        }
    }

    private static void validate(InstanceAdminDtos.UpdateInstanceRequest request) {
        if (request == null || invalid(request.displayName(), 120) || invalid(request.purpose(), 500)) {
            throw new InstanceAdminException(InstanceAdminException.Code.INSTANCE_REQUEST_INVALID);
        }
    }

    private static boolean invalid(String value, int maximum) {
        return value == null || value.isBlank() || value.length() > maximum;
    }

    private static String sha256(String canonicalRequest) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    /** Length-prefixing makes the request hash unambiguous even when fields contain delimiters. */
    private static String canonical(String... fields) {
        StringBuilder value = new StringBuilder();
        for (String field : fields) {
            String required = Objects.requireNonNull(field, "canonical field");
            value.append(required.getBytes(StandardCharsets.UTF_8).length).append(':').append(required);
        }
        return value.toString();
    }

    /** Payload-free errors available on the generalized transport surface. */
    static final class InstanceAdminException extends RuntimeException {
        enum Code { IDEMPOTENCY_KEY_REQUIRED, INSTANCE_REQUEST_INVALID, INSTANCE_DISABLED,
            LIVE_RELEASE_NOT_FOUND, MANIFEST_UNSUPPORTED, INSTANCE_REQUEST_FAILED }
        private final Code code;
        InstanceAdminException(Code code) {
            super(code.name());
            this.code = code;
        }
        Code code() { return code; }
    }

    private record MutationResult(UUID id, InstanceAdminDtos.InstanceDetail detail) {}
}

/** Safe, closed error envelope for the instance surface; no exception text reaches the client. */
@RestControllerAdvice(assignableTypes = InstanceAdminController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
final class InstanceAdminExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InstanceAdminExceptionHandler.class);
    record ErrorResponse(String code) {}

    @ExceptionHandler(InstanceAdminController.InstanceAdminException.class)
    ResponseEntity<ErrorResponse> handleInstance(InstanceAdminController.InstanceAdminException failure) {
        HttpStatus status = switch (failure.code()) {
            case IDEMPOTENCY_KEY_REQUIRED, INSTANCE_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case INSTANCE_DISABLED, LIVE_RELEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MANIFEST_UNSUPPORTED -> HttpStatus.CONFLICT;
            case INSTANCE_REQUEST_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, failure.code().name());
    }

    @ExceptionHandler(InstanceRegistryService.InstanceException.class)
    ResponseEntity<ErrorResponse> handleRegistry(InstanceRegistryService.InstanceException failure) {
        HttpStatus status = failure.code() == InstanceRegistryService.InstanceException.Code.INSTANCE_DISABLED
                ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND;
        return response(status, failure.code().name());
    }

    @ExceptionHandler(InstanceReleaseResolver.ReleaseResolutionException.class)
    ResponseEntity<ErrorResponse> handleRelease(InstanceReleaseResolver.ReleaseResolutionException failure) {
        HttpStatus status = switch (failure.code()) {
            case LIVE_RELEASE_NOT_FOUND, RELEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RELEASE_SCOPE_MISMATCH -> HttpStatus.CONFLICT;
        };
        String code = failure.code() == InstanceReleaseResolver.ReleaseResolutionException.Code.RELEASE_NOT_FOUND
                ? InstanceRegistryService.InstanceException.Code.INSTANCE_NOT_FOUND.name()
                : failure.code().name();
        return response(status, code);
    }

    @ExceptionHandler(LabManifestWriter.ManifestException.class)
    ResponseEntity<ErrorResponse> handleManifest(LabManifestWriter.ManifestException failure) {
        return response(HttpStatus.CONFLICT,
                InstanceAdminController.InstanceAdminException.Code.MANIFEST_UNSUPPORTED.name());
    }

    @ExceptionHandler(InstanceSnapshotService.InstanceSnapshotException.class)
    ResponseEntity<ErrorResponse> handleSnapshot(InstanceSnapshotService.InstanceSnapshotException failure) {
        HttpStatus status = failure.code() == InstanceSnapshotService.InstanceSnapshotException.Code.LIVE_RELEASE_NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return response(status, failure.code().name());
    }

    @ExceptionHandler(com.pragmaticds.rag.lab.instance.VerifiedInstanceReleaseReader.VerifiedReleaseException.class)
    ResponseEntity<ErrorResponse> handleVerifiedRelease(
            com.pragmaticds.rag.lab.instance.VerifiedInstanceReleaseReader.VerifiedReleaseException failure) {
        return response(HttpStatus.CONFLICT,
                InstanceAdminController.InstanceAdminException.Code.MANIFEST_UNSUPPORTED.name());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException failure) {
        return response(HttpStatus.BAD_REQUEST, InstanceAdminController.InstanceAdminException.Code.INSTANCE_REQUEST_INVALID.name());
    }

    @ExceptionHandler(LabIdempotencyService.IdempotencyException.class)
    ResponseEntity<ErrorResponse> handleIdempotency(LabIdempotencyService.IdempotencyException failure) {
        return switch (failure.code()) {
            case IDEMPOTENCY_KEY_REUSED -> response(HttpStatus.CONFLICT, failure.code().name());
            case IDEMPOTENCY_COMMAND_INVALID -> response(HttpStatus.BAD_REQUEST,
                    InstanceAdminController.InstanceAdminException.Code.INSTANCE_REQUEST_INVALID.name());
            case IDEMPOTENCY_RECEIPT_CONFLICT -> response(HttpStatus.INTERNAL_SERVER_ERROR,
                    InstanceAdminController.InstanceAdminException.Code.INSTANCE_REQUEST_FAILED.name());
        };
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception failure) {
        // Class name and correlation id only. Not the message — that is the payload.
        log.error("Unexpected instance admin request failure ({}) [{}]",
                failure.getClass().getSimpleName(), LabAuditService.correlationId());
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
                InstanceAdminController.InstanceAdminException.Code.INSTANCE_REQUEST_FAILED.name());
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new ErrorResponse(code));
    }
}
