package com.pragmaticds.rag.service.publicapi;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.domain.ResponseType;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.AskRequest;
import com.pragmaticds.rag.dto.AskResponse;
import com.pragmaticds.rag.dto.PublicAskRequest;
import com.pragmaticds.rag.dto.PublicAskResponse;
import com.pragmaticds.rag.dto.PublicRecommendedPageDto;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.AskService;
import com.pragmaticds.rag.service.audit.RagTraceService;
import com.pragmaticds.rag.service.clarification.ClarificationDecision;
import com.pragmaticds.rag.service.clarification.ClarificationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PublicAskService {

    private final BrainRepository brains;
    private final PublicAccessService access;
    private final ClarificationService clarification;
    private final AskService askService;
    private final RagTraceService traceService;

    public PublicAskService(BrainRepository brains, PublicAccessService access,
                            ClarificationService clarification, AskService askService,
                            RagTraceService traceService) {
        this.brains = brains;
        this.access = access;
        this.clarification = clarification;
        this.askService = askService;
        this.traceService = traceService;
    }

    public PublicAskResponse ask(String slug, String token, String origin, PublicAskRequest req) {
        Brain brain = brains.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + slug));
        BrainProfile profile = access.validate(brain.getId(), token, origin);
        String surface = SourceVisibility.PUBLIC.name();
        Map<String, Object> facts = req.facts() == null ? Map.of() : req.facts();
        ClarificationDecision decision = clarification.decide(
                brain.getId(), req.message(), surface, facts);
        if (decision.responseType() == ResponseType.CLARIFY) {
            var trace = traceService.recordPublicDecision(brain.getId(), req.sessionId(), req.message(), facts,
                    decision, SourceVisibility.PUBLIC);
            return new PublicAskResponse("CLARIFY", decision.question(), null, decision.question(),
                    decision.missingFacts(), List.of(), List.of(), 0.0, null, null,
                    disclaimerOf(profile), false, trace == null ? null : trace.getId());
        }
        if (decision.responseType() == ResponseType.NAVIGATE) {
            traceService.recordPublicDecision(brain.getId(), req.sessionId(), req.message(), facts,
                    decision, SourceVisibility.PUBLIC);
            AskResponse answer = askService.ask(toAskRequest(req, surface), brain.getId(), SourceVisibility.PUBLIC);
            return mapAnswer(answer.humanEscalationRequired() ? "ESCALATE" : "NAVIGATE", answer);
        }
        AskResponse answer = askService.ask(toAskRequest(req, surface), brain.getId(), SourceVisibility.PUBLIC);
        return mapAnswer(answer.humanEscalationRequired() ? "ESCALATE" : "ANSWER", answer);
    }

    private static AskRequest toAskRequest(PublicAskRequest req, String surface) {
        return new AskRequest(req.conversationId(), req.sessionId(), req.message(), null, null,
                req.pageRoute(), surface, stringFacts(req.facts()));
    }

    /**
     * Flattens the public request's {@code Map<String, Object>} facts to string
     * values so they can be threaded into the answer prompt. AskService applies
     * the trimming/length/entry caps.
     */
    private static Map<String, String> stringFacts(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        facts.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k, String.valueOf(v));
            }
        });
        return out;
    }

    private static PublicAskResponse mapAnswer(String responseType, AskResponse answer) {
        List<PublicRecommendedPageDto> pages = answer.recommendedPage() == null
                ? List.of()
                : List.of(new PublicRecommendedPageDto(
                        answer.recommendedPage().label(), answer.recommendedPage().route(), "Matched the current question."));
        return new PublicAskResponse(responseType, answer.answer(), answer.answer(), null, List.of(),
                answer.citations(), pages, answer.confidence(), answer.nextAction(), answer.conversationId(),
                answer.disclaimer(), answer.humanEscalationRequired(), answer.traceId());
    }

    private static String disclaimerOf(BrainProfile profile) {
        if (profile == null || profile.getDisclaimer() == null || profile.getDisclaimer().isBlank()) {
            return null;
        }
        return profile.getDisclaimer().strip();
    }
}
