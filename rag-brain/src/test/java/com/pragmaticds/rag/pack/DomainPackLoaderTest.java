package com.pragmaticds.rag.pack;

import com.pragmaticds.rag.service.ai.QuestionCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DomainPackLoaderTest {

    private static final Path TEST_PACK = Path.of("src/test/resources/packs/test-pack");

    private final DomainPackLoader loader = new DomainPackLoader();

    @TempDir
    Path tempDir;

    /** Copies the valid fixture pack, then lets a test break one file. */
    private Path packCopy() throws IOException {
        for (String f : List.of("pack.yaml", "prompt.yaml", "guardrails.yaml",
                "classifier.yaml", "retrieval.yaml")) {
            Files.copy(TEST_PACK.resolve(f), tempDir.resolve(f));
        }
        return tempDir;
    }

    @Test
    void packWithoutAnalyzersFileYieldsEmptyList() {
        DomainPack pack = loader.load(TEST_PACK);
        assertNotNull(pack.analyzers(), "analyzers must never be null");
        assertTrue(pack.analyzers().isEmpty(), "a pack with no analyzers.yaml has no analyzers");
    }

    @Test
    void loadsAllFiveFilesIntoOnePack() {
        DomainPack pack = loader.load(TEST_PACK);

        assertEquals("testco", pack.slug());
        assertEquals("Test Company", pack.companyName());
        assertEquals("Educational only.", pack.disclaimer());
        assertEquals("Hard: %s\nSoft: %s\nContext: %s\nQuestion: %s\nDisclaimer: %s\n", pack.promptTemplate());
        assertEquals("H1", pack.hardRules());
        assertEquals("G1", pack.guidance());

        assertEquals(List.of("you are approved"), pack.guardrails().prohibitedPhrases());
        assertEquals("you are eligible", pack.guardrails().eligiblePhrase());
        assertEquals("No source.", pack.guardrails().cannedAnswers().noSource());
        assertEquals("Escalate.", pack.guardrails().cannedAnswers().escalation());
        assertEquals("No legal.", pack.guardrails().cannedAnswers().legal());
        assertEquals("No tax.", pack.guardrails().cannedAnswers().tax());
        assertEquals("No rates.", pack.guardrails().cannedAnswers().liveRates());
        assertEquals("No fraud.", pack.guardrails().cannedAnswers().fraud());

        assertEquals(2, pack.classifierRules().size());
        assertEquals(QuestionCategory.FRAUD, pack.classifierRules().get(0).category());
        assertEquals(List.of("\\bfake\\b"), pack.classifierRules().get(0).patterns());

        assertEquals(Map.of("pmi", "private mortgage insurance"), pack.acronymExpansions());
        assertEquals(2, pack.programRules().size());
        assertEquals("VA", pack.programRules().get(1).program());
        assertEquals(List.of("\\bva\\b"), pack.programRules().get(1).wordPatterns());

        // Fix 3: null word-patterns branch — FHA has no word-patterns key in YAML
        assertEquals(List.of("fha"), pack.programRules().get(0).keywords());
        assertEquals(List.of(), pack.programRules().get(0).wordPatterns());
    }

    @Test
    void emptyAcronymsAndProgramsLoadSuccessfully() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("retrieval.yaml"), """
                acronyms: {}
                programs: []
                """);
        DomainPack pack = loader.load(dir);
        assertNotNull(pack.acronymExpansions());
        assertTrue(pack.acronymExpansions().isEmpty(), "empty acronyms must be allowed");
        assertNotNull(pack.programRules());
        assertTrue(pack.programRules().isEmpty(), "empty programs must be allowed");
        // Null-safety: building the per-brain bundle must not NPE (CompiledProgram.compile).
        BrainPackBundle bundle = BrainPackBundle.of(pack);
        assertTrue(bundle.programs().isEmpty());
        assertTrue(bundle.acronyms().isEmpty());
    }

    @Test
    void absentAcronymsAndProgramsLoadSuccessfully() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("retrieval.yaml"), "{}\n");
        DomainPack pack = loader.load(dir);
        assertTrue(pack.acronymExpansions().isEmpty());
        assertTrue(pack.programRules().isEmpty());
        assertDoesNotThrow(() -> BrainPackBundle.of(pack));
    }

    @Test
    void nullYamlDocumentFailsNamingTheFile() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("pack.yaml"), "---\n");
        var ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("pack.yaml"), ex.getMessage());
    }

    @Test
    void missingFileFailsNamingTheFile() throws IOException {
        Path dir = packCopy();
        Files.delete(dir.resolve("guardrails.yaml"));
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("guardrails.yaml"), ex.getMessage());
    }

    @Test
    void emptyProhibitedPhrasesFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("guardrails.yaml"), """
                prohibited-phrases: []
                eligible-phrase: you are eligible
                canned-answers:
                  no-source: a
                  escalation: b
                  legal: c
                  tax: d
                  live-rates: e
                  fraud: f
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("prohibited-phrases"), ex.getMessage());
    }

    @Test
    void blankCannedAnswerFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("guardrails.yaml"), """
                prohibited-phrases:
                  - you are approved
                eligible-phrase: you are eligible
                canned-answers:
                  no-source: ""
                  escalation: b
                  legal: c
                  tax: d
                  live-rates: e
                  fraud: f
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("no-source"), ex.getMessage());
    }

    @Test
    void templateWithoutFivePlaceholdersFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("prompt.yaml"),
                "template: only %s here\nhard-rules: |-\n  H1\nguidance: |-\n  G1\n");
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("template"), ex.getMessage());
        assertTrue(ex.getMessage().contains("5"), ex.getMessage());
    }

    @Test
    void blankHardRulesFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("prompt.yaml"),
                "template: 'Hard: %s\\nSoft: %s\\nContext: %s\\nQuestion: %s\\nDisclaimer: %s\\n'\n"
                + "hard-rules: \"\"\nguidance: |-\n  G1\n");
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("hard-rules"), ex.getMessage());
    }

    @Test
    void invalidRegexFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("classifier.yaml"), """
                rules:
                  - category: FRAUD
                    patterns:
                      - '([unclosed'
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("classifier.yaml"), ex.getMessage());
        assertTrue(ex.getMessage().contains("invalid regex"), ex.getMessage());
    }

    @Test
    void invalidSlugFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("pack.yaml"), """
                slug: "Not A Slug!"
                company-name: Test Company
                disclaimer: Educational only.
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("slug"), ex.getMessage());
    }

    // Locks the loader's strict mode: a typo'd key must fail, never be ignored.
    @Test
    void unknownYamlKeyFailsNamingTheFile() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("guardrails.yaml"), """
                prohibited-phrases:
                  - you are approved
                eligible-phrase: you are eligible
                eligible-phrases: oops-typo
                canned-answers:
                  no-source: a
                  escalation: b
                  legal: c
                  tax: d
                  live-rates: e
                  fraud: f
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("eligible-phrases"), ex.getMessage());
    }

    @Test
    void invalidRegexAlsoNamesBadRegexInMessage() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("classifier.yaml"), """
                rules:
                  - category: FRAUD
                    patterns:
                      - '([unclosed'
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("classifier.yaml"), ex.getMessage());
        assertTrue(ex.getMessage().contains("invalid regex"), ex.getMessage());
    }

    @Test
    void invalidWordPatternFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("retrieval.yaml"), """
                acronyms:
                  pmi: private mortgage insurance
                programs:
                  - program: VA
                    keywords:
                      - veteran
                    word-patterns:
                      - '([unclosed'
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("invalid regex"), ex.getMessage());
    }

    @Test
    void danglingListEntryFailsNamingFileAndField() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("guardrails.yaml"), """
                prohibited-phrases:
                  - you are approved
                  -
                eligible-phrase: you are eligible
                canned-answers:
                  no-source: a
                  escalation: b
                  legal: c
                  tax: d
                  live-rates: e
                  fraud: f
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("prohibited-phrases"), ex.getMessage());
    }

    @Test
    void emptyAcronymValueFailsNamingTheEntry() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("retrieval.yaml"), """
                acronyms:
                  pmi:
                programs:
                  - program: FHA
                    keywords:
                      - fha
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("acronyms"), ex.getMessage());
    }

    @Test
    void templateWithStrayFormatDirectiveFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("prompt.yaml"),
                "template: 'a %s b %s c %s d %s e %s plus stray %q'\nhard-rules: |-\n  H1\nguidance: |-\n  G1\n");
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("format string"), ex.getMessage());
    }

    @Test
    void mixedCaseProhibitedPhraseFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("guardrails.yaml"), """
                prohibited-phrases:
                  - You Are Approved
                eligible-phrase: you are eligible
                canned-answers:
                  no-source: a
                  escalation: b
                  legal: c
                  tax: d
                  live-rates: e
                  fraud: f
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("lowercase"), ex.getMessage());
    }

    @Test
    void mixedCaseEligiblePhraseFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("guardrails.yaml"), """
                prohibited-phrases:
                  - you are approved
                eligible-phrase: You Are Eligible
                canned-answers:
                  no-source: a
                  escalation: b
                  legal: c
                  tax: d
                  live-rates: e
                  fraud: f
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("lowercase"), ex.getMessage());
        assertTrue(ex.getMessage().contains("You Are Eligible"), ex.getMessage());
    }

    @Test
    void mixedCaseProgramKeywordFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("retrieval.yaml"), """
                acronyms:
                  pmi: private mortgage insurance
                programs:
                  - program: FHA
                    keywords:
                      - FHA
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("lowercase"), ex.getMessage());
    }

    @Test
    void duplicateClassifierCategoryFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("classifier.yaml"), """
                rules:
                  - category: FRAUD
                    patterns:
                      - '\\bfake\\b'
                  - category: FRAUD
                    patterns:
                      - '\\bforged\\b'
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("duplicate"), ex.getMessage());
        assertTrue(ex.getMessage().contains("FRAUD"), ex.getMessage());
    }

    @Test
    void loadsAnalyzersFile() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: income
                    display-name: Income
                    base-prompt: Analyze income.
                    retrieval-query-template: income calculation guidelines
                    retrieval-top-k: 8
                    output-schema: '{"items":[]}'
                  - slug: documents
                    display-name: Classifier
                    base-prompt: Classify docs.
                    retrieval-query-template: null
                    retrieval-top-k: 0
                    output-schema: '{"items":[]}'
                """);
        DomainPack pack = loader.load(dir);
        assertEquals(2, pack.analyzers().size());
        AnalyzerConfig income = pack.analyzers().get(0);
        assertEquals("income", income.slug());
        assertEquals("income calculation guidelines", income.retrievalQueryTemplate());
        assertEquals(8, income.retrievalTopK());
        assertNull(pack.analyzers().get(1).retrievalQueryTemplate(),
                "documents classifier has null retrieval template");
    }

    @Test
    void loadsPageSelectionFile() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("page-selection.yaml"), """
                profiles:
                  - name: federal-income
                    min-pages: 8
                    continuation-window: 2
                    keep:
                      - name: "1040"
                        patterns: ["Form 1040", "U.S. Individual Income Tax Return"]
                      - name: "Schedule E"
                        patterns: ["SCHEDULE E"]
                    drop-hints: ["State of ", "Worksheet"]
                """);
        DomainPack pack = loader.load(dir);
        assertEquals(1, pack.pageSelectionProfiles().size());
        PageSelectionProfile p = pack.pageSelectionProfiles().get(0);
        assertEquals("federal-income", p.name());
        assertEquals(8, p.minPages());
        assertEquals(2, p.continuationWindow());
        assertEquals(List.of("1040", "Schedule E"),
                p.keep().stream().map(PageSelectionProfile.KeepRule::name).toList());
        assertEquals(List.of("Form 1040", "U.S. Individual Income Tax Return"),
                p.keep().get(0).patterns());
        assertEquals(List.of("State of ", "Worksheet"), p.dropHints());
    }

    @Test
    void packWithoutPageSelectionFileYieldsEmptyProfiles() throws IOException {
        DomainPack pack = loader.load(packCopy());
        assertTrue(pack.pageSelectionProfiles().isEmpty());
    }

    @Test
    void pageSelectionNullPatternEntryFailsFast() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("page-selection.yaml"), """
                profiles:
                  - name: federal-income
                    min-pages: 8
                    continuation-window: 2
                    keep:
                      - name: "1040"
                        patterns: ["Form 1040", null]
                """);
        DomainPackLoader.PackValidationException ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("page-selection.yaml"), ex.getMessage());
    }

    @Test
    void envelopeFieldLoadsDefaultsNullAndMapsIsV2() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: income
                    display-name: Income
                    base-prompt: Analyze income.
                    retrieval-top-k: 0
                    output-schema: '{}'
                """);
        AnalyzerConfig withoutKey = loader.load(dir).analyzers().get(0);
        assertNull(withoutKey.envelope(), "absent envelope key must load as null (v1 default)");
        assertFalse(withoutKey.isV2(), "null envelope means v1");

        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: income
                    display-name: Income
                    base-prompt: Analyze income.
                    retrieval-top-k: 0
                    output-schema: '{}'
                    envelope: v1
                """);
        AnalyzerConfig withV1 = loader.load(dir).analyzers().get(0);
        assertEquals("v1", withV1.envelope(), "explicit envelope: v1 must pass validation and load");
        assertFalse(withV1.isV2(), "explicit v1 is not v2 even though envelope is non-null");

        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: income
                    display-name: Income
                    base-prompt: Analyze income.
                    retrieval-top-k: 0
                    output-schema: '{}'
                    envelope: v2
                """);
        AnalyzerConfig withV2 = loader.load(dir).analyzers().get(0);
        assertEquals("v2", withV2.envelope());
        assertTrue(withV2.isV2());
    }

    @Test
    void invalidEnvelopeValueFailsValidation() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: income
                    display-name: Income
                    base-prompt: Analyze income.
                    retrieval-top-k: 0
                    output-schema: '{}'
                    envelope: v3
                """);
        DomainPackLoader.PackValidationException ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("analyzers.yaml"), ex.getMessage());
        assertTrue(ex.getMessage().contains("income"),
                "message must name the analyzer slug: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("envelope"), ex.getMessage());
    }

    @Test
    void instanceSlugsLoadAndDefaultToEmpty() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: assets-v2
                    display-name: Assets
                    base-prompt: x
                    retrieval-top-k: 0
                    output-schema: '{}'
                    instance-slugs: [asset-analysis]
                  - slug: income
                    display-name: Income
                    base-prompt: x
                    retrieval-top-k: 0
                    output-schema: '{}'
                """);
        DomainPack pack = loader.load(dir);
        assertEquals(List.of("asset-analysis"), pack.analyzers().get(0).instanceSlugs());
        assertEquals(List.of(), pack.analyzers().get(1).instanceSlugs());
    }

    @Test
    void anInstanceSlugThatIsAlsoAnAnalyzerSlugFailsFast() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: assets-v2
                    display-name: Assets
                    base-prompt: x
                    retrieval-top-k: 0
                    output-schema: '{}'
                    instance-slugs: [income]
                  - slug: income
                    display-name: Income
                    base-prompt: x
                    retrieval-top-k: 0
                    output-schema: '{}'
                """);
        DomainPackLoader.PackValidationException ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("instance-slugs"), ex.getMessage());
        assertTrue(ex.getMessage().contains("income"), ex.getMessage());
    }

    @Test
    void anInstanceSlugClaimedByTwoAnalyzersFailsFast() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: assets
                    display-name: Assets
                    base-prompt: x
                    retrieval-top-k: 0
                    output-schema: '{}'
                    instance-slugs: [asset-analysis]
                  - slug: assets-v2
                    display-name: Assets v2
                    base-prompt: x
                    retrieval-top-k: 0
                    output-schema: '{}'
                    instance-slugs: [asset-analysis]
                """);
        DomainPackLoader.PackValidationException ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("asset-analysis"), ex.getMessage());
    }

    @Test
    void aMalformedInstanceSlugFailsFast() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: assets-v2
                    display-name: Assets
                    base-prompt: x
                    retrieval-top-k: 0
                    output-schema: '{}'
                    instance-slugs: ['Asset Analysis']
                """);
        DomainPackLoader.PackValidationException ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("instance-slugs"), ex.getMessage());
    }

    @Test
    void analyzerWithBlankSlugFailsFast() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: ' '
                    display-name: Bad
                    base-prompt: x
                    retrieval-top-k: 0
                    output-schema: '{}'
                """);
        DomainPackLoader.PackValidationException ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("analyzers.yaml"), ex.getMessage());
        assertTrue(ex.getMessage().contains("slug"), ex.getMessage());
    }

    @Test
    void duplicateAnalyzerSlugFailsFast() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: income
                    display-name: A
                    base-prompt: x
                    retrieval-top-k: 0
                    output-schema: '{}'
                  - slug: income
                    display-name: B
                    base-prompt: y
                    retrieval-top-k: 0
                    output-schema: '{}'
                """);
        DomainPackLoader.PackValidationException ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("duplicate analyzer slug"), ex.getMessage());
    }

    @Test
    void analyzerPageSelectionProfileLoadsAndDefaultsNull() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("page-selection.yaml"), """
                profiles:
                  - name: federal-income
                    min-pages: 8
                    keep:
                      - name: "1040"
                        patterns: ["Form 1040"]
                """);
        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: income
                    display-name: Income
                    base-prompt: Analyze income.
                    retrieval-query-template: income calculation guidelines
                    retrieval-top-k: 8
                    output-schema: '{"items":[]}'
                    page-selection-profile: federal-income
                  - slug: assets
                    display-name: Assets
                    base-prompt: Analyze assets.
                    retrieval-query-template: asset guidelines
                    retrieval-top-k: 4
                    output-schema: '{"items":[]}'
                """);
        DomainPack pack = loader.load(dir);
        assertEquals("federal-income", pack.analyzers().get(0).pageSelectionProfile());
        assertNull(pack.analyzers().get(1).pageSelectionProfile(),
                "an analyzer that does not opt in has no profile");
        assertNotNull(pack.pageSelectionProfile("federal-income"));
        assertNull(pack.pageSelectionProfile("nope"));
    }

    @Test
    void analyzerReferencingUnknownPageSelectionProfileFailsFast() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("analyzers.yaml"), """
                analyzers:
                  - slug: income
                    display-name: Income
                    base-prompt: Analyze income.
                    retrieval-query-template: income calculation guidelines
                    retrieval-top-k: 8
                    output-schema: '{"items":[]}'
                    page-selection-profile: does-not-exist
                """);
        DomainPackLoader.PackValidationException e = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(e.getMessage().contains("does-not-exist"));
    }

    // ---- extractors.yaml (Task A1) ------------------------------------------------

    @Test
    void packWithoutExtractorsFileYieldsEmptyList() {
        DomainPack pack = loader.load(TEST_PACK);
        assertNotNull(pack.extractors(), "extractors must never be null");
        assertTrue(pack.extractors().isEmpty(), "a pack with no extractors.yaml has no extractors");
    }

    @Test
    void loadsExtractorsFile() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("extractors.yaml"), """
                extractors:
                  - slug: income-schedule-c
                    display-name: Schedule C
                    base-prompt: "test prompt"
                    value-keys: [netProfit, depreciation]
                    meta-keys: [taxYear, businessName]
                    model-override: null
                """);
        DomainPack pack = loader.load(dir);
        assertEquals(1, pack.extractors().size());
        ExtractorConfig ex = pack.extractors().get(0);
        assertEquals("income-schedule-c", ex.slug());
        assertEquals("Schedule C", ex.displayName());
        assertEquals("test prompt", ex.basePrompt());
        assertEquals(List.of("netProfit", "depreciation"), ex.valueKeys());
        assertEquals(List.of("taxYear", "businessName"), ex.metaKeys());
        assertNull(ex.modelOverride());
    }

    @Test
    void extractorMetaKeysMayBeEmptyWhenAbsent() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("extractors.yaml"), """
                extractors:
                  - slug: income-schedule-c
                    display-name: Schedule C
                    base-prompt: x
                    value-keys: [netProfit]
                """);
        DomainPack pack = loader.load(dir);
        assertTrue(pack.extractors().get(0).metaKeys().isEmpty());
    }

    @Test
    void duplicateExtractorSlugFailsFast() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("extractors.yaml"), """
                extractors:
                  - slug: income-schedule-c
                    display-name: A
                    base-prompt: x
                    value-keys: [netProfit]
                    meta-keys: [taxYear]
                  - slug: income-schedule-c
                    display-name: B
                    base-prompt: y
                    value-keys: [netProfit]
                    meta-keys: [taxYear]
                """);
        DomainPackLoader.PackValidationException ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("extractors.yaml"), ex.getMessage());
        assertTrue(ex.getMessage().contains("duplicate extractor slug"), ex.getMessage());
    }

    @Test
    void extractorWithBlankPromptFailsFast() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("extractors.yaml"), """
                extractors:
                  - slug: income-schedule-c
                    display-name: Schedule C
                    base-prompt: ""
                    value-keys: [netProfit]
                    meta-keys: [taxYear]
                """);
        DomainPackLoader.PackValidationException ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("extractors.yaml"), ex.getMessage());
        assertTrue(ex.getMessage().contains("base-prompt"), ex.getMessage());
    }

    @Test
    void extractorWithBadSlugFailsFast() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("extractors.yaml"), """
                extractors:
                  - slug: "Not A Slug!"
                    display-name: Bad
                    base-prompt: x
                    value-keys: [netProfit]
                    meta-keys: [taxYear]
                """);
        DomainPackLoader.PackValidationException ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("extractors.yaml"), ex.getMessage());
        assertTrue(ex.getMessage().contains("slug"), ex.getMessage());
    }

    @Test
    void extractorWithEmptyValueKeysFailsFast() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("extractors.yaml"), """
                extractors:
                  - slug: income-schedule-c
                    display-name: Schedule C
                    base-prompt: x
                    value-keys: []
                    meta-keys: [taxYear]
                """);
        DomainPackLoader.PackValidationException ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("extractors.yaml"), ex.getMessage());
        assertTrue(ex.getMessage().contains("value-keys"), ex.getMessage());
    }
}
