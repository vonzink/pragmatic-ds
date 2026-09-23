package com.pragmaticds.rag.lab.corpus;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.ReplaceMembershipCommand;
import com.pragmaticds.rag.lab.corpus.repository.CorpusCollectionDocumentRepository;
import com.pragmaticds.rag.lab.corpus.repository.CorpusCollectionRepository;
import com.pragmaticds.rag.lab.service.LabAuditService;
import com.pragmaticds.rag.lab.service.LabIdempotencyService;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.repository.DocumentChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** Real-database proof that a copy-on-write clone never copies source documents or chunks. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CorpusCollectionServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired CorpusCollectionRepository collections;
    @Autowired CorpusCollectionDocumentRepository memberships;
    @Autowired BrainDocumentRepository documents;
    @Autowired DocumentChunkRepository chunks;

    private CorpusCollectionService service;

    @BeforeEach
    void setUp() {
        LabIdempotencyService passThrough = new LabIdempotencyService() {
            @Override
            public <T> T execute(IdempotentCommand<T> command) {
                return command.action().get();
            }

            /** A pass-through stores no receipt, so there is never a prior request to differ from. */
            @Override
            public void requireUnusedOrMatching(
                    UUID brainId, String operation, String key, String requestSha256) {
            }
        };
        service = new DefaultCorpusCollectionService(
                collections, memberships, documents, passThrough, mock(LabAuditService.class));
    }

    @Test
    void cloneDivergesByMembershipWhilePhysicalDocumentAndChunkCountsStayConstant() {
        BrainDocument first = documents.saveAndFlush(document("first.md", "a".repeat(64)));
        BrainDocument second = documents.saveAndFlush(document("second.md", "b".repeat(64)));

        var source = service.create(TestBrains.DEFAULT_ID, "source-test", "Source Test", "create");
        source = service.replaceMembership(new ReplaceMembershipCommand(
                TestBrains.DEFAULT_ID, source.id(), 1,
                List.of(first.getId(), second.getId()), "source-membership"));
        var sourceId = source.id();
        long documentCount = documents.count();
        long chunkCount = chunks.count();

        var clone = service.cloneByReference(TestBrains.DEFAULT_ID, sourceId,
                "clone-test", "Clone Test", "clone");
        clone = service.replaceMembership(new ReplaceMembershipCommand(
                TestBrains.DEFAULT_ID, clone.id(), 1, List.of(second.getId()), "clone-membership"));

        var sourceAfterCloneChange = service.list(TestBrains.DEFAULT_ID).stream()
                .filter(view -> view.id().equals(sourceId)).findFirst().orElseThrow();
        assertEquals(List.of(first.getId(), second.getId()).stream().sorted().toList(),
                sourceAfterCloneChange.documentIds());
        assertEquals(2, sourceAfterCloneChange.version());
        assertEquals(List.of(second.getId()), clone.documentIds());
        assertEquals(2, clone.version());
        assertEquals(documentCount, documents.count());
        assertEquals(chunkCount, chunks.count());
    }

    private static BrainDocument document(String fileName, String hash) {
        BrainDocument document = new BrainDocument();
        document.setBrainId(TestBrains.DEFAULT_ID);
        document.setTitle(fileName);
        document.setSourceName("Synthetic");
        document.setSourceType(SourceType.EDUCATIONAL);
        document.setVisibility(SourceVisibility.INTERNAL);
        document.setTrustLevel(SourceTrustLevel.APPROVED);
        document.setFileName(fileName);
        document.setDocumentVersion("2026.08");
        document.setContentSha256(hash);
        return document;
    }
}
