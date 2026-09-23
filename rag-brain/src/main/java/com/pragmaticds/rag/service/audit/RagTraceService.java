package com.pragmaticds.rag.service.audit;

import com.pragmaticds.rag.domain.RagTrace;
import com.pragmaticds.rag.domain.ResponseType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.CitationDto;
import com.pragmaticds.rag.repository.RagTraceRepository;
import com.pragmaticds.rag.service.ai.Intent;
import com.pragmaticds.rag.service.clarification.ClarificationDecision;
import com.pragmaticds.rag.service.retrieval.PlannedEvidence;
import com.pragmaticds.rag.service.retrieval.RetrievalPlan;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RagTraceService {

    private final RagTraceRepository repository;

    public RagTraceService(RagTraceRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RagTrace record(UUID conversationId,
                           UUID brainId,
                           String userQuestion,
                           String rewrittenQuestion,
                           Intent intent,
                           RetrievalPlan plan,
                           List<RetrievedChunk> chunks,
                           PlannedEvidence evidence,
                           List<CitationDto> citations,
                           String finalAnswer,
                           Double confidence,
                           boolean escalation,
                           ResponseType responseType,
                           ClarificationDecision clarificationDecision,
                           SourceVisibility visibility,
                           Map<String, Object> collectedFacts,
                           Map<String, Object> confidenceReason,
                           String validationOutcome) {
        RagTrace trace = new RagTrace();
        trace.setConversationId(conversationId);
        trace.setBrainId(brainId);
        trace.setUserQuestion(userQuestion);
        trace.setRewrittenQuestion(rewrittenQuestion);
        trace.setIntent(intent == null ? null : intent.name());
        trace.setResponseType(responseType == null ? ResponseType.ANSWER.name() : responseType.name());
        trace.setRetrievalPlan(Map.of(
                "indexes", plan == null ? List.of() : plan.indexes().stream().map(Enum::name).toList()));
        trace.setRetrievedContext(chunks == null ? List.of() : chunks.stream()
                .map(c -> Map.<String, Object>of(
                        "chunk_id", String.valueOf(c.chunkId()),
                        "parent_chunk_id", c.parentChunkId() == null ? "" : String.valueOf(c.parentChunkId()),
                        "document_id", String.valueOf(c.documentId()),
                        "document_name", String.valueOf(c.documentName()),
                        "source_name", String.valueOf(c.sourceName()),
                        "section", c.section() == null ? "" : c.section(),
                        "hierarchy_path", c.hierarchyPath() == null ? "" : c.hierarchyPath(),
                        "combined_score", c.combinedScore()))
                .toList());
        trace.setSideEvidence(Map.of(
                "page_guides", evidence == null ? List.of() : evidence.pageGuides().stream()
                        .map(g -> Map.of("id", String.valueOf(g.getId()), "title", g.getTitle()))
                        .toList(),
                "source_links", evidence == null ? List.of() : evidence.links().stream()
                        .map(l -> Map.of("id", String.valueOf(l.getId()), "name", l.getName(),
                                "authority", l.getAuthority().name()))
                        .toList()));
        trace.setCitations(citations == null ? List.of() : citations.stream()
                .map(c -> Map.<String, Object>of(
                        "source_name", c.sourceName() == null ? "" : c.sourceName(),
                        "document_name", c.documentName() == null ? "" : c.documentName(),
                        "section", c.section() == null ? "" : c.section(),
                        "page_number", c.pageNumber() == null ? "" : c.pageNumber(),
                        "effective_date", c.effectiveDate() == null ? "" : c.effectiveDate()))
                .toList());
        trace.setFinalAnswer(finalAnswer);
        trace.setConfidenceScore(confidence);
        trace.setClarificationDecision(clarificationDecision == null
                ? defaultClarificationDecision(responseType)
                : clarificationDecision.reason());
        trace.setMissingFacts(clarificationDecision == null
                ? List.of()
                : clarificationDecision.missingFacts());
        trace.setCollectedFacts(immutableFacts(collectedFacts));
        trace.setVisibilityFilter(visibility == null ? SourceVisibility.PUBLIC.name() : visibility.name());
        trace.setConfidenceReason(confidenceReason == null
                ? Map.of("confidence", confidence == null ? 0.0 : confidence)
                : confidenceReason);
        trace.setValidationOutcome(validationOutcome == null ? "valid" : validationOutcome);
        trace.setHumanEscalationRequired(escalation);
        return repository.save(trace);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RagTrace recordPublicDecision(UUID brainId,
                                         String sessionId,
                                         String userQuestion,
                                         Map<String, Object> suppliedFacts,
                                         ClarificationDecision decision,
                                         SourceVisibility visibility) {
        RagTrace trace = new RagTrace();
        Map<String, Object> collectedFacts = new LinkedHashMap<>();
        if (suppliedFacts != null) {
            suppliedFacts.forEach((key, value) -> {
                if (key != null && value != null) {
                    collectedFacts.put(key, value);
                }
            });
        }
        if (sessionId != null) {
            collectedFacts.put("session_id", sessionId);
        }
        trace.setBrainId(brainId);
        trace.setUserQuestion(userQuestion);
        trace.setResponseType(decision.responseType().name());
        trace.setClarificationDecision(decision.reason());
        trace.setMissingFacts(decision.missingFacts());
        trace.setCollectedFacts(immutableFacts(collectedFacts));
        trace.setRetrievedContext(List.of());
        trace.setVisibilityFilter(visibility.name());
        trace.setConfidenceReason(Map.of("reason", "pre-retrieval public decision"));
        trace.setValidationOutcome("not_applicable");
        trace.setHumanEscalationRequired(decision.responseType() == ResponseType.ESCALATE);
        return repository.save(trace);
    }

    /**
     * Resolves the document ids each of the given traces retrieved, read back out of
     * {@link RagTrace#getRetrievedContext()}'s {@code document_id} field (set in
     * {@link #record}). Used by the learning job to attribute a trace's feedback vote
     * to the documents that trace actually surfaced. Traces with no retrieved context
     * (e.g. pre-retrieval clarification decisions) are simply absent from the result.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<UUID>> documentIdsForTraces(Collection<UUID> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        for (RagTrace trace : repository.findAllById(traceIds)) {
            List<Map<String, Object>> context = trace.getRetrievedContext();
            if (context == null || context.isEmpty()) {
                continue;
            }
            List<UUID> docIds = context.stream()
                    .map(row -> row.get("document_id"))
                    .filter(v -> v != null)
                    .map(String::valueOf)
                    .distinct()
                    .flatMap(id -> {
                        try {
                            return java.util.stream.Stream.of(UUID.fromString(id));
                        } catch (IllegalArgumentException e) {
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .collect(Collectors.toList());
            if (!docIds.isEmpty()) {
                result.put(trace.getId(), docIds);
            }
        }
        return result;
    }

    private static Map<String, Object> immutableFacts(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        facts.forEach((key, value) -> {
            if (key != null && value != null) {
                filtered.put(key, value);
            }
        });
        return filtered.isEmpty() ? Map.of() : Map.copyOf(filtered);
    }

    private static Map<String, Object> defaultClarificationDecision(ResponseType responseType) {
        if (responseType == ResponseType.ESCALATE) {
            return Map.of("decision", "escalate");
        }
        return Map.of("decision", "answer");
    }
}
