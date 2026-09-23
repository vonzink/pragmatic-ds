package com.pragmaticds.rag.service.answer;

import com.pragmaticds.rag.dto.CitationDto;
import com.pragmaticds.rag.service.ai.ModelAnswer;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerCitationServiceTest {

    private final AnswerCitationService citations = new AnswerCitationService();

    @Test
    void citationsFromChunksMapsRetrievedMetadataToPublicCitationFields() {
        List<CitationDto> result = citations.citationsFromChunks(List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf",
                        "B3-3.1-01", 12, LocalDate.of(2026, 1, 1))));

        assertEquals(1, result.size());
        CitationDto citation = result.get(0);
        assertEquals("Fannie Mae Selling Guide", citation.sourceName());
        assertEquals("selling-guide.pdf", citation.documentName());
        assertEquals("B3-3.1-01", citation.section());
        assertEquals("12", citation.pageNumber());
        assertEquals("2026-01-01", citation.effectiveDate());
    }

    @Test
    void citationsFromChunksLeavesMissingOptionalMetadataNull() {
        CitationDto citation = citations.citationsFromChunks(List.of(
                chunk("FHA Handbook", "4000.1.pdf", null, null, null))).get(0);

        assertEquals("FHA Handbook", citation.sourceName());
        assertNull(citation.section());
        assertNull(citation.pageNumber());
        assertNull(citation.effectiveDate());
    }

    @Test
    void filterToRetrievedKeepsCitationsMatchingDocumentOrSourceName() {
        List<CitationDto> model = List.of(
                new CitationDto("fannie mae selling guide", "renamed.pdf", null, null, null),
                new CitationDto("Some label", "selling-guide.pdf", "B7", "1", null));

        List<CitationDto> kept = citations.filterToRetrieved(model, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, null)));

        assertEquals(2, kept.size());
    }

    @Test
    void filterToRetrievedDropsFabricatedCitations() {
        List<CitationDto> kept = citations.filterToRetrieved(List.of(
                new CitationDto("Totally Made Up", "ghost.pdf", "Z", "9", null)), List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, null)));

        assertTrue(kept.isEmpty());
    }

    @Test
    void ensureCitationsBackfillsRetrievedSourcesOnlyWhenModelOmittedThem() {
        ModelAnswer answer = new ModelAnswer("PMI is mortgage insurance.", List.of(), 0.85, false, "d");

        ModelAnswer result = citations.ensureCitations(answer, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, null)));

        assertFalse(result.humanEscalationRequired());
        assertEquals("PMI is mortgage insurance.", result.answer());
        assertEquals(1, result.citations().size());
        assertEquals("Fannie Mae Selling Guide", result.citations().get(0).sourceName());
    }

    @Test
    void ensureCitationsKeepsModelProvidedCitations() {
        List<CitationDto> modelCitations = List.of(
                new CitationDto("Model Source", "model.pdf", "sec", "5", "2026-01-01"));
        ModelAnswer answer = new ModelAnswer("PMI is mortgage insurance.", modelCitations, 0.85, false, "d");

        ModelAnswer result = citations.ensureCitations(answer, List.of(
                chunk("Retrieved Source", "retrieved.pdf", "other", 99, null)));

        assertEquals(modelCitations, result.citations());
    }

    private RetrievedChunk chunk(String sourceName, String documentName,
                                 String section, Integer pageNumber, LocalDate effectiveDate) {
        return new RetrievedChunk(
                UUID.randomUUID(), UUID.randomUUID(),
                "Some grounding content.",
                sourceName, "AGENCY_GUIDELINE",
                documentName, "Doc Title",
                section, pageNumber, effectiveDate,
                0.9, 0.7, 0.83);
    }
}
