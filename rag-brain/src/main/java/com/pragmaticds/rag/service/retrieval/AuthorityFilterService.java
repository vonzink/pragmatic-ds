package com.pragmaticds.rag.service.retrieval;

import com.pragmaticds.rag.domain.BrainSourceLink;
import com.pragmaticds.rag.domain.LinkAuthority;
import com.pragmaticds.rag.domain.SourceType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tiers and orders the collected side-evidence by trust authority (spec §6.4,
 * §7.6). Pure {@code @Service} — no injected collaborators.
 *
 * <p>This only re-orders the side-evidence
 * {@link RetrievalPlannerService#collect} produces so downstream prompt assembly,
 * response shaping, and trace output can present it trust-first. It still does
 * NOT touch the corpus retrieval or reranker path that grounds the answer body.
 */
@Service
public class AuthorityFilterService {

    /** Tolerance absorbing binary-float error at the tie-band boundary. */
    private static final double SCORE_EPSILON = 1e-9;

    /**
     * Maps a link's external authority to its tier. The link row only ever
     * carries the three external authorities (tiers 1–2 come from elsewhere,
     * per {@link LinkAuthority} / spec §6.4). Exhaustive switch.
     */
    public AuthorityTier tierOf(LinkAuthority authority) {
        return switch (authority) {
            case PRIMARY -> AuthorityTier.PRIMARY_EXTERNAL;
            case SECONDARY -> AuthorityTier.SECONDARY_EXTERNAL;
            case BACKGROUND -> AuthorityTier.BACKGROUND;
        };
    }

    /**
     * Maps a corpus source's {@link SourceType} to its tier. Exhaustive switch:
     * {@code INTERNAL_POLICY → COMPANY_RULE} (company rule);
     * {@code AGENCY_GUIDELINE → PRIMARY_EXTERNAL} (agency guides + HUD = primary);
     * {@code INVESTOR_OVERLAY → SECONDARY_EXTERNAL} (approved supporting overlay);
     * {@code EDUCATIONAL → BACKGROUND} (borrower-facing context).
     *
     * <p>Defined for completeness; the current corpus path still uses its existing
     * retrieval/rerank flow rather than applying this authority tiering directly.
     */
    public AuthorityTier tierOf(SourceType sourceType) {
        return switch (sourceType) {
            case INTERNAL_POLICY -> AuthorityTier.COMPANY_RULE;
            case AGENCY_GUIDELINE -> AuthorityTier.PRIMARY_EXTERNAL;
            case INVESTOR_OVERLAY -> AuthorityTier.SECONDARY_EXTERNAL;
            case EDUCATIONAL -> AuthorityTier.BACKGROUND;
        };
    }

    /**
     * Re-orders corpus chunks so that, <b>among chunks the scorer rated equally</b>,
     * the more authoritative source comes first (company rule → agency guide →
     * investor overlay → educational background).
     *
     * <p>This never promotes a less-relevant chunk over a more-relevant one. Chunks
     * are grouped into runs whose score is within {@code band} of the run's first
     * (highest-scoring) chunk; only within a run does authority decide. Comparing
     * against the run anchor — rather than the previous chunk — bounds each run to
     * {@code band} wide, so a long chain of near-equal scores cannot transitively
     * drift into reordering genuinely different scores.
     *
     * <p>{@code band = 0.0} means exact ties only, which is the conservative
     * default and still fires constantly in practice: the reranker emits integer
     * scores normalized by 10, so its output is quantized to 0.1 steps and real
     * ties are common.
     *
     * <p>Total and non-throwing — this sits in the answer-grounding path. A null or
     * unrecognized {@code sourceType} is treated as lowest authority rather than
     * failing retrieval. Input order is otherwise preserved (stable sort).
     */
    public List<RetrievedChunk> orderCorpusByAuthority(List<RetrievedChunk> chunks, double band) {
        if (chunks == null || chunks.size() < 2) {
            return chunks == null ? List.of() : chunks;
        }
        // Inclusive band comparison needs a tolerance: a configured 0.05 against
        // scores 0.90 and 0.85 yields 0.050000000000000044 in IEEE-754, which would
        // otherwise exclude a chunk the operator meant to include.
        double effectiveBand = (band > 0 ? band : 0.0) + SCORE_EPSILON;
        List<RetrievedChunk> out = new ArrayList<>(chunks.size());
        int i = 0;
        while (i < chunks.size()) {
            double anchor = chunks.get(i).combinedScore();
            int end = i + 1;
            while (end < chunks.size() && anchor - chunks.get(end).combinedScore() <= effectiveBand) {
                end++;
            }
            List<RetrievedChunk> run = new ArrayList<>(chunks.subList(i, end));
            if (run.size() > 1) {
                run.sort(Comparator.comparingInt(c -> rankOf(c.sourceType())));
            }
            out.addAll(run);
            i = end;
        }
        return List.copyOf(out);
    }

    /** Authority rank of a raw {@code sourceType} string; unknown/null sorts last. */
    private int rankOf(String sourceType) {
        if (sourceType == null) {
            return Integer.MAX_VALUE;
        }
        try {
            return tierOf(SourceType.valueOf(sourceType)).rank();
        } catch (IllegalArgumentException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Returns a NEW {@link PlannedEvidence} with {@code links} stable-sorted
     * ascending by {@code tierOf(link.getAuthority()).rank()} (PRIMARY first, then
     * SECONDARY, then BACKGROUND). Ties keep the incoming order — the matcher's
     * {@code createdAt}-desc order — because Java's sort is stable and the
     * comparator has no tiebreaker. {@code pageGuides} are all tier
     * {@link AuthorityTier#CURRENT_PAGE_GUIDE}, so their relative order is
     * preserved as-is. Null-safe: {@code order(null)} and empty evidence return
     * {@link PlannedEvidence#empty()}.
     */
    public PlannedEvidence order(PlannedEvidence evidence) {
        if (evidence == null
                || (evidence.pageGuides().isEmpty() && evidence.links().isEmpty())) {
            return PlannedEvidence.empty();
        }
        List<BrainSourceLink> sortedLinks = evidence.links().stream()
                .sorted(Comparator.comparingInt(link -> tierOf(link.getAuthority()).rank()))
                .toList();
        return new PlannedEvidence(evidence.pageGuides(), sortedLinks);
    }
}
