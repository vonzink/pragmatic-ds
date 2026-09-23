package com.pragmaticds.rag.service.retrieval;

import com.pragmaticds.rag.domain.SourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthorityFilterServiceTest {

    private final AuthorityFilterService authority = new AuthorityFilterService();

    /** A chunk identified by documentName, carrying a sourceType and a score. */
    private static RetrievedChunk chunk(String name, String sourceType, double score) {
        return new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(), "content",
                null, null, null, "Source", sourceType, name, name,
                null, null, null, score, score, score, name);
    }

    private static List<String> names(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::documentName).toList();
    }

    @Test
    void tiedChunksAreOrderedAuthoritativeFirst() {
        // Same score means the scorer could not tell these apart; when it cannot,
        // the company rule should ground the answer ahead of borrower-facing
        // background material.
        List<RetrievedChunk> ordered = authority.orderCorpusByAuthority(List.of(
                chunk("educational", SourceType.EDUCATIONAL.name(), 0.7),
                chunk("policy", SourceType.INTERNAL_POLICY.name(), 0.7)), 0.0);

        assertEquals(List.of("policy", "educational"), names(ordered));
    }

    @Test
    void aMoreRelevantChunkIsNeverDemotedForAuthority() {
        // The safety property: authority only breaks ties. A background doc that
        // genuinely answers the question must stay ahead of a company policy doc
        // that merely mentions the topic.
        List<RetrievedChunk> ordered = authority.orderCorpusByAuthority(List.of(
                chunk("educational", SourceType.EDUCATIONAL.name(), 0.90),
                chunk("policy", SourceType.INTERNAL_POLICY.name(), 0.40)), 0.0);

        assertEquals(List.of("educational", "policy"), names(ordered));
    }

    @Test
    void ordersAcrossAllFourTiers() {
        List<RetrievedChunk> ordered = authority.orderCorpusByAuthority(List.of(
                chunk("educational", SourceType.EDUCATIONAL.name(), 0.5),
                chunk("overlay", SourceType.INVESTOR_OVERLAY.name(), 0.5),
                chunk("agency", SourceType.AGENCY_GUIDELINE.name(), 0.5),
                chunk("policy", SourceType.INTERNAL_POLICY.name(), 0.5)), 0.0);

        assertEquals(List.of("policy", "agency", "overlay", "educational"), names(ordered));
    }

    @Test
    void preservesIncomingOrderWithinTheSameTier() {
        // Same tier + same score: the scorer's order is all the information there
        // is, so it must survive.
        List<RetrievedChunk> ordered = authority.orderCorpusByAuthority(List.of(
                chunk("first", SourceType.AGENCY_GUIDELINE.name(), 0.6),
                chunk("second", SourceType.AGENCY_GUIDELINE.name(), 0.6),
                chunk("third", SourceType.AGENCY_GUIDELINE.name(), 0.6)), 0.0);

        assertEquals(List.of("first", "second", "third"), names(ordered));
    }

    @Test
    void bandGroupsAgainstTheRunAnchorSoTiesCannotDriftTransitively() {
        // 0.90 / 0.85 / 0.80 with band 0.05: the first run is anchored at 0.90 and
        // admits 0.85 but NOT 0.80 (0.10 away), even though 0.80 is within 0.05 of
        // 0.85. Without an anchor this chain would collapse into one run and let
        // authority reorder chunks the scorer ranked clearly apart.
        List<RetrievedChunk> ordered = authority.orderCorpusByAuthority(List.of(
                chunk("edu-90", SourceType.EDUCATIONAL.name(), 0.90),
                chunk("policy-85", SourceType.INTERNAL_POLICY.name(), 0.85),
                chunk("policy-80", SourceType.INTERNAL_POLICY.name(), 0.80)), 0.05);

        assertEquals(List.of("policy-85", "edu-90", "policy-80"), names(ordered));
    }

    @Test
    void unknownAndNullSourceTypesSortLastWithoutThrowing() {
        List<RetrievedChunk> ordered = authority.orderCorpusByAuthority(List.of(
                chunk("bogus", "NOT_A_SOURCE_TYPE", 0.5),
                chunk("missing", null, 0.5),
                chunk("policy", SourceType.INTERNAL_POLICY.name(), 0.5)), 0.0);

        assertEquals("policy", names(ordered).getFirst());
        assertEquals(3, ordered.size());
    }

    @Test
    void isTotalOnTrivialInput() {
        assertEquals(List.of(), authority.orderCorpusByAuthority(null, 0.0));
        assertEquals(List.of(), authority.orderCorpusByAuthority(List.of(), 0.0));

        RetrievedChunk only = chunk("only", SourceType.EDUCATIONAL.name(), 0.3);
        assertEquals(List.of("only"), names(authority.orderCorpusByAuthority(List.of(only), 0.0)));
    }
}
