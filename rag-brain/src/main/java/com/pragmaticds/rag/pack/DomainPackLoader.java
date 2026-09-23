package com.pragmaticds.rag.pack;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.pragmaticds.rag.service.ai.QuestionCategory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads the required YAML files of a domain pack directory into a DomainPack.
 * Optional source-links.yaml and page-guides.yaml seed admin registries.
 * Throws PackValidationException naming the exact file (and field) on any
 * problem — the application must fail to boot rather than run with a partial
 * compliance layer.
 */
public class DomainPackLoader {

    private final ObjectMapper yaml = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    // Intermediate per-file shapes (kebab-case keys map to these components).
    private record PackFile(String slug, String companyName, String disclaimer) {}
    private record PromptFile(String template, String hardRules, String guidance) {}
    private record GuardrailsFile(List<String> prohibitedPhrases, String eligiblePhrase,
                                  DomainPack.CannedAnswers cannedAnswers) {}
    private record ClassifierFile(List<ClassifierRuleFile> rules) {}
    private record ClassifierRuleFile(QuestionCategory category, List<String> patterns) {}
    private record RetrievalFile(Map<String, String> acronyms, List<ProgramFile> programs) {}
    private record ProgramFile(String program, List<String> keywords, List<String> wordPatterns) {}
    private record SourceLinksFile(List<SourceLinkFile> links) {}
    private record SourceLinkFile(String name, String url, String domain, String authority,
                                  List<String> topics, boolean freshnessRequired,
                                  List<String> allowedUse, List<String> doNotUseFor,
                                  String surface) {}
    private record PageGuidesFile(List<PageGuideFile> guides) {}
    private record PageGuideFile(String route, String title, String purpose, String surface,
                                 List<String> userIntents, List<String> allowedGuidance,
                                 List<InternalLinkFile> internalLinks, List<String> topics) {}
    private record InternalLinkFile(String label, String url) {}
    private record AnalyzersFile(List<AnalyzerFile> analyzers) {}
    private record AnalyzerFile(String slug, String displayName, String basePrompt,
                                String retrievalQueryTemplate, int retrievalTopK,
                                String outputSchema, String modelOverride,
                                String providerOverride, String corpusScope,
                                String envelope, String pageSelectionProfile,
                                AssetsRules assetsRules, List<String> instanceSlugs) {}
    private record PageSelectionFile(List<PageSelectionProfileFile> profiles) {}
    private record PageSelectionProfileFile(String name, Integer minPages, Integer continuationWindow,
                                            List<KeepRuleFile> keep, List<String> dropHints) {}
    private record KeepRuleFile(String name, List<String> patterns) {}
    private record ExtractorsFile(List<ExtractorFile> extractors) {}
    private record ExtractorFile(String slug, String displayName, String basePrompt,
                                 List<String> valueKeys, List<String> metaKeys, String modelOverride) {}

