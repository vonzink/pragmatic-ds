package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.CollectionView;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.ReplaceMembershipCommand;
import com.pragmaticds.rag.lab.corpus.domain.CorpusCollection;
import com.pragmaticds.rag.lab.corpus.domain.CorpusCollectionDocument;
import com.pragmaticds.rag.lab.corpus.repository.CorpusCollectionDocumentRepository;
import com.pragmaticds.rag.lab.corpus.repository.CorpusCollectionRepository;
import com.pragmaticds.rag.lab.domain.LabAuditEvent;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Versioned, idempotent collection membership and copy-on-write operations. */
public interface CorpusCollectionService {
    CollectionView create(UUID brainId, String slug, String displayName, String idempotencyKey);
    CollectionView replaceMembership(ReplaceMembershipCommand command);
    CollectionView cloneByReference(UUID brainId, UUID sourceId, String slug,
                                    String displayName, String idempotencyKey);
    CollectionView disable(UUID brainId, UUID collectionId, long expectedVersion,
                           String idempotencyKey);
    List<CollectionView> list(UUID brainId);

    /** Stable, payload-free collection failure taxonomy. */
    final class CollectionException extends RuntimeException {
        public enum Code {
            COLLECTION_COMMAND_INVALID,
            COLLECTION_NOT_FOUND,
            COLLECTION_DISABLED,
            COLLECTION_SLUG_EXISTS,
            COLLECTION_VERSION_CONFLICT,
            COLLECTION_DUPLICATE_DOCUMENT,
            COLLECTION_DOCUMENT_INVALID,
            COLLECTION_DOCUMENT_INACTIVE,
            COLLECTION_REPLAY_INVALID
        }

        private final Code code;
        private final Long currentVersion;

        public CollectionException(Code code) {
            this(code, null);
        }

        public CollectionException(Code code, Long currentVersion) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
            this.currentVersion = currentVersion;
        }

        public Code code() { return code; }
        public Long currentVersion() { return currentVersion; }
    }
}

