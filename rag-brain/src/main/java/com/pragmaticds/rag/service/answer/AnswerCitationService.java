package com.pragmaticds.rag.service.answer;

import com.pragmaticds.rag.dto.CitationDto;
import com.pragmaticds.rag.service.ai.ModelAnswer;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AnswerCitationService {

    /**
     * Keeps only the model citations that correspond to a retrieved source,
     * matched by document name or source name (case-insensitive). Anything else
     * was not in the prompt context and is therefore fabricated.
     */
    public List<CitationDto> filterToRetrieved(List<CitationDto> citations, List<RetrievedChunk> chunks) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        Set<String> documentNames = new HashSet<>();
        Set<String> sourceNames = new HashSet<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk.documentName() != null) {
                documentNames.add(normalizeName(chunk.documentName()));
            }
            if (chunk.sourceName() != null) {
                sourceNames.add(normalizeName(chunk.sourceName()));
            }
        }
        return citations.stream()
                .filter(citation -> matchesRetrieved(citation, documentNames, sourceNames))
                .toList();
    }

    /**
     * When the model returns a grounded answer but omits citations, attach the
     * retrieved approved sources so a correct answer is not discarded and
     * escalated. Model-supplied citations are kept as-is.
     */
    public ModelAnswer ensureCitations(ModelAnswer answer, List<RetrievedChunk> chunks) {
        if (answer.citations() != null && !answer.citations().isEmpty()) {
            return answer;
        }
        return withCitations(answer, citationsFromChunks(chunks));
    }

    /** Maps retrieved source chunks to the citation shape returned to the website. */
    public List<CitationDto> citationsFromChunks(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(chunk -> new CitationDto(
                        chunk.sourceName(),
                        chunk.documentName(),
                        chunk.section(),
                        chunk.pageNumber() == null ? null : String.valueOf(chunk.pageNumber()),
                        chunk.effectiveDate() == null ? null : chunk.effectiveDate().toString()))
                .toList();
    }

    public ModelAnswer withCitations(ModelAnswer answer, List<CitationDto> citations) {
        return new ModelAnswer(
                answer.answer(),
                citations,
                answer.confidence(),
                answer.humanEscalationRequired(),
                answer.disclaimer());
    }

    private static boolean matchesRetrieved(CitationDto citation,
                                            Set<String> documentNames, Set<String> sourceNames) {
        if (citation == null) {
            return false;
        }
        boolean docMatch = citation.documentName() != null
                && documentNames.contains(normalizeName(citation.documentName()));
        boolean sourceMatch = citation.sourceName() != null
                && sourceNames.contains(normalizeName(citation.sourceName()));
        return docMatch || sourceMatch;
    }

    private static String normalizeName(String value) {
        return value.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