    public DomainPack load(Path packDir) {
        PackFile packFile = read(packDir, "pack.yaml", PackFile.class);
        PromptFile promptFile = read(packDir, "prompt.yaml", PromptFile.class);
        GuardrailsFile guardrailsFile = read(packDir, "guardrails.yaml", GuardrailsFile.class);
        ClassifierFile classifierFile = read(packDir, "classifier.yaml", ClassifierFile.class);
        RetrievalFile retrievalFile = read(packDir, "retrieval.yaml", RetrievalFile.class);
        SourceLinksFile sourceLinksFile = readOptional(packDir, "source-links.yaml", SourceLinksFile.class);
        PageGuidesFile pageGuidesFile = readOptional(packDir, "page-guides.yaml", PageGuidesFile.class);
        AnalyzersFile analyzersFile = readOptional(packDir, "analyzers.yaml", AnalyzersFile.class);
        PageSelectionFile pageSelectionFile =
                readOptional(packDir, "page-selection.yaml", PageSelectionFile.class);
        ExtractorsFile extractorsFile = readOptional(packDir, "extractors.yaml", ExtractorsFile.class);

        // Element-level checks BEFORE assembly: List.copyOf/Map.copyOf in the
        // record constructors reject null elements with a bare NPE, which
        // would bypass the file+field error contract.
        requireElementsNotBlank(packDir, "guardrails.yaml", "prohibited-phrases",
                guardrailsFile.prohibitedPhrases());
        requireLowercase(packDir, "guardrails.yaml", "prohibited-phrases",
                guardrailsFile.prohibitedPhrases());
        requireLowercase(packDir, "guardrails.yaml", "eligible-phrase",
                guardrailsFile.eligiblePhrase() == null ? null
                        : List.of(guardrailsFile.eligiblePhrase()));
        if (classifierFile.rules() != null) {
            for (ClassifierRuleFile rule : classifierFile.rules()) {
                requireElementsNotBlank(packDir, "classifier.yaml", "rules.patterns", rule.patterns());
            }
        }
        if (retrievalFile.acronyms() != null) {
            for (Map.Entry<String, String> entry : retrievalFile.acronyms().entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().isBlank()) {
                    throw new PackValidationException("domain pack " + packDir
                            + ": retrieval.yaml: invalid or empty acronyms entry \"" + entry.getKey() + "\"");
                }
                // Acronym keys are matched against lowercased text — must be lowercase.
                if (!entry.getKey().equals(entry.getKey().toLowerCase(java.util.Locale.US))) {
                    throw new PackValidationException("domain pack " + packDir
                            + ": retrieval.yaml: entry must be lowercase: \"" + entry.getKey() + "\"");
                }
            }
        }
        if (retrievalFile.programs() != null) {
            for (ProgramFile program : retrievalFile.programs()) {
                requireElementsNotBlank(packDir, "retrieval.yaml", "programs.keywords", program.keywords());
                requireElementsNotBlank(packDir, "retrieval.yaml", "programs.word-patterns", program.wordPatterns());
                requireLowercase(packDir, "retrieval.yaml", "programs.keywords", program.keywords());
            }
        }
        if (pageSelectionFile != null && pageSelectionFile.profiles() != null) {
            for (PageSelectionProfileFile profile : pageSelectionFile.profiles()) {
                requireElementsNotBlank(packDir, "page-selection.yaml", "drop-hints", profile.dropHints());
                if (profile.keep() != null) {
                    for (KeepRuleFile rule : profile.keep()) {
                        requireElementsNotBlank(packDir, "page-selection.yaml", "keep.patterns", rule.patterns());
                    }
                }
            }
        }

        DomainPack pack = new DomainPack(
                packFile.slug(),
                packFile.companyName(),
                packFile.disclaimer(),
                promptFile.template(),
                promptFile.hardRules(),
                promptFile.guidance(),
                new DomainPack.Guardrails(
                        guardrailsFile.prohibitedPhrases(),
                        guardrailsFile.eligiblePhrase(),
                        guardrailsFile.cannedAnswers()),
                classifierFile.rules() == null ? null : classifierFile.rules().stream()
                        .map(r -> new DomainPack.ClassifierRule(r.category(), r.patterns()))
                        .toList(),
                retrievalFile.acronyms() == null ? java.util.Map.of() : retrievalFile.acronyms(),
                retrievalFile.programs() == null ? java.util.List.of() : retrievalFile.programs().stream()
                        .map(p -> new DomainPack.ProgramRule(
                                p.program(),
                                p.keywords() == null ? List.of() : p.keywords(),
                                p.wordPatterns() == null ? List.of() : p.wordPatterns()))
                        .toList(),
                (sourceLinksFile == null || sourceLinksFile.links() == null) ? List.of()
                        : sourceLinksFile.links().stream()
                                .map(s -> new DomainPack.SourceLink(
                                        s.name(), s.url(), s.domain(), s.authority(),
                                        s.topics(), s.freshnessRequired(),
                                        s.allowedUse(), s.doNotUseFor(), s.surface()))
                                .toList(),
                (pageGuidesFile == null || pageGuidesFile.guides() == null) ? List.of()
                        : pageGuidesFile.guides().stream()
                                .map(g -> new DomainPack.PageGuide(
                                        g.route(), g.title(), g.purpose(), g.surface(),
                                        g.userIntents(), g.allowedGuidance(),
                                        g.internalLinks() == null ? List.of()
                                                : g.internalLinks().stream()
                                                        .map(l -> new DomainPack.InternalLink(l.label(), l.url()))
                                                        .toList(),
                                        g.topics()))
                                .toList(),
                (analyzersFile == null || analyzersFile.analyzers() == null) ? List.of()
                        : analyzersFile.analyzers().stream()
                                .map(a -> new AnalyzerConfig(
                                        a.slug(), a.displayName(), a.basePrompt(),
                                        a.retrievalQueryTemplate(), a.retrievalTopK(),
                                        a.outputSchema(), a.modelOverride(), a.providerOverride(),
                                        a.corpusScope(), a.envelope(), a.pageSelectionProfile(),
                                        a.assetsRules(), a.instanceSlugs()))
                                .toList(),
                (pageSelectionFile == null || pageSelectionFile.profiles() == null) ? List.of()
                        : pageSelectionFile.profiles().stream()
                                .map(f -> new PageSelectionProfile(
                                        f.name(),
                                        f.minPages() == null ? 0 : f.minPages(),
                                        f.continuationWindow() == null ? 1 : f.continuationWindow(),
                                        f.keep() == null ? List.of() : f.keep().stream()
                                                .map(k -> new PageSelectionProfile.KeepRule(
                                                        k.name(), k.patterns()))
                                                .toList(),
                                        f.dropHints()))
                                .toList(),
                (extractorsFile == null || extractorsFile.extractors() == null) ? List.of()
                        : extractorsFile.extractors().stream()
                                .map(e -> new ExtractorConfig(
                                        e.slug(), e.displayName(), e.basePrompt(),
                                        e.valueKeys(), e.metaKeys(), e.modelOverride()))
                                .toList());

