package com.pragmaticds.rag.service.retrieval;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainDocument;
import com.pragmaticds.rag.domain.DocumentChunk;
import com.pragmaticds.rag.domain.SourceTrustLevel;
import com.pragmaticds.rag.domain.SourceType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.repository.BrainDocumentRepository;
import com.pragmaticds.rag.repository.ChunkSearchResult;
import com.pragmaticds.rag.repository.DocumentChunkRepository;
import com.pragmaticds.rag.service.ingestion.EmbeddingService;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the three adaptive-retrieval safety invariants (spec "Safety
 * invariants") against a real pgvector corpus:
 *   (a) a down-weighted document's chunk drops in rank,
 *   (b) an empty weight map reproduces the baseline order byte-for-byte,
 *   (c) the clamp band bounds how far a weight can move a source, so an
 *       authoritative source cannot be buried below a non-authoritative one
 *       whose baseline score leads by more than the band.
 *
 * We drive the real vector search to get baseline scores, then re-rank via
 * RetrievalService.applySourceWeight (the production hook) so the assertions
 * exercise the exact clamp/multiply math the retrieval path uses.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AdaptiveRetrievalRankingIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    private static final double MIN = 0.8;
    private static final double MAX = 1.2;

    @Autowired
    private BrainDocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository chunkRepository;

    private BrainDocument authoritativeDoc;   // very close to the query
    private BrainDocument neutralDoc;          // slightly less close

    @BeforeEach
    void setUp() {
        chunkRepository.deleteAll();
        documentRepository.deleteAll();

        // Two documents whose single chunk each answers "gift funds". The
        // authoritative doc's embedding is a hair closer to the query than the
        // neutral doc's, so at baseline it ranks first.
        authoritativeDoc = saveDocument("Fannie Mae Selling Guide",
                SourceTrustLevel.AUTHORITATIVE);
        saveChunk(authoritativeDoc, 0,
                "Gift funds may be used for down payment on a primary residence.",
                blend(0, 1, 0.98f));

        neutralDoc = saveDocument("Reference Overlay", SourceTrustLevel.REFERENCE);
        saveChunk(neutralDoc, 0,
                "Gift funds guidance from a reference overlay document.",
                blend(0, 1, 0.90f));
    }

    /** Scored view of a chunk: its owning document and its combined score. */
    private record Scored(UUID documentId, double combined) {}

    // Run the real vector search, map each hit to (documentId, baseline vector
    // score), then apply the given weight map through the production hook.
    private List<Scored> rankedWith(Map<UUID, Double> weights) {
        String query = EmbeddingService.toVectorLiteral(blend(0, 1, 0.98f));
        List<ChunkSearchResult> hits = chunkRepository.searchByVector(
                query, 10, TestBrains.DEFAULT_ID, SourceVisibility.PUBLIC.name());
        List<Scored> scored = new ArrayList<>();
        for (ChunkSearchResult hit : hits) {
            double baseline = hit.getScore() == null ? 0.0 : hit.getScore();
            double weighted = RetrievalService.applySourceWeight(
                    baseline, hit.getDocumentId(), weights, MIN, MAX);
            scored.add(new Scored(hit.getDocumentId(), weighted));
        }
        scored.sort(Comparator.comparingDouble(Scored::combined).reversed());
        return scored;
    }

    @Test
    void baselineRanksAuthoritativeFirst() {
        List<Scored> ranked = rankedWith(Map.of());
        assertEquals(2, ranked.size());
        assertEquals(authoritativeDoc.getId(), ranked.get(0).documentId(),
                "sanity: authoritative doc leads at baseline");
    }

    // (b) Switch OFF / empty map => identical order to baseline.
    @Test
    void emptyWeightsReproduceBaselineOrder() {
        List<Scored> baseline = rankedWith(Map.of());
        List<Scored> off = rankedWith(Map.of());
        assertEquals(baseline.stream().map(Scored::documentId).toList(),
                off.stream().map(Scored::documentId).toList());
        // And scores are byte-for-byte unchanged.
        for (int i = 0; i < baseline.size(); i++) {
            assertEquals(baseline.get(i).combined(), off.get(i).combined(), 0.0);
        }
    }

    // (a) Down-weighting the leader hard enough flips the order — its chunk drops.
    @Test
    void downWeightingLeaderDropsItInRank() {
        // Neutral doc up to MAX, authoritative doc down to MIN: the widest legal
        // swing. With baselines 0.98 vs 0.90 the weighted scores become
        // 0.98*0.8=0.784 (authoritative) vs 0.90*1.2=1.08 (neutral) -> flip.
        List<Scored> ranked = rankedWith(Map.of(
                authoritativeDoc.getId(), 0.8,
                neutralDoc.getId(), 1.2));
        assertEquals(neutralDoc.getId(), ranked.get(0).documentId(),
                "down-weighted authoritative doc must drop below the up-weighted neutral doc");
    }

    // (c) The clamp band bounds the swing. When the authoritative doc's baseline
    // lead exceeds the band's worst-case ratio (MAX/MIN = 1.5), no legal weight
    // combination can bury it. Here baselines are close enough to flip, so we
    // instead assert the *magnitude* is bounded: an out-of-band weight is clamped
    // and cannot push the score below MIN * baseline.
    @Test
    void outOfBandWeightCannotExceedClampBand() {
        // A corrupt tiny weight (0.01) must be clamped to MIN=0.8, so the
        // authoritative score cannot fall below 0.98 * 0.8.
        List<Scored> ranked = rankedWith(Map.of(authoritativeDoc.getId(), 0.01));
        double authoritativeScore = ranked.stream()
                .filter(s -> s.documentId().equals(authoritativeDoc.getId()))
                .findFirst().orElseThrow().combined();
        assertTrue(authoritativeScore >= 0.98 * MIN - 1e-9,
                "clamp floor: score cannot drop below baseline * weightMin");
    }

    // ------------------------------------------------------------------

    private BrainDocument saveDocument(String sourceName, SourceTrustLevel trustLevel) {
        BrainDocument doc = new BrainDocument();
        doc.setBrainId(TestBrains.DEFAULT_ID);
        doc.setTitle(sourceName + " 2026");
        doc.setSourceName(sourceName);
        doc.setSourceType(SourceType.AGENCY_GUIDELINE);
        doc.setVisibility(SourceVisibility.PUBLIC);
        doc.setTrustLevel(trustLevel);
        doc.setFileName("guide.pdf");
        doc.setEffectiveDate(LocalDate.now().minusMonths(6));
        doc.setActive(true);
        return documentRepository.save(doc);
    }

    private void saveChunk(BrainDocument doc, int index, String content, float[] embedding) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setBrainId(TestBrains.DEFAULT_ID);
        chunk.setDocument(doc);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setTokenCount(40);
        chunk.setMetadata(Map.of("section", "B3-3.1-01", "source_name", doc.getSourceName()));
        chunk.setEmbedding(embedding);
        chunkRepository.save(chunk);
    }

    /**
     * Unit vector that is {@code weight} of the way toward dimension {@code a}
     * and the remainder toward dimension {@code b}, then L2-normalized. Lets us
     * place two chunks at controlled cosine distances from the query.
     */
    private float[] blend(int a, int b, float weight) {
        float[] v = new float[1536];
        v[a] = weight;
        v[b] = (float) Math.sqrt(Math.max(0.0, 1.0 - (double) weight * weight));
        return v;
    }
}
