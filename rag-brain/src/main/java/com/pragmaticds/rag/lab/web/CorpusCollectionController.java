package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.lab.corpus.CorpusCollectionService;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.ReplaceMembershipCommand;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.CollectionVersionRef;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.FrozenCorpusSnapshot;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.SnapshotRequest;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Brain-scoped administration for versioned collections and immutable snapshots. */
@RestController
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
@RequestMapping("/api/ai/admin/instances")
public final class CorpusCollectionController {
    private static final String SNAPSHOT_RESULT_KIND = "CORPUS_SNAPSHOT";

    private final CorpusCollectionService collections;
    private final CorpusSnapshotService snapshots;
    private final LabIdempotencyService idempotency;
    private final BrainDocumentRepository documents;

    public CorpusCollectionController(CorpusCollectionService collections,
                                      CorpusSnapshotService snapshots,
                                      LabIdempotencyService idempotency,
                                      BrainDocumentRepository documents) {
        this.collections = Objects.requireNonNull(collections, "collections");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    @GetMapping("/corpus-collections")
    public List<CorpusCollectionDtos.CollectionSummary> list(@RequestParam UUID brain) {
        return collections.list(brain).stream().map(CorpusCollectionDtos::summary).toList();
    }

    @PostMapping("/corpus-collections")
    public CorpusCollectionDtos.CollectionDetail create(
            @RequestParam UUID brain,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody CorpusCollectionDtos.CreateCollectionRequest request) {
        requireKey(key);
        if (request == null) {
            throw invalid();
        }
        return detail(collections.create(
                brain, request.slug(), request.displayName(), key), brain);
    }

    @PutMapping("/corpus-collections/{id}/membership")
    public CorpusCollectionDtos.CollectionDetail replaceMembership(
            @RequestParam UUID brain,
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody CorpusCollectionDtos.ReplaceMembershipRequest request) {
        requireKey(key);
        if (request == null || request.documentIds() == null) {
            throw invalid();
        }
        return detail(collections.replaceMembership(new ReplaceMembershipCommand(
                brain, id, request.expectedVersion(), request.documentIds(), key)), brain);
    }

    @PostMapping("/corpus-collections/{id}/clone")
    public CorpusCollectionDtos.CollectionDetail cloneByReference(
            @RequestParam UUID brain,
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody CorpusCollectionDtos.CloneCollectionRequest request) {
        requireKey(key);
        if (request == null) {
            throw invalid();
        }
        return detail(collections.cloneByReference(
                brain, id, request.slug(), request.displayName(), key), brain);
    }

    @PostMapping("/corpus-snapshots")
    public CorpusCollectionDtos.SnapshotDetail freeze(
            @RequestParam UUID brain,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody CorpusCollectionDtos.CreateSnapshotRequest request) {
        requireKey(key);
        List<CollectionVersionRef> refs = snapshotRefs(request);
        String[] fields = new String[2 + refs.size() * 2];
        fields[0] = "freeze";
        fields[1] = brain.toString();
        for (int index = 0; index < refs.size(); index++) {
            fields[2 + index * 2] = refs.get(index).collectionId().toString();
            fields[3 + index * 2] = Long.toString(refs.get(index).expectedVersion());
        }
        FrozenCorpusSnapshot frozen = idempotency.execute(
                new LabIdempotencyService.IdempotentCommand<>(
                        brain, "corpus.snapshot.freeze", key, hash(fields),
                        () -> snapshots.freeze(new SnapshotRequest(brain, refs)),
                        result -> new LabIdempotencyService.IdempotencyResult(
                                SNAPSHOT_RESULT_KIND, result.id(), 1L),
                        receipt -> replaySnapshot(brain, receipt)));
        return snapshot(frozen, brain);
    }

    @GetMapping("/corpus-snapshots/{id}")
    public CorpusCollectionDtos.SnapshotDetail readSnapshot(
            @RequestParam UUID brain, @PathVariable UUID id) {
        return snapshot(snapshots.require(brain, id), brain);
    }

    private CorpusCollectionDtos.CollectionDetail detail(
            com.pragmaticds.rag.lab.corpus.CorpusCommands.CollectionView view, UUID brain) {
        if (!brain.equals(view.brainId())) {
            throw failed();
        }
        return CorpusCollectionDtos.detail(view, documentMap(brain, view.documentIds()));
    }

    private CorpusCollectionDtos.SnapshotDetail snapshot(
            FrozenCorpusSnapshot snapshot, UUID brain) {
        if (!brain.equals(snapshot.brainId())) {
            throw failed();
        }
        List<UUID> documentIds = snapshot.documents().stream()
                .map(CorpusSnapshotService.FrozenDocument::documentId)
                .distinct().sorted().toList();
        return CorpusCollectionDtos.snapshot(snapshot, documentMap(brain, documentIds));
    }

    private Map<UUID, BrainDocument> documentMap(UUID brain, List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BrainDocument> byId = new HashMap<>();
        for (BrainDocument document : documents.findAllById(ids)) {
            if (!brain.equals(document.getBrainId()) || byId.put(document.getId(), document) != null) {
                throw failed();
            }
        }
        if (byId.size() != ids.size()) {
            throw failed();
        }
        return Map.copyOf(byId);
    }

    private FrozenCorpusSnapshot replaySnapshot(
            UUID brain, LabIdempotencyService.IdempotencyResult receipt) {
        if (!SNAPSHOT_RESULT_KIND.equals(receipt.kind()) || receipt.id() == null
                || receipt.version() == null || receipt.version() != 1L) {
            throw failed();
        }
        return snapshots.require(brain, receipt.id());
    }

    private static List<CollectionVersionRef> snapshotRefs(
            CorpusCollectionDtos.CreateSnapshotRequest request) {
        if (request == null || request.collections() == null || request.collections().isEmpty()) {
            throw invalid();
        }
        List<CollectionVersionRef> refs = new ArrayList<>(request.collections().size());
        for (CorpusCollectionDtos.SnapshotCollectionRequest collection : request.collections()) {
            if (collection == null || collection.collectionId() == null
                    || collection.expectedVersion() < 1) {
                throw invalid();
            }
            refs.add(new CollectionVersionRef(
                    collection.collectionId(), collection.expectedVersion()));
        }
        return List.copyOf(refs);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new CorpusAdminException(CorpusAdminException.Code.IDEMPOTENCY_KEY_REQUIRED);
        }
    }

