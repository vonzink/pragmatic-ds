package com.pragmaticds.rag.lab;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.lab.corpus.CorpusCollectionService;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Injected failures at the corpus boundary, against the real database.
 *
 * <p>The provider, engine, lease, and budget injections live in
 * {@link InstanceControlE2EIT} where the execution plane is on; this file drives the two
 * corpus-side refusals the plan names — a collection that moved after a release pinned it, and a
 * snapshot request naming a collection that does not exist — and pins that both answer with a
 * value-free code, create nothing durable, and leave the collection's real state untouched.
 */
@SpringBootTest(properties = {
        "ragbrain.instances.enabled=true",
        "ragbrain.rag.admin.api-key=failure-it-key"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class InstanceControlFailureIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    com.pragmaticds.rag.repository.BrainRepository brains;
    @Autowired CorpusCollectionService collections;
    @Autowired CorpusSnapshotService snapshots;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aCollectionThatMovedSinceTheReleasePinnedItRefusesTheFreezeAndCreatesNothing() {
        UUID brainId = seedBrain("failure-stale");
        var collection = collections.create(brainId, "stale-guidelines", "Guidelines",
                "failure-stale-key");

        // The release pinned a version the collection has never reached — the exact shape of a
        // collection that moved after pinning, seen from the freeze's side.
        CorpusSnapshotService.SnapshotException refused = assertThrows(
                CorpusSnapshotService.SnapshotException.class,
                () -> snapshots.freeze(new CorpusSnapshotService.SnapshotRequest(brainId,
                        List.of(new CorpusSnapshotService.CollectionVersionRef(
                                collection.id(), collection.version() + 7)))));

        assertEquals("COLLECTION_VERSION_CONFLICT", refused.code().name());
        // The message is the code and nothing else: no version numbers, no collection names.
        assertEquals(refused.code().name(), refused.getMessage());
        assertEquals(0, count("SELECT count(*) FROM brain_corpus_snapshot WHERE brain_id = ?",
                brainId));
        // The refusal read the collection; it must not have moved it.
        assertEquals(1, count("SELECT count(*) FROM brain_corpus_collection "
                + "WHERE id = ? AND collection_version = ?", collection.id(), collection.version()));
    }

    @Test
    void aSnapshotNamingAMissingCollectionRefusesWithACodeAndCreatesNothing() {
        UUID brainId = seedBrain("failure-absent");

        CorpusSnapshotService.SnapshotException refused = assertThrows(
                CorpusSnapshotService.SnapshotException.class,
                () -> snapshots.freeze(new CorpusSnapshotService.SnapshotRequest(brainId,
                        List.of(new CorpusSnapshotService.CollectionVersionRef(
                                UUID.randomUUID(), 1L)))));

        assertTrue(refused.getMessage().equals(refused.code().name()),
                "a snapshot refusal must say its code and nothing else");
        assertFalse(refused.getMessage().contains("http"),
                "no URI belongs in a refusal");
        assertEquals(0, count("SELECT count(*) FROM brain_corpus_snapshot WHERE brain_id = ?",
                brainId));
    }

    // ================================================================ plumbing

    private UUID seedBrain(String prefix) {
        UUID brainId = UUID.randomUUID();
        Brain brain = new Brain(brainId, prefix + "-brain", "Failure " + prefix);
        brain.setActive(true);
        brains.save(brain);
        return brainId;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
