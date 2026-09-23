package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.domain.BrainPageGuide;
import com.pragmaticds.rag.domain.BrainSourceLink;
import com.pragmaticds.rag.dto.LinkDto;
import com.pragmaticds.rag.dto.RecommendedPageDto;
import com.pragmaticds.rag.service.retrieval.PlannedEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the SERVER-SIDE output contract (spec §6.3 / §7.7): the
 * {@code recommendedPage}, {@code links}, and {@code nextAction} that ride
 * alongside the corpus-grounded answer. All three are derived purely from the
 * Phase 6/7 {@link PlannedEvidence} (already authority-ordered by
 * {@link com.pragmaticds.rag.service.retrieval.AuthorityFilterService#order}) — the LLM
 * never emits them and the boot-locked prompt is untouched.
 *
 * <p><b>Light link/grounding validation (degrade-gracefully):</b> spec §7.8(b)
 * says an emitted link that does not resolve to an active registry row must
 * escalate. In this server-side model the links ARE active registry rows by
 * construction (they come from {@code SourceLinkService.activeLinks() → match()}),
 * so that escalation branch is structurally unreachable. This builder therefore
 * runs a belt-and-suspenders sanity check on the data it itself assembled: a link
 * with a blank URL is DROPPED (logged at WARN) and a guide with a blank route
 * yields a {@code null recommendedPage}. It NEVER throws and NEVER escalates — a
 * single corrupted row can never break or refuse a response.
 *
 * <p>Pure {@code @Service}: no injected collaborators, no LLM call, no I/O. The
 * returned {@link OutputContract} is an internal carrier (its three values are
 * passed into {@code AskResponse}); it is never serialized directly.
 */
@Service
public class OutputContractService {

    private static final Logger log = LoggerFactory.getLogger(OutputContractService.class);

    /** Maximum number of source links surfaced with an answer. */
    static final int MAX_LINKS = 5;

    /**
     * Internal carrier for the three output-contract values. Not a wire type —
     * {@code AskService} explodes it into the {@code AskResponse} components.
     */
    public record OutputContract(RecommendedPageDto recommendedPage,
                                 List<LinkDto> links,
                                 String nextAction) {
    }

    /**
     * Builds the output contract from already-authority-ordered side-evidence.
     * Null-safe and total: {@code build(PlannedEvidence.empty())} returns
     * {@code (null, [], null)}.
     */
    public OutputContract build(PlannedEvidence evidence) {
        RecommendedPageDto recommendedPage = topRecommendedPage(evidence.pageGuides());
        List<LinkDto> links = toLinkDtos(evidence.links());
        String nextAction = nextAction(recommendedPage, topGuide(evidence.pageGuides()), links);
        return new OutputContract(recommendedPage, links, nextAction);
    }

    /** The first page guide, or null when there are none. */
    private BrainPageGuide topGuide(List<BrainPageGuide> guides) {
        return guides.isEmpty() ? null : guides.get(0);
    }

    /**
     * The top guide as a {@link RecommendedPageDto}, or null when there is no
     * guide or the top guide has no usable (non-blank) route.
     */
    private RecommendedPageDto topRecommendedPage(List<BrainPageGuide> guides) {
        BrainPageGuide guide = topGuide(guides);
        if (guide == null) {
            return null;
        }
        String route = guide.getRoute();
        if (route == null || route.isBlank()) {
            return null;
        }
        return new RecommendedPageDto(route, guide.getTitle());
    }

    /**
     * Maps the (already authority-ordered) links to DTOs, dropping any with a
     * blank URL, then caps at {@link #MAX_LINKS}.
     */
    private List<LinkDto> toLinkDtos(List<BrainSourceLink> links) {
        List<LinkDto> out = new ArrayList<>();
        for (BrainSourceLink link : links) {
            String url = link.getUrl();
            if (url == null || url.isBlank()) {
                log.warn("Dropping output-contract link with blank URL: name={}", link.getName());
                continue;
            }
            out.add(new LinkDto(link.getName(), url, link.getAuthority().name()));
            if (out.size() == MAX_LINKS) {
                break;
            }
        }
        return out;
    }

    /**
     * Deterministic next-action string (NO LLM call). In order:
     * <ol>
     *   <li>recommendedPage present + guide has a non-blank first allowed-guidance
     *       entry → that entry trimmed (via {@code .strip()});</li>
     *   <li>recommendedPage present (no allowed-guidance) → "See the &lt;title&gt;
     *       page for detailed guidance.";</li>
     *   <li>recommendedPage null but links present → "Review the linked source(s)
     *       for authoritative detail.";</li>
     *   <li>otherwise → null.</li>
     * </ol>
     */
    private String nextAction(RecommendedPageDto recommendedPage, BrainPageGuide guide,
                              List<LinkDto> links) {
        if (recommendedPage != null && guide != null) {
            String guidance = firstNonBlank(guide.getAllowedGuidance());
            if (guidance != null) {
                return guidance;
            }
            return "See the " + guide.getTitle() + " page for detailed guidance.";
        }
        if (!links.isEmpty()) {
            return "Review the linked source(s) for authoritative detail.";
        }
        return null;
    }

    /** First non-blank entry of a (possibly null/empty) list, stripped of leading/trailing whitespace, or null. */
    private static String firstNonBlank(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }
}