        validate(packDir, pack);
        return pack;
    }

    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]+");
    /** The Lab's own instance-slug rule (lab_instance.chk_lab_instance_slug). */
    private static final Pattern INSTANCE_SLUG = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    private void validate(Path dir, DomainPack p) {
        require(dir, "pack.yaml", "slug (must match [a-z0-9-]+)", p.slug() != null && SLUG.matcher(p.slug()).matches());
        require(dir, "pack.yaml", "company-name", notBlank(p.companyName()));
        require(dir, "pack.yaml", "disclaimer", notBlank(p.disclaimer()));

        require(dir, "prompt.yaml", "template (needs exactly 5 %s placeholders)",
                p.promptTemplate() != null && p.promptTemplate().split("%s", -1).length == 6);
        try {
            p.promptTemplate().formatted("", "", "", "", "");
        } catch (IllegalFormatException e) {
            throw new PackValidationException("domain pack " + dir
                    + ": prompt.yaml: template is not a valid format string: " + e.getMessage());
        }
        require(dir, "prompt.yaml", "hard-rules", notBlank(p.hardRules()));
        require(dir, "prompt.yaml", "guidance", notBlank(p.guidance()));

        require(dir, "guardrails.yaml", "prohibited-phrases",
                p.guardrails() != null && p.guardrails().prohibitedPhrases() != null
                        && !p.guardrails().prohibitedPhrases().isEmpty());
        require(dir, "guardrails.yaml", "eligible-phrase", notBlank(p.guardrails().eligiblePhrase()));
        DomainPack.CannedAnswers c = p.guardrails().cannedAnswers();
        require(dir, "guardrails.yaml", "canned-answers", c != null);
        require(dir, "guardrails.yaml", "canned-answers.no-source", notBlank(c.noSource()));
        require(dir, "guardrails.yaml", "canned-answers.escalation", notBlank(c.escalation()));
        require(dir, "guardrails.yaml", "canned-answers.legal", notBlank(c.legal()));
        require(dir, "guardrails.yaml", "canned-answers.tax", notBlank(c.tax()));
        require(dir, "guardrails.yaml", "canned-answers.live-rates", notBlank(c.liveRates()));
        require(dir, "guardrails.yaml", "canned-answers.fraud", notBlank(c.fraud()));

        require(dir, "classifier.yaml", "rules",
                p.classifierRules() != null && !p.classifierRules().isEmpty());
        EnumSet<QuestionCategory> seen = EnumSet.noneOf(QuestionCategory.class);
        for (DomainPack.ClassifierRule rule : p.classifierRules()) {
            require(dir, "classifier.yaml", "rules.category", rule.category() != null);
            if (!seen.add(rule.category())) {
                throw new PackValidationException("domain pack " + dir
                        + ": classifier.yaml: duplicate category " + rule.category());
            }
            require(dir, "classifier.yaml", "rules.patterns",
                    rule.patterns() != null && !rule.patterns().isEmpty());
            compileAll(dir, "classifier.yaml", rule.patterns());
        }

        // acronyms + programs are OPTIONAL — a neutral pack may carry neither.
        // Per-element checks (above, in load()) still run when present.
        for (DomainPack.ProgramRule rule : p.programRules()) {
            require(dir, "retrieval.yaml", "programs.program", notBlank(rule.program()));
            compileAll(dir, "retrieval.yaml", rule.wordPatterns());
        }

        // analyzers.yaml is OPTIONAL — an empty/absent file means no analyzers.
        java.util.Set<String> analyzerSlugs = new java.util.HashSet<>();
        for (AnalyzerConfig a : p.analyzers()) {
            require(dir, "analyzers.yaml", "slug (must match [a-z0-9-]+)",
                    a.slug() != null && SLUG.matcher(a.slug()).matches());
            if (!analyzerSlugs.add(a.slug())) {
                throw new PackValidationException("domain pack " + dir
                        + ": analyzers.yaml: duplicate analyzer slug " + a.slug());
            }
            require(dir, "analyzers.yaml", "display-name", notBlank(a.displayName()));
            require(dir, "analyzers.yaml", "base-prompt", notBlank(a.basePrompt()));
            require(dir, "analyzers.yaml", "output-schema", notBlank(a.outputSchema()));
            require(dir, "analyzers.yaml", "retrieval-top-k (must be >= 0)", a.retrievalTopK() >= 0);
            require(dir, "analyzers.yaml", "retrieval-query-template",
                    a.retrievalQueryTemplate() == null || !a.retrievalQueryTemplate().isBlank());
            require(dir, "analyzers.yaml", "corpus-scope (must match [a-z0-9-]+ when set)",
                    a.corpusScope() == null || SLUG.matcher(a.corpusScope()).matches());
            if (a.envelope() != null
                    && !"v1".equals(a.envelope()) && !"v2".equals(a.envelope())) {
                throw new PackValidationException("domain pack " + dir
                        + ": analyzers.yaml: analyzer " + a.slug()
                        + ": envelope must be v1 or v2, got \"" + a.envelope() + "\"");
            }
            if (a.pageSelectionProfile() != null
                    && p.pageSelectionProfile(a.pageSelectionProfile()) == null) {
                throw new PackValidationException("domain pack " + dir
                        + ": analyzers.yaml: analyzer " + a.slug()
                        + ": unknown page-selection-profile \"" + a.pageSelectionProfile() + "\"");
            }
        }

        // An instance slug names exactly one analyzer: it may not be an analyzer's own slug and
        // may not be claimed twice, or a pinned run would resolve to whichever came first.
        java.util.Set<String> instanceSlugs = new java.util.HashSet<>();
        for (AnalyzerConfig a : p.analyzers()) {
            for (String instance : a.instanceSlugs()) {
                if (instance == null || !INSTANCE_SLUG.matcher(instance).matches()) {
                    throw new PackValidationException("domain pack " + dir
                            + ": analyzers.yaml: analyzer " + a.slug()
                            + ": instance-slugs entry must match " + INSTANCE_SLUG.pattern()
                            + ", got \"" + instance + "\"");
                }
                if (analyzerSlugs.contains(instance) || !instanceSlugs.add(instance)) {
                    throw new PackValidationException("domain pack " + dir
                            + ": analyzers.yaml: analyzer " + a.slug()
                            + ": instance-slugs entry \"" + instance
                            + "\" is already an analyzer slug or claimed by another analyzer");
                }
            }
        }

        // extractors.yaml is OPTIONAL — an empty/absent file means no extractors.
        java.util.Set<String> extractorSlugs = new java.util.HashSet<>();
        for (ExtractorConfig ex : p.extractors()) {
            require(dir, "extractors.yaml", "slug (must match [a-z0-9-]+)",
                    ex.slug() != null && SLUG.matcher(ex.slug()).matches());
            if (!extractorSlugs.add(ex.slug())) {
                throw new PackValidationException("domain pack " + dir
                        + ": extractors.yaml: duplicate extractor slug " + ex.slug());
            }
            require(dir, "extractors.yaml", "display-name", notBlank(ex.displayName()));
            require(dir, "extractors.yaml", "base-prompt", notBlank(ex.basePrompt()));
            require(dir, "extractors.yaml", "value-keys (must be non-empty)",
                    ex.valueKeys() != null && !ex.valueKeys().isEmpty());
        }

        // page-selection.yaml is OPTIONAL — an absent file means no profiles.
        java.util.Set<String> profileNames = new java.util.HashSet<>();
        for (PageSelectionProfile sp : p.pageSelectionProfiles()) {
            require(dir, "page-selection.yaml", "name (must match [a-z0-9-]+)",
                    sp.name() != null && SLUG.matcher(sp.name()).matches());
            if (!profileNames.add(sp.name())) {
                throw new PackValidationException("domain pack " + dir
                        + ": page-selection.yaml: duplicate profile name " + sp.name());
            }
            require(dir, "page-selection.yaml", "min-pages (must be >= 0)", sp.minPages() >= 0);
            require(dir, "page-selection.yaml", "continuation-window (must be >= 0)",
                    sp.continuationWindow() >= 0);
            require(dir, "page-selection.yaml", "keep (must declare at least one rule)",
                    !sp.keep().isEmpty());
            for (PageSelectionProfile.KeepRule rule : sp.keep()) {
                require(dir, "page-selection.yaml", "keep.name", notBlank(rule.name()));
                require(dir, "page-selection.yaml", "keep.patterns (must be non-empty)",
                        !rule.patterns().isEmpty());
                for (String pattern : rule.patterns()) {
                    require(dir, "page-selection.yaml", "keep.patterns entry", notBlank(pattern));
                }
            }
        }
    }

    private void compileAll(Path dir, String file, List<String> patterns) {
        for (String pattern : patterns) {
            require(dir, file, "pattern", notBlank(pattern));
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new PackValidationException("domain pack " + dir + ": " + file
                        + ": invalid regex \"" + pattern + "\": " + e.getDescription());
            }
        }
    }

    /** Null list is fine here — list-level nullness/emptiness is checked in validate(). */
    private void requireElementsNotBlank(Path dir, String file, String field, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new PackValidationException(
                        "domain pack " + dir + ": " + file + ": blank entry in " + field);
            }
        }
    }

    /**
     * Entries that are matched against lowercased text at runtime must themselves
     * be lowercase — a mixed-case entry is a silently dead rule.
     * Null list is fine — emptiness/nullness is checked elsewhere.
     */
    private void requireLowercase(Path dir, String file, String field, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.equals(value.toLowerCase(java.util.Locale.US))) {
                throw new PackValidationException("domain pack " + dir + ": " + file
                        + ": entry must be lowercase: \"" + value + "\"");
            }
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private void require(Path dir, String file, String field, boolean ok) {
        if (!ok) {
            throw new PackValidationException(
                    "domain pack " + dir + ": " + file + ": invalid or empty " + field);
        }
    }

    private <T> T read(Path packDir, String fileName, Class<T> type) {
        Path file = packDir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new PackValidationException(
                    "domain pack " + packDir + ": missing required file " + fileName);
        }
        try {
            T parsed = yaml.readValue(file.toFile(), type);
            if (parsed == null) {
                throw new PackValidationException(
                        "domain pack " + packDir + ": " + fileName + ": file is empty");
            }
            return parsed;
        } catch (IOException e) {
            throw new PackValidationException(
                    "domain pack " + packDir + ": " + fileName + ": " + e.getMessage(), e);
        }
    }

    /** Optional pack file: a missing file returns null (caller defaults to empty). */
    private <T> T readOptional(Path packDir, String fileName, Class<T> type) {
        Path file = packDir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            T parsed = yaml.readValue(file.toFile(), type);
            if (parsed == null) {
                throw new PackValidationException(
                        "domain pack " + packDir + ": " + fileName + ": file is empty");
            }
            return parsed;
        } catch (IOException e) {
            throw new PackValidationException(
                    "domain pack " + packDir + ": " + fileName + ": " + e.getMessage(), e);
        }
    }

    /** Boot-blocking pack problem. */
    public static class PackValidationException extends RuntimeException {
        public PackValidationException(String message) { super(message); }
        public PackValidationException(String message, Throwable cause) { super(message, cause); }
    }
}