@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class DefaultCorpusCollectionService implements CorpusCollectionService {
    private static final String RESULT_KIND = "CORPUS_COLLECTION";
    private static final String SLUG_PATTERN = "^[a-z][a-z0-9-]{0,47}$";

    private final CorpusCollectionRepository collections;
    private final CorpusCollectionDocumentRepository memberships;
    private final BrainDocumentRepository documents;
    private final LabIdempotencyService idempotency;
    private final LabAuditService audit;

    DefaultCorpusCollectionService(CorpusCollectionRepository collections,
                                   CorpusCollectionDocumentRepository memberships,
                                   BrainDocumentRepository documents,
                                   LabIdempotencyService idempotency,
                                   LabAuditService audit) {
        this.collections = Objects.requireNonNull(collections, "collections");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public CollectionView create(UUID brainId, String slug, String displayName,
                                 String idempotencyKey) {
        requireIdentity(brainId, slug, displayName);
        return idempotent(brainId, "corpus.collection.create", idempotencyKey,
                hash("create", brainId, slug, displayName),
                () -> createOnce(brainId, slug, displayName));
    }

    @Override
    public CollectionView replaceMembership(ReplaceMembershipCommand command) {
        Objects.requireNonNull(command, "command");
        requireUuid(command.brainId());
        requireUuid(command.collectionId());
        if (command.expectedVersion() < 1) {
            throw invalid();
        }
        List<UUID> canonicalDocuments = canonicalDocumentIds(command.documentIds());
        String[] fields = new String[4 + canonicalDocuments.size()];
        fields[0] = "replace";
        fields[1] = command.brainId().toString();
        fields[2] = command.collectionId().toString();
        fields[3] = Long.toString(command.expectedVersion());
        for (int index = 0; index < canonicalDocuments.size(); index++) {
            fields[4 + index] = canonicalDocuments.get(index).toString();
        }
        return idempotent(command.brainId(), "corpus.collection.replace-membership",
                command.idempotencyKey(), hash(fields),
                () -> replaceOnce(command, canonicalDocuments));
    }

    @Override
    public CollectionView cloneByReference(UUID brainId, UUID sourceId, String slug,
                                           String displayName, String idempotencyKey) {
        requireIdentity(brainId, slug, displayName);
        requireUuid(sourceId);
        return idempotent(brainId, "corpus.collection.clone", idempotencyKey,
                hash("clone", brainId, sourceId, slug, displayName),
                () -> cloneOnce(brainId, sourceId, slug, displayName));
    }

    @Override
    public CollectionView disable(UUID brainId, UUID collectionId, long expectedVersion,
                                  String idempotencyKey) {
        requireUuid(brainId);
        requireUuid(collectionId);
        if (expectedVersion < 1) {
            throw invalid();
        }
        return idempotent(brainId, "corpus.collection.disable", idempotencyKey,
                hash("disable", brainId, collectionId, expectedVersion),
                () -> disableOnce(brainId, collectionId, expectedVersion));
    }

    @Transactional(readOnly = true)
    @Override
    public List<CollectionView> list(UUID brainId) {
        requireUuid(brainId);
        return collections.findAllByBrainIdOrderByDisplayNameAsc(brainId).stream()
                .map(this::view).toList();
    }

    private CollectionView createOnce(UUID brainId, String slug, String displayName) {
        if (collections.existsByBrainIdAndSlug(brainId, slug)) {
            throw new CollectionException(CollectionException.Code.COLLECTION_SLUG_EXISTS);
        }
        CorpusCollection collection = collections.saveAndFlush(
                new CorpusCollection(brainId, slug, displayName, null));
        CollectionView created = view(collection, List.of());
        audit(collection, LabAuditService.COLLECTION_CREATE, 0, 1, 0);
        return created;
    }

    private CollectionView replaceOnce(ReplaceMembershipCommand command,
                                       List<UUID> canonicalDocuments) {
        CorpusCollection collection = lock(command.brainId(), command.collectionId());
        requireActive(collection);
        requireVersion(collection, command.expectedVersion());
        validateDocuments(command.brainId(), canonicalDocuments);

        List<CorpusCollectionDocument> current = members(collection.getId());
        List<UUID> currentIds = current.stream()
                .map(CorpusCollectionDocument::getDocumentId).sorted().toList();
        long oldVersion = collection.getCollectionVersion();
        if (currentIds.equals(canonicalDocuments)) {
            audit(collection, LabAuditService.COLLECTION_MEMBERSHIP_REPLACE,
                    oldVersion, oldVersion, currentIds.size());
            return view(collection, currentIds);
        }

        memberships.deleteAll(current);
        memberships.flush();
        List<CorpusCollectionDocument> replacement = canonicalDocuments.stream()
                .map(documentId -> new CorpusCollectionDocument(
                        collection.getId(), command.brainId(), documentId))
                .toList();
        memberships.saveAll(replacement);
        collection.advanceVersion();
        collections.saveAndFlush(collection);
        audit(collection, LabAuditService.COLLECTION_MEMBERSHIP_REPLACE,
                oldVersion, collection.getCollectionVersion(), replacement.size());
        return view(collection, canonicalDocuments);
    }

    private CollectionView cloneOnce(UUID brainId, UUID sourceId, String slug,
                                     String displayName) {
        CorpusCollection source = lock(brainId, sourceId);
        requireActive(source);
        if (collections.existsByBrainIdAndSlug(brainId, slug)) {
            throw new CollectionException(CollectionException.Code.COLLECTION_SLUG_EXISTS);
        }
        List<CorpusCollectionDocument> sourceMembership = members(sourceId);
        CorpusCollection clone = collections.saveAndFlush(
                new CorpusCollection(brainId, slug, displayName, sourceId));
        List<CorpusCollectionDocument> copied = sourceMembership.stream()
                .map(member -> new CorpusCollectionDocument(
                        clone.getId(), brainId, member.getDocumentId()))
                .toList();
        memberships.saveAll(copied);
        List<UUID> documentIds = copied.stream()
                .map(CorpusCollectionDocument::getDocumentId).sorted().toList();
        audit(clone, LabAuditService.COLLECTION_CLONE, 0, 1, documentIds.size());
        return view(clone, documentIds);
    }

    private CollectionView disableOnce(UUID brainId, UUID collectionId, long expectedVersion) {
        CorpusCollection collection = lock(brainId, collectionId);
        requireActive(collection);
        requireVersion(collection, expectedVersion);
        long oldVersion = collection.getCollectionVersion();
        collection.disableAndAdvanceVersion();
        collections.saveAndFlush(collection);
        List<UUID> documentIds = members(collectionId).stream()
                .map(CorpusCollectionDocument::getDocumentId).sorted().toList();
        audit(collection, LabAuditService.COLLECTION_DISABLE,
                oldVersion, collection.getCollectionVersion(), documentIds.size());
        return view(collection, documentIds);
    }

    private CollectionView idempotent(UUID brainId, String operation, String key,
                                      String requestHash, Supplier<CollectionView> action) {
        return idempotency.execute(new LabIdempotencyService.IdempotentCommand<>(
                brainId, operation, key, requestHash, action,
                result -> new LabIdempotencyService.IdempotencyResult(
                        RESULT_KIND, result.id(), result.version()),
                receipt -> replay(brainId, receipt)));
    }

    private CollectionView replay(UUID brainId, LabIdempotencyService.IdempotencyResult receipt) {
        if (!RESULT_KIND.equals(receipt.kind()) || receipt.id() == null
                || receipt.version() == null || receipt.version() < 1) {
            throw new CollectionException(CollectionException.Code.COLLECTION_REPLAY_INVALID);
        }
        CorpusCollection collection = collections.findByIdAndBrainId(receipt.id(), brainId)
                .orElseThrow(() -> new CollectionException(
                        CollectionException.Code.COLLECTION_REPLAY_INVALID));
        if (collection.getCollectionVersion() < receipt.version()) {
            throw new CollectionException(CollectionException.Code.COLLECTION_REPLAY_INVALID);
        }
        return view(collection);
    }

    private CorpusCollection lock(UUID brainId, UUID collectionId) {
        return collections.lockByIdAndBrainId(collectionId, brainId)
                .orElseThrow(() -> new CollectionException(
                        CollectionException.Code.COLLECTION_NOT_FOUND));
    }

    private void validateDocuments(UUID brainId, List<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }
        List<BrainDocument> found = documents.findAllById(documentIds);
        Map<UUID, BrainDocument> byId = new HashMap<>();
        for (BrainDocument document : found) {
            byId.put(document.getId(), document);
        }
        if (byId.size() != documentIds.size()) {
            throw new CollectionException(CollectionException.Code.COLLECTION_DOCUMENT_INVALID);
        }
        for (UUID id : documentIds) {
            BrainDocument document = byId.get(id);
            if (document == null || !brainId.equals(document.getBrainId())) {
                throw new CollectionException(CollectionException.Code.COLLECTION_DOCUMENT_INVALID);
            }
            if (!document.isActive()) {
                throw new CollectionException(CollectionException.Code.COLLECTION_DOCUMENT_INACTIVE);
            }
        }
    }

    private List<CorpusCollectionDocument> members(UUID collectionId) {
        return memberships.findAllByIdCollectionIdOrderByIdDocumentIdAsc(collectionId);
    }

    private CollectionView view(CorpusCollection collection) {
        List<UUID> documentIds = members(collection.getId()).stream()
                .map(CorpusCollectionDocument::getDocumentId).sorted().toList();
        return view(collection, documentIds);
    }

    private static CollectionView view(CorpusCollection collection, List<UUID> documentIds) {
        return new CollectionView(collection.getId(), collection.getBrainId(), collection.getSlug(),
                collection.getDisplayName(), collection.getState().name(),
                collection.getCollectionVersion(), collection.getClonedFromId(), documentIds);
    }

    private void audit(CorpusCollection collection, String action, long oldVersion,
                       long newVersion, int documentCount) {
        audit.record(collection.getBrainId(), action, LabAuditEvent.Status.SUCCEEDED,
                LabAuditEvent.SubjectType.COLLECTION, collection.getId(), null,
                Map.of("oldVersion", oldVersion, "newVersion", newVersion,
                        "documentCount", documentCount));
    }

    private static void requireIdentity(UUID brainId, String slug, String displayName) {
        requireUuid(brainId);
        if (slug == null || !slug.matches(SLUG_PATTERN)
                || displayName == null || displayName.isBlank() || displayName.length() > 120) {
            throw invalid();
        }
    }

    private static void requireUuid(UUID value) {
        if (value == null) {
            throw invalid();
        }
    }

    private static List<UUID> canonicalDocumentIds(List<UUID> documentIds) {
        Objects.requireNonNull(documentIds, "documentIds");
        if (documentIds.stream().anyMatch(Objects::isNull)) {
            throw invalid();
        }
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(documentIds);
        if (unique.size() != documentIds.size()) {
            throw new CollectionException(CollectionException.Code.COLLECTION_DUPLICATE_DOCUMENT);
        }
        return unique.stream().sorted().toList();
    }

    private static void requireActive(CorpusCollection collection) {
        if (collection.getState() == CorpusCollection.State.DISABLED) {
            throw new CollectionException(CollectionException.Code.COLLECTION_DISABLED);
        }
    }

    private static void requireVersion(CorpusCollection collection, long expectedVersion) {
        if (collection.getCollectionVersion() != expectedVersion) {
            throw new CollectionException(CollectionException.Code.COLLECTION_VERSION_CONFLICT,
                    collection.getCollectionVersion());
        }
    }

    private static CollectionException invalid() {
        return new CollectionException(CollectionException.Code.COLLECTION_COMMAND_INVALID);
    }

    private static String hash(Object... fields) {
        List<String> values = new ArrayList<>(fields.length);
        for (Object field : fields) {
            values.add(Objects.requireNonNull(field, "canonical field").toString());
        }
        return hash(values.toArray(String[]::new));
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
}
