package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.domain.BrainPageGuide;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.domain.BrainSourceLink;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.service.profile.BrainProfileService;
import com.pragmaticds.rag.service.retrieval.PlannedEvidence;
import com.pragmaticds.rag.service.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Builds the final prompt sent to the LLM. The template and disclaimer come
 * from the brain's pack (resolved per request via DomainPackRegistry), keeping
 * all company-specific text out of code. Hard rules and guidance are injected
 * live from {@link RulesService} (pack defaults until edited) and remain
 * compliance-critical: the model must answer only from the supplied source
 * context, never from general knowledge.
 */
@Service
public class PromptBuilderService {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilderService.class);

    private final DomainPackRegistry registry;
    private final RulesService rules;
    private final BrainProfileService profiles;
    private final int maxContextTokens;
    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    @Autowired
    public PromptBuilderService(DomainPackRegistry registry, RulesService rules, BrainProfileService profiles,
                                @Value("${ragbrain.rag.ai.max-context-tokens:12000}") int maxContextTokens) {
        this.registry = registry;
        this.rules = rules;
        this.profiles = profiles;
        this.maxContextTokens = maxContextTokens > 0 ? maxContextTokens : Integer.MAX_VALUE;
    }

    /** Test/convenience constructor with an effectively unbounded context budget. */
    public PromptBuilderService(DomainPackRegistry registry, RulesService rules, BrainProfileService profiles) {
        this(registry, rules, profiles, Integer.MAX_VALUE);
    }

    /** The brain's public disclaimer, appended to every website response. */
    public String disclaimer(UUID brainId) {
        String profileDisclaimer = profiles.getOrCreate(brainId).getDisclaimer();
        if (profileDisclaimer != null && !profileDisclaimer.isBlank()) {
            return profileDisclaimer.strip();
        }
        return registry.bundle(brainId).pack().disclaimer();
    }

    public String build(String question, List<RetrievedChunk> chunks, UUID brainId) {
        return build(question, chunks, brainId, PlannedEvidence.empty());
    }

    public String build(String question, List<RetrievedChunk> chunks, UUID brainId, PlannedEvidence evidence) {
        var pack = registry.bundle(brainId).pack();
        return pack.promptTemplate().formatted(
                rules.effectiveHard(brainId) + "\n\n" + profileGuidance(brainId),
                rules.effectiveGuidance(brainId) + "\n\n" + sideEvidenceGuidance(evidence),
                formatContext(chunks),
                question,
                disclaimer(brainId));
    }

    String profileGuidance(UUID brainId) {
        BrainProfile profile = profiles.getOrCreate(brainId);
        return """
                Brain profile:
                purpose: %s
                audience: %s
                personality: %s
                tone: %s
                expertise_level: %s
                answer_length: %s
                confidence_target: %s
                clarification_policy: %s
                escalation_policy: %s
                citation_policy: %s
                cta_policy: %s
                profile_disclaimer: %s
                """.formatted(
                safe(profile.getPurpose()),
                safe(profile.getAudience()),
                safe(profile.getPersonality()),
                safe(profile.getTone()),
                safe(profile.getExpertiseLevel()),
                safe(profile.getAnswerLength()),
                profile.getConfidenceTarget(),
                safe(profile.getClarificationPolicy()),
                safe(profile.getEscalationPolicy()),
                safe(profile.getCitationPolicy()),
                safe(profile.getCtaPolicy()),
                disclaimer(brainId)).strip();
    }

    String sideEvidenceGuidance(PlannedEvidence evidence) {
        if (evidence == null || (evidence.pageGuides().isEmpty() && evidence.links().isEmpty())) {
            return """
                    Side evidence:
                    (none)
                    Do not invent navigation steps or links. Do not treat side links as corpus factual proof.
                    """.strip();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Side evidence:\n");
        sb.append("Use this only for navigation, next steps, and allowed links. ");
        sb.append("Do not treat side links as corpus factual proof.\n");
        if (!evidence.pageGuides().isEmpty()) {
            sb.append("Page guides:\n");
            int n = 1;
            for (BrainPageGuide guide : evidence.pageGuides()) {
                sb.append("- [Guide ").append(n++).append("] ");
                sb.append("title: ").append(safe(guide.getTitle())).append("; ");
                sb.append("route: ").append(safe(guide.getRoute())).append("; ");
                sb.append("purpose: ").append(safe(guide.getPurpose()));
                String guidance = String.join(" | ", guide.getAllowedGuidance());
                if (!guidance.isBlank()) {
                    sb.append("; allowed_guidance: ").append(safe(guidance));
                }
                sb.append('\n');
            }
        }
        if (!evidence.links().isEmpty()) {
            sb.append("Allowed source links:\n");
            int n = 1;
            for (BrainSourceLink link : evidence.links()) {
                sb.append("- [Link ").append(n++).append("] ");
                sb.append("name: ").append(safe(link.getName())).append("; ");
                sb.append("url: ").append(safe(link.getUrl())).append("; ");
                sb.append("authority: ").append(link.getAuthority() == null ? "" : link.getAuthority().name());
                String allowedUse = String.join(" | ", link.getAllowedUse());
                if (!allowedUse.isBlank()) {
                    sb.append("; allowed_use: ").append(safe(allowedUse));
                }
                sb.append('\n');
            }
        }
        return sb.toString().strip();
    }

    /**
     * Each chunk is labeled [Source N] with its citation metadata so the model
     * can attribute statements to specific sources.
     */
    private String formatContext(List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return "(no source context found)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 1;
        int included = 0;
        int usedTokens = 0;
        for (RetrievedChunk chunk : chunks) {
            String block = renderChunk(n, chunk);
            int blockTokens = encoding.countTokens(block);
            // Always keep at least the top chunk; otherwise stop before blowing the budget.
            if (included > 0 && usedTokens + blockTokens > maxContextTokens) {
                log.info("Context token budget {} reached; including {}/{} retrieved chunks in the prompt",
                        maxContextTokens, included, chunks.size());
                break;
            }
            sb.append(block);
            usedTokens += blockTokens;
            included++;
            n++;
        }
        return sb.toString().strip();
    }

    private String renderChunk(int index, RetrievedChunk chunk) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Source ").append(index).append("]\n");
        sb.append("source_name: ").append(chunk.sourceName()).append('\n');
        sb.append("document_name: ").append(chunk.documentName()).append('\n');
        if (chunk.section() != null) {
            sb.append("section: ").append(chunk.section()).append('\n');
        }
        if (chunk.pageNumber() != null) {
            sb.append("page_number: ").append(chunk.pageNumber()).append('\n');
        }
        if (chunk.effectiveDate() != null) {
            sb.append("effective_date: ").append(chunk.effectiveDate()).append('\n');
        }
        sb.append("content:\n").append(chunk.content()).append("\n\n");
        return sb.toString();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("[\\r\\n]+", " ").strip();
    }
}
