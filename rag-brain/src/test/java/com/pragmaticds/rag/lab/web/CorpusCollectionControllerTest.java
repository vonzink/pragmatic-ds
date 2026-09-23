package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.config.AdminApiKeyFilter;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.config.RequestCorrelationFilter;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.lab.corpus.CorpusCollectionService;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.CollectionView;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.FrozenCollection;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.FrozenCorpusSnapshot;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService.FrozenDocument;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for feature-gated collection and immutable snapshot administration. */
class CorpusCollectionControllerTest {
    private static final String ADMIN_KEY = "test-admin-key";
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COLLECTION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CLONE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID DOCUMENT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID SNAPSHOT = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String HASH = "a".repeat(64);

    private CorpusCollectionService collections;
    private CorpusSnapshotService snapshots;
    private LabIdempotencyService idempotency;
    private BrainDocumentRepository documents;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        collections = mock(CorpusCollectionService.class);
        snapshots = mock(CorpusSnapshotService.class);
        idempotency = mock(LabIdempotencyService.class);
        documents = mock(BrainDocumentRepository.class);
        doAnswer(invocation -> invocation.<LabIdempotencyService.IdempotentCommand<Object>>getArgument(0)
                .action().get()).when(idempotency).execute(any());
        when(documents.findAllById(any())).thenReturn(List.of(document()));
        mvc = MockMvcBuilders.standaloneSetup(
                        new CorpusCollectionController(collections, snapshots, idempotency, documents))
                .addFilters(new RequestCorrelationFilter("X-Auth-Request-Email"),
                        new AdminApiKeyFilter(properties()))
                .setControllerAdvice(new CorpusCollectionExceptionHandler())
                .build();
    }

    @Test
    void routesAreAdminGatedExplicitlyBrainScopedAndIndependentlyFeatureGated() throws Exception {
        for (String route : List.of(
                "/api/ai/admin/instances/corpus-collections",
                "/api/ai/admin/instances/corpus-snapshots/" + SNAPSHOT)) {
            mvc.perform(get(route).param("brain", BRAIN.toString()))
                    .andExpect(status().isUnauthorized());
        }

        when(collections.list(BRAIN)).thenReturn(List.of(collection(COLLECTION, 2, List.of(DOCUMENT))));
        mvc.perform(get("/api/ai/admin/instances/corpus-collections")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].brainId").value(BRAIN.toString()))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].documentCount").value(1))
                .andExpect(jsonPath("$[0].documents").doesNotExist());

        ConditionalOnProperty gate = CorpusCollectionController.class
                .getAnnotation(ConditionalOnProperty.class);
        assertEquals("ragbrain.instances", gate.prefix());
        assertEquals("enabled", gate.name()[0]);
        assertEquals("true", gate.havingValue());
    }

    @Test
    void everyMutationRequiresIdempotencyKeyBeforeCallingServices() throws Exception {
        mvc.perform(post("/api/ai/admin/instances/corpus-collections")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"income\",\"displayName\":\"Income\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(put("/api/ai/admin/instances/corpus-collections/" + COLLECTION + "/membership")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"documentIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/api/ai/admin/instances/corpus-collections/" + COLLECTION + "/clone")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"copy\",\"displayName\":\"Copy\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/api/ai/admin/instances/corpus-snapshots")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collections\":[{\"collectionId\":\"" + COLLECTION
                                + "\",\"expectedVersion\":2}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        verify(collections, never()).create(any(), any(), any(), any());
        verify(snapshots, never()).freeze(any());
        verify(idempotency, never()).execute(any());
    }

    @Test
    void createMembershipAndCloneReturnOnlySafeDocumentMetadata() throws Exception {
        CollectionView created = collection(COLLECTION, 1, List.of());
        CollectionView replaced = collection(COLLECTION, 2, List.of(DOCUMENT));
        CollectionView cloned = new CollectionView(CLONE, BRAIN, "income-copy", "Income Copy",
                "ACTIVE", 1, COLLECTION, List.of(DOCUMENT));
        when(collections.create(BRAIN, "income", "Income", "create-1")).thenReturn(created);
        when(collections.replaceMembership(any())).thenReturn(replaced);
        when(collections.cloneByReference(BRAIN, COLLECTION, "income-copy", "Income Copy", "clone-1"))
                .thenReturn(cloned);

        mvc.perform(post("/api/ai/admin/instances/corpus-collections")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Income\",\"slug\":\"income\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        String membership = mvc.perform(put(
                        "/api/ai/admin/instances/corpus-collections/" + COLLECTION + "/membership")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "replace-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentIds\":[\"" + DOCUMENT
                                + "\"],\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].documentId").value(DOCUMENT.toString()))
                .andExpect(jsonPath("$.documents[0].title").value("Paystub"))
                .andExpect(jsonPath("$.documents[0].documentVersion").value("v1"))
                .andExpect(jsonPath("$.documents[0].contentSha256").value(HASH))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/ai/admin/instances/corpus-collections/" + COLLECTION + "/clone")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "clone-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"income-copy\",\"displayName\":\"Income Copy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clonedFromId").value(COLLECTION.toString()));

        assertFalse(membership.contains("embedding"));
        assertFalse(membership.contains("chunk"));
    }

    @Test
    void snapshotCreateAndReadExposeFrozenIdentityAndUseCanonicalIdempotency() throws Exception {
        FrozenCorpusSnapshot frozen = snapshot();
        when(snapshots.freeze(any())).thenReturn(frozen);
        when(snapshots.require(BRAIN, SNAPSHOT)).thenReturn(frozen);

        String first = mvc.perform(post("/api/ai/admin/instances/corpus-snapshots")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "snapshot-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collections\":[{\"expectedVersion\":2,\"collectionId\":\""
                                + COLLECTION + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(SNAPSHOT.toString()))
                .andExpect(jsonPath("$.documents[0].contentSha256").value(HASH))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/ai/admin/instances/corpus-snapshots")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "snapshot-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collections\":[{\"collectionId\":\"" + COLLECTION
                                + "\",\"expectedVersion\":2}]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ai/admin/instances/corpus-snapshots/" + SNAPSHOT)
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifestSha256").value("b".repeat(64)));

        org.mockito.ArgumentCaptor<LabIdempotencyService.IdempotentCommand<?>> commands =
                org.mockito.ArgumentCaptor.forClass(LabIdempotencyService.IdempotentCommand.class);
        verify(idempotency, org.mockito.Mockito.times(2)).execute(commands.capture());
        assertTrue(commands.getAllValues().stream().allMatch(command ->
                command.operation().equals("corpus.snapshot.freeze")
                        && command.brainId().equals(BRAIN)
                        && command.requestSha256().matches("[0-9a-f]{64}")));
        assertEquals(commands.getAllValues().get(0).requestSha256(),
                commands.getAllValues().get(1).requestSha256(),
                "JSON member order must not change the semantic request hash");
        assertFalse(first.contains("\"content\":"));
    }

    @Test
    void safeErrorsMapVersionDisabledAndNotVersionedWithoutExceptionText() throws Exception {
        when(collections.replaceMembership(any())).thenThrow(
                new CorpusCollectionService.CollectionException(
                        CorpusCollectionService.CollectionException.Code.COLLECTION_VERSION_CONFLICT, 7L));
        mvc.perform(put("/api/ai/admin/instances/corpus-collections/" + COLLECTION + "/membership")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "stale-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"documentIds\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLECTION_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(7));

        when(collections.cloneByReference(any(), any(), any(), any(), any())).thenThrow(
                new CorpusCollectionService.CollectionException(
                        CorpusCollectionService.CollectionException.Code.COLLECTION_DISABLED));
        mvc.perform(post("/api/ai/admin/instances/corpus-collections/" + COLLECTION + "/clone")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "disabled-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"copy\",\"displayName\":\"Copy\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLECTION_DISABLED"));

        when(snapshots.freeze(any())).thenThrow(new CorpusSnapshotService.SnapshotException(
                CorpusSnapshotService.SnapshotException.Code.SNAPSHOT_DOCUMENT_NOT_VERSIONED));
        String body = mvc.perform(post("/api/ai/admin/instances/corpus-snapshots")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "snapshot-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collections\":[{\"collectionId\":\"" + COLLECTION
                                + "\",\"expectedVersion\":2}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_DOCUMENT_NOT_VERSIONED"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("contentSha256"));
    }

    @Test
    void malformedOrMissingScopeIsSafeBadRequestAndNoConnectorRouteExists() throws Exception {
        mvc.perform(post("/api/ai/admin/instances/corpus-collections")
                        .param("brain", BRAIN.toString()).header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "bad")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CORPUS_REQUEST_INVALID"));
        mvc.perform(get("/api/ai/admin/instances/corpus-collections")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CORPUS_REQUEST_INVALID"));
        mvc.perform(get("/api/ai/connectors/corpus-collections")
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isNotFound());
    }

    private static CollectionView collection(UUID id, long version, List<UUID> documentIds) {
        return new CollectionView(id, BRAIN, "income", "Income", "ACTIVE",
                version, null, documentIds);
    }

    private static FrozenCorpusSnapshot snapshot() {
        return new FrozenCorpusSnapshot(SNAPSHOT, BRAIN, "b".repeat(64),
                List.of(new FrozenCollection(COLLECTION, 2)),
                List.of(new FrozenDocument(COLLECTION, DOCUMENT, "v1", HASH,
                        SourceVisibility.INTERNAL, SourceTrustLevel.APPROVED,
                        LocalDate.of(2026, 1, 1), null)));
    }

    private static BrainDocument document() {
        BrainDocument document = new BrainDocument();
        ReflectionTestUtils.setField(document, "id", DOCUMENT);
        document.setBrainId(BRAIN);
        document.setTitle("Paystub");
        document.setDocumentVersion("v1");
        document.setContentSha256(HASH);
        return document;
    }

    private static RagProperties properties() {
        return new RagProperties(new RagProperties.Routing("anthropic", "openai"),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/documents"),
                new RagProperties.Admin(ADMIN_KEY), new RagProperties.Analyze(null),
                new RagProperties.RateLimit(10, 60, 120));
    }
}