    private static CorpusAdminException invalid() {
        return new CorpusAdminException(CorpusAdminException.Code.CORPUS_REQUEST_INVALID);
    }

    private static CorpusAdminException failed() {
        return new CorpusAdminException(CorpusAdminException.Code.CORPUS_REQUEST_FAILED);
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

    static final class CorpusAdminException extends RuntimeException {
        enum Code { IDEMPOTENCY_KEY_REQUIRED, CORPUS_REQUEST_INVALID, CORPUS_REQUEST_FAILED }
        private final Code code;

        CorpusAdminException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        Code code() { return code; }
    }
}

/** Safe, payload-free error taxonomy for only the corpus administration routes. */
@RestControllerAdvice(assignableTypes = CorpusCollectionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
final class CorpusCollectionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CorpusCollectionExceptionHandler.class);
    record ErrorResponse(String code, Long currentVersion) {}

    @ExceptionHandler(CorpusCollectionController.CorpusAdminException.class)
    ResponseEntity<ErrorResponse> handleAdmin(
            CorpusCollectionController.CorpusAdminException failure) {
        HttpStatus status = switch (failure.code()) {
            case IDEMPOTENCY_KEY_REQUIRED, CORPUS_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case CORPUS_REQUEST_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, failure.code().name(), null);
    }

    @ExceptionHandler(CorpusCollectionService.CollectionException.class)
    ResponseEntity<ErrorResponse> handleCollection(
            CorpusCollectionService.CollectionException failure) {
        HttpStatus status = switch (failure.code()) {
            case COLLECTION_COMMAND_INVALID, COLLECTION_DUPLICATE_DOCUMENT -> HttpStatus.BAD_REQUEST;
            case COLLECTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case COLLECTION_DISABLED, COLLECTION_SLUG_EXISTS, COLLECTION_VERSION_CONFLICT ->
                    HttpStatus.CONFLICT;
            case COLLECTION_DOCUMENT_INVALID, COLLECTION_DOCUMENT_INACTIVE ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case COLLECTION_REPLAY_INVALID -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return response(status, failure.code().name(), failure.currentVersion());
    }

    @ExceptionHandler(CorpusSnapshotService.SnapshotException.class)
    ResponseEntity<ErrorResponse> handleSnapshot(CorpusSnapshotService.SnapshotException failure) {
        HttpStatus status = switch (failure.code()) {
            case SNAPSHOT_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
            case CORPUS_SNAPSHOT_NOT_FOUND, COLLECTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case COLLECTION_DISABLED, COLLECTION_VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case SNAPSHOT_DOCUMENT_INVALID, SNAPSHOT_DOCUMENT_INACTIVE,
                 SNAPSHOT_DOCUMENT_NOT_VERSIONED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return response(status, failure.code().name(), failure.currentVersion());
    }

    @ExceptionHandler(LabIdempotencyService.IdempotencyException.class)
    ResponseEntity<ErrorResponse> handleIdempotency(
            LabIdempotencyService.IdempotencyException failure) {
        return switch (failure.code()) {
            case IDEMPOTENCY_KEY_REUSED -> response(HttpStatus.CONFLICT, failure.code().name(), null);
            case IDEMPOTENCY_COMMAND_INVALID -> response(
                    HttpStatus.BAD_REQUEST, "CORPUS_REQUEST_INVALID", null);
            case IDEMPOTENCY_RECEIPT_CONFLICT -> response(
                    HttpStatus.INTERNAL_SERVER_ERROR, "CORPUS_REQUEST_FAILED", null);
        };
    }

    @ExceptionHandler({
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> handleUnreadable(Exception failure) {
        return response(HttpStatus.BAD_REQUEST, "CORPUS_REQUEST_INVALID", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception failure) {
        // Class name and correlation id only. Not the message — that is the payload.
        log.error("Unexpected corpus request failure ({}) [{}]",
                failure.getClass().getSimpleName(), LabAuditService.correlationId());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "CORPUS_REQUEST_FAILED", null);
    }

    private static ResponseEntity<ErrorResponse> response(
            HttpStatus status, String code, Long currentVersion) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, currentVersion));
    }
}
