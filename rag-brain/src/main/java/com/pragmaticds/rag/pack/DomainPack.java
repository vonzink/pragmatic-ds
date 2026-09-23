package com.pragmaticds.rag.pack;

import com.pragmaticds.rag.service.ai.QuestionCategory;

import java.util.List;
import java.util.Map;

/**
 * Everything company-specific about one brain, loaded from a pack directory.
 * Optional source-links/page-guides seed dashboard registries on first boot.
 * Immutable; services inject this instead of holding their own constants. Spec: docs/superpowers/specs/
 * 2026-06-10-rag-brain-platform-design.md §4.
 */
public record DomainPack(
        String slug,
        String companyName,
        String disclaimer,
        String promptTemplate,
        String hardRules,
        String guidance,
        Guardrails guardrails,
        List<ClassifierRule> classifierRules,
        Map<String, String> acronymExpansions,
        List<ProgramRule> programRules,
        List<SourceLink> sourceLinks,
        List<PageGuide> pageGuides,
        List<AnalyzerConfig> analyzers,
        List<PageSelectionProfile> pageSelectionProfiles,
        List<ExtractorConfig> extractors
) {

    public DomainPack {
        classifierRules = classifierRules == null ? null : List.copyOf(classifierRules);
        acronymExpansions = acronymExpansions == null ? null : Map.copyOf(acronymExpansions);
        programRules = programRules == null ? null : List.copyOf(programRules);
        sourceLinks = sourceLinks == null ? List.of() : List.copyOf(sourceLinks);
        pageGuides = pageGuides == null ? List.of() : List.copyOf(pageGuides);
        analyzers = analyzers == null ? List.of() : List.copyOf(analyzers);
        pageSelectionProfiles =
                pageSelectionProfiles == null ? List.of() : List.copyOf(pageSelectionProfiles);
        extractors = extractors == null ? List.of() : List.copyOf(extractors);
    }

    /** The named page-selection profile, or null when the pack declares none by that name. */
    public PageSelectionProfile pageSelectionProfile(String name) {
        if (name == null) {
            return null;
        }
        return pageSelectionProfiles.stream()
                .filter(p -> name.equals(p.name()))
                .findFirst()
                .orElse(null);
    }

    public record Guardrails(
            List<String> prohibitedPhrases,
            String eligiblePhrase,
            CannedAnswers cannedAnswers
    ) {
        public Guardrails {
            prohibitedPhrases = prohibitedPhrases == null ? null : List.copyOf(prohibitedPhrases);
        }
    }

    /**
     * The fixed refusal/escalation texts the pipeline can return. The first six are
     * required of every pack; {@code personalLookup} is optional (null when the pack
     * omits {@code personal-lookup}) and falls back to {@code escalation}, so packs
     * that predate it — including ones loaded from a data volume — keep loading.
     */
    public record CannedAnswers(
            String noSource,
            String escalation,
            String legal,
            String tax,
            String liveRates,
            String fraud,
            String personalLookup
    ) {
        /** The text for a {@link com.pragmaticds.rag.service.ai.QuestionCategory#PERSONAL_LOOKUP} refusal. */
        public String personalLookupOrEscalation() {
            return personalLookup == null || personalLookup.isBlank() ? escalation : personalLookup;
        }
    }

    /** One classifier category with its regex patterns; list order = check order. */
    public record ClassifierRule(QuestionCategory category, List<String> patterns) {
        public ClassifierRule {
            patterns = patterns == null ? null : List.copyOf(patterns);
        }
    }

    /**
     * Program detection for program-aware ranking: substring keywords plus
     * word-boundary regex patterns (e.g. "\\bva\\b" so "available" never
     * matches VA). List order = priority order.
     */
    public record ProgramRule(String program, List<String> keywords, List<String> wordPatterns) {
        public ProgramRule {
            keywords = keywords == null ? null : List.copyOf(keywords);
            wordPatterns = wordPatterns == null ? null : List.copyOf(wordPatterns);
        }
    }

    public record SourceLink(
            String name,
            String url,
            String domain,
            String authority,
            List<String> topics,
            boolean freshnessRequired,
            List<String> allowedUse,
            List<String> doNotUseFor,
            String surface
    ) {
        public SourceLink {
            topics = topics == null ? List.of() : List.copyOf(topics);
            allowedUse = allowedUse == null ? List.of() : List.copyOf(allowedUse);
            doNotUseFor = doNotUseFor == null ? List.of() : List.copyOf(doNotUseFor);
        }
    }

    public record PageGuide(
            String route,
            String title,
            String purpose,
            String surface,
            List<String> userIntents,
            List<String> allowedGuidance,
            List<InternalLink> internalLinks,
            List<String> topics
    ) {
        public PageGuide {
            userIntents = userIntents == null ? List.of() : List.copyOf(userIntents);
            allowedGuidance = allowedGuidance == null ? List.of() : List.copyOf(allowedGuidance);
            internalLinks = internalLinks == null ? List.of() : List.copyOf(internalLinks);
            topics = topics == null ? List.of() : List.copyOf(topics);
        }
    }

    public record InternalLink(String label, String url) {}
}
