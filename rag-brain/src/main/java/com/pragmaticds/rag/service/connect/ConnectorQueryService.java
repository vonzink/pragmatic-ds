package com.pragmaticds.rag.service.connect;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.ResponseType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.AskRequest;
import com.pragmaticds.rag.dto.AskResponse;
import com.pragmaticds.rag.dto.FederationDtos;
import com.pragmaticds.rag.dto.PublicAskResponse;
import com.pragmaticds.rag.dto.PublicRecommendedPageDto;
import com.pragmaticds.rag.service.AskService;
import com.pragmaticds.rag.service.clarification.ClarificationDecision;
import com.pragmaticds.rag.service.clarification.ClarificationService;
import com.pragmaticds.rag.service.retrieval.RetrievalResult;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConnectorQueryService {

    private final ClarificationService clarification;
    private final AskService askService;
    private final RetrievalService retrievalService;

    public ConnectorQueryService(ClarificationService clarification,
                                 AskService askService,
                                 RetrievalService retrievalService) {
        this.clarification = clarification;
        this.askService = askService;
        this.retrievalService = retrievalService;
    }

    public PublicAskResponse ask(Brain brain, FederationDtos.FederationAskRequest req) {
        Map<String, Object> facts = req.facts() == null ? Map.of() : req.facts();
        String surface = SourceVisibility.PUBLIC.name();
        ClarificationDecision decision = clarification.decide(
                brain.getId(), req.message(), surface, facts);
        if (decision.responseType() == ResponseType.CLARIFY) {
            return new PublicAskResponse("CLARIFY", decision.question(), null, decision.question(),
                    decision.missingFacts(), List.of(), List.of(), 0.0, null, req.conversationId(),
                    null, false, null);
        }
        AskResponse answer = askService.ask(toAskRequest(req, surface), brain.getId(), SourceVisibility.PUBLIC);
        String type = answer.humanEscalationRequired()
                ? "ESCALATE"
                : decision.responseType() == ResponseType.NAVIGATE ? "NAVIGATE" : "ANSWER";
        return mapAnswer(type, answer);
    }

    public FederationDtos.FederationRetrieveResponse retrieve(
            Brain brain, FederationDtos.FederationRetrieveRequest req) {
        RetrievalResult result = retrievalService.retrieve(
                req.question(), brain.getId(), SourceVisibility.PUBLIC);
        return new FederationDtos.FederationRetrieveResponse(
                result.chunks().stream().map(ConnectorQueryService::toDto).toList(),
                result.confidence(),
                result.sufficientEvidence());
    }

    private static AskRequest toAskRequest(FederationDtos.FederationAskRequest req, String surface) {
        return new AskRequest(req.conversationId(), req.sessionId(), req.message(), null, null,
                req.pageRoute(), surface, stringFacts(req.facts()));
    }

    private static Map<String, String> stringFacts(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        facts.forEach((key, value) -> {
            if (key != null && value != null) {
                out.put(key, String.valueOf(value));
            }
        });
        return out;
    }

    private static PublicAskResponse mapAnswer(String responseType, AskResponse answer) {
        List<PublicRecommendedPageDto> pages = answer.recommendedPage() == null
                ? List.of()
                : List.of(new PublicRecommendedPageDto(
                        answer.recommendedPage().label(),
                        answer.recommendedPage().route(),
                        "Matched the current question."));
        return new PublicAskResponse(responseType, answer.answer(), answer.answer(), null, List.of(),
                answer.citations(), pages, answer.confidence(), answer.nextAction(),
                answer.conversationId(), answer.disclaimer(), answer.humanEscalationRequired(), answer.traceId());
    }

    private static FederationDtos.RetrievedChunkDto toDto(RetrievedChunk chunk) {
        return new FederationDtos.RetrievedChunkDto(
                chunk.chunkId(),
                chunk.documentId(),
                chunk.content(),
                chunk.parentChunkId(),
                chunk.hierarchyPath(),
                chunk.sourceName(),
                chunk.sourceType(),
                chunk.documentName(),
                chunk.documentTitle(),
                chunk.section(),
                chunk.pageNumber(),
                chunk.effectiveDate(),
                chunk.combinedScore());
    }
}
