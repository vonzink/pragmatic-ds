package com.pragmaticds.docengine.reuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.ai.AiBehaviorIdentity;
import com.pragmaticds.docengine.classification.rules.RulePackLoader;
import com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader;
import com.pragmaticds.docengine.orchestration.ParserPort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

/**
 * The behavior fingerprint: SHA-256 over the DOCENGINE-C14N-1 bytes of
 * {@code {engineRelease, parserAdapter, worker, classificationPacks, extractionSchemas,
 * aiExtraction}}. Reuse is
 * only ever admitted on fingerprint equality, so these tests pin the properties that make equality
 * honest: canonical (repo order and jsonb key order never matter), complete (every behavior input
 * moves it), and fail-open (a missing input yields NO fingerprint, never a guessed one).
 */
class ReuseFingerprintServiceTest {

    private static final String WORKER_VERSION_BODY =
            """
            {"worker":"0.9.1","stateless":true,
             "libraries":{"pdfplumber":"0.11.10","pypdfium2":"5.12.1","pytesseract":null}}
            """;

    private RulePackLoader packs;
    private ExtractionSchemaLoader schemas;
    private WorkerVersionProbe worker;
    private ParserPort adapter;
    private AiBehaviorIdentity aiExtraction;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BuildProperties> noBuildInfo = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        packs = mock(RulePackLoader.class);
        schemas = mock(ExtractionSchemaLoader.class);
        worker = mock(WorkerVersionProbe.class);
        adapter = mock(ParserPort.class);
        aiExtraction = mock(AiBehaviorIdentity.class);
        // The default posture of every deployment that has not turned the seam on.
        when(aiExtraction.aiBehaviorIdentity())
                .thenReturn(Optional.of(Map.of("enabled", "false")));
        when(adapter.behaviorIdentity()).thenReturn(Optional.of("worker"));
        when(noBuildInfo.getIfAvailable()).thenReturn(null);
        when(worker.version())
                .thenReturn(
                        Optional.of(
                                parse(WORKER_VERSION_BODY)));
        when(packs.fingerprintViewForCurrentOrg()).thenReturn(defaultPacks());
        when(schemas.fingerprintViewForCurrentOrg()).thenReturn(defaultSchemas());
    }

    private static List<RulePackLoader.PackFingerprint> defaultPacks() {
        return List.of(
                new RulePackLoader.PackFingerprint(
                        "PAYSTUB",
                        "1.2.0",
                        new BigDecimal("0.70"),
                        "{\"targetScore\":6,\"anchors\":[{\"id\":\"a\",\"weight\":2}]}"),
                new RulePackLoader.PackFingerprint(
                        "W2",
                        "1.0.0",
                        new BigDecimal("0.75"),
                        "{\"targetScore\":8,\"anchors\":[{\"id\":\"b\",\"weight\":3}]}"));
    }

    private static List<ExtractionSchemaLoader.SchemaFingerprint> defaultSchemas() {
        return List.of(
                new ExtractionSchemaLoader.SchemaFingerprint(
                        "PAYSTUB", "2.0.0", "{\"fields\":[{\"name\":\"netPay\"}]}"),
                new ExtractionSchemaLoader.SchemaFingerprint(
                        "SCHEDULE_E", "1.1.0", "{\"fields\":[{\"name\":\"rentsReceived\"}]}"));
    }

    private ReuseFingerprintService service(String engineRelease) {
        return new ReuseFingerprintService(
                true, engineRelease, noBuildInfo, worker, adapter, packs, schemas, aiExtraction);
    }

    /** The AI seam switched ON, fully described — the shape every AI assertion below varies. */
    private static Map<String, String> enrichingAi() {
        // Map.of() tops out at 10 pairs; this shape has 11, so it is built via ofEntries instead.
        return new java.util.TreeMap<>(
                Map.ofEntries(
                        Map.entry("enabled", "true"),
                        Map.entry("paystubEnabled", "false"),
                        Map.entry("w2Enabled", "false"),
                        Map.entry("firstPass", "vertex-gemini/gemini-2.5-flash-lite"),
                        Map.entry("secondPass", "disabled"),
                        Map.entry("maxPages", "100"),
                        Map.entry("maxInputCharacters", "500000"),
                        Map.entry("pageImagesEnabled", "false"),
                        Map.entry("pageImageOcrFloor", "0.60"),
                        Map.entry("maxPageImages", "8"),
                        Map.entry("dialect.BANK_STATEMENT", "aaaa")));
    }

    /**
     * The master switch makes the feature INERT, not merely non-serving: with it off nothing is
     * composed, so nothing probes the worker, nothing consults the behavior-view loaders, and
     * nothing is stamped. An earlier shape gated only the candidate scan, and the finalizer went on
     * issuing a live {@code /version} probe for a column no probe would ever read.
     *
     * <p>The two never-consulted checks pin the finalizer's own {@code enabled} early-out (it
     * returns before reading a held view); the never-probed check pins the composer's (it returns
     * before {@code workerIdentity()}). Neuter either gate and this fails.
     */
    @Test
    void theMasterSwitchStopsTheFingerprintBeingComputedAtAll() {
        ReuseFingerprintService off =
                new ReuseFingerprintService(
                        false,
                        "release-1",
                        noBuildInfo,
                        worker,
                        adapter,
                        packs,
                        schemas,
                        aiExtraction);
        assertThat(off.fingerprint()).isEmpty();
        assertThat(off.fingerprintForCompletedRun(java.util.Set.of())).isEmpty();
        org.mockito.Mockito.verify(worker, org.mockito.Mockito.never()).version();
        org.mockito.Mockito.verify(packs, org.mockito.Mockito.never()).heldFingerprintView();
        org.mockito.Mockito.verify(schemas, org.mockito.Mockito.never()).heldFingerprintView();
        // The AI seam is asked nothing either: with the switch off there is no document to place
        // it in, and asking would consult an adapter for a column no probe will ever read.
        org.mockito.Mockito.verify(aiExtraction, org.mockito.Mockito.never()).aiBehaviorIdentity();
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void canonicalFingerprintIsStableAcrossRepoOrder() {
        String inOrder = service("release-1").fingerprint().orElseThrow();

        List<RulePackLoader.PackFingerprint> reversedPacks = new ArrayList<>(defaultPacks());
        java.util.Collections.reverse(reversedPacks);
        List<ExtractionSchemaLoader.SchemaFingerprint> reversedSchemas =
                new ArrayList<>(defaultSchemas());
        java.util.Collections.reverse(reversedSchemas);
        when(packs.fingerprintViewForCurrentOrg()).thenReturn(reversedPacks);
        when(schemas.fingerprintViewForCurrentOrg()).thenReturn(reversedSchemas);

        assertThat(service("release-1").fingerprint().orElseThrow()).isEqualTo(inOrder);
    }

    @Test
    void definitionKeyOrderDoesNotChangeFingerprint() {
        when(packs.fingerprintViewForCurrentOrg())
                .thenReturn(
                        List.of(
                                new RulePackLoader.PackFingerprint(
                                        "PAYSTUB",
                                        "1.2.0",
                                        new BigDecimal("0.70"),
                                        "{\"targetScore\":6,\"anchors\":[]}")));
        when(schemas.fingerprintViewForCurrentOrg()).thenReturn(List.of());
        String baseline = service("release-1").fingerprint().orElseThrow();

        // Semantically identical jsonb definition, different key insertion order — the exact
        // hazard of trusting definition::text. C14N owns ordering, so the fingerprint agrees.
        when(packs.fingerprintViewForCurrentOrg())
                .thenReturn(
                        List.of(
                                new RulePackLoader.PackFingerprint(
                                        "PAYSTUB",
                                        "1.2.0",
                                        new BigDecimal("0.70"),
                                        "{\"anchors\":[],\"targetScore\":6}")));
        assertThat(service("release-1").fingerprint().orElseThrow()).isEqualTo(baseline);
    }

    @Test
    void eachBehaviorInputChangesFingerprint() {
        String baseline = service("release-1").fingerprint().orElseThrow();
        List<String> variants = new ArrayList<>();

        // Pack version bump.
        when(packs.fingerprintViewForCurrentOrg())
                .thenReturn(
                        List.of(
                                new RulePackLoader.PackFingerprint(
                                        "PAYSTUB",
                                        "1.3.0",
                                        new BigDecimal("0.70"),
                                        defaultPacks().get(0).definition()),
                                defaultPacks().get(1)));
        variants.add(service("release-1").fingerprint().orElseThrow());

        // minConfidence change.
        when(packs.fingerprintViewForCurrentOrg())
                .thenReturn(
                        List.of(
                                new RulePackLoader.PackFingerprint(
                                        "PAYSTUB",
                                        "1.2.0",
                                        new BigDecimal("0.80"),
                                        defaultPacks().get(0).definition()),
                                defaultPacks().get(1)));
        variants.add(service("release-1").fingerprint().orElseThrow());

        // Pack definition change (splitting behavior lives inside definitions).
        when(packs.fingerprintViewForCurrentOrg())
                .thenReturn(
                        List.of(
                                new RulePackLoader.PackFingerprint(
                                        "PAYSTUB",
                                        "1.2.0",
                                        new BigDecimal("0.70"),
                                        "{\"targetScore\":6,\"anchors\":[{\"id\":\"a\","
                                                + "\"weight\":2,\"startsDocument\":true}]}"),
                                defaultPacks().get(1)));
        variants.add(service("release-1").fingerprint().orElseThrow());
        when(packs.fingerprintViewForCurrentOrg()).thenReturn(defaultPacks());

        // Schema version bump.
        when(schemas.fingerprintViewForCurrentOrg())
                .thenReturn(
                        List.of(
                                new ExtractionSchemaLoader.SchemaFingerprint(
                                        "PAYSTUB",
                                        "2.1.0",
                                        defaultSchemas().get(0).definition()),
                                defaultSchemas().get(1)));
        variants.add(service("release-1").fingerprint().orElseThrow());
        when(schemas.fingerprintViewForCurrentOrg()).thenReturn(defaultSchemas());

        // One worker library version.
        when(worker.version())
                .thenReturn(
                        Optional.of(
                                parse(
                                                WORKER_VERSION_BODY.replace(
                                                        "0.11.10", "0.11.11"))));
        variants.add(service("release-1").fingerprint().orElseThrow());
        when(worker.version())
                .thenReturn(
                        Optional.of(
                                parse(WORKER_VERSION_BODY)));

        // Engine release.
        variants.add(service("release-2").fingerprint().orElseThrow());

        // The parser adapter: WHICH code path executes the stages is behavior too.
        when(adapter.behaviorIdentity()).thenReturn(Optional.of("some-other-adapter"));
        variants.add(service("release-1").fingerprint().orElseThrow());
        when(adapter.behaviorIdentity()).thenReturn(Optional.of("worker"));

        // Turning the AI seam ON. The stage writes extracted_field rows and a reconciliation
        // status into the result reuse serves, so an off-parse must never satisfy an on-request.
        when(aiExtraction.aiBehaviorIdentity()).thenReturn(Optional.of(enrichingAi()));
        variants.add(service("release-1").fingerprint().orElseThrow());

        // The MODEL, with every other input untouched. This is the case no jar hash can catch:
        // same build, same packs, same schemas, same worker — a different model answering.
        Map<String, String> otherModel = enrichingAi();
        otherModel.put("firstPass", "vertex-gemini/gemini-2.5-pro");
        when(aiExtraction.aiBehaviorIdentity()).thenReturn(Optional.of(otherModel));
        variants.add(service("release-1").fingerprint().orElseThrow());

        // The PROMPT, edited without touching any version label. The dialect hash is content, so
        // it moves anyway — the reason it is a hash and not the prompt-version string.
        Map<String, String> editedPrompt = enrichingAi();
        editedPrompt.put("dialect.BANK_STATEMENT", "bbbb");
        when(aiExtraction.aiBehaviorIdentity()).thenReturn(Optional.of(editedPrompt));
        variants.add(service("release-1").fingerprint().orElseThrow());

        // A second pass that did not run before.
        Map<String, String> secondPass = enrichingAi();
        secondPass.put("secondPass", "vertex-gemini/gemini-2.5-pro");
        when(aiExtraction.aiBehaviorIdentity()).thenReturn(Optional.of(secondPass));
        variants.add(service("release-1").fingerprint().orElseThrow());

        // A budget that changes what the model is shown.
        Map<String, String> fewerPages = enrichingAi();
        fewerPages.put("maxPages", "40");
        when(aiExtraction.aiBehaviorIdentity()).thenReturn(Optional.of(fewerPages));
        variants.add(service("release-1").fingerprint().orElseThrow());

        // The W-2 gate flipped ON with every other input untouched — flipping
        // DOCENGINE_AI_W2_ENABLED must move the fingerprint on its own, or reuse would serve a
        // stale rules-only (or stale AI-enriched) package across the flip.
        Map<String, String> w2Flag = enrichingAi();
        w2Flag.put("w2Enabled", "true");
        when(aiExtraction.aiBehaviorIdentity()).thenReturn(Optional.of(w2Flag));
        variants.add(service("release-1").fingerprint().orElseThrow());

        // The paystub gate, same proof, for symmetry: flipping DOCENGINE_AI_PAYSTUB_ENABLED alone
        // must move the fingerprint too.
        Map<String, String> paystubFlag = enrichingAi();
        paystubFlag.put("paystubEnabled", "true");
        when(aiExtraction.aiBehaviorIdentity()).thenReturn(Optional.of(paystubFlag));
        variants.add(service("release-1").fingerprint().orElseThrow());

        when(aiExtraction.aiBehaviorIdentity())
                .thenReturn(Optional.of(Map.of("enabled", "false")));

        assertThat(variants).doesNotContain(baseline).doesNotHaveDuplicates();
    }

    /**
     * The AI seam's own refusal, and the reason it exists: {@code AiExtractionConfig} fails CLOSED
     * to the stub adapter on any incomplete provider configuration while {@code
     * docengine.ai.enabled} stays true and the configured model still reads exactly as intended.
     * Such a boot enriches nothing, and without this the parses it produces would carry full,
     * healthy-looking fingerprints — then be served back once the credential was fixed. Precisely
     * the hazard {@code ParserPort.behaviorIdentity} was added for, one seam over.
     */
    @Test
    void anAiSeamThatCannotIdentifyItselfDisablesReuse() {
        when(aiExtraction.aiBehaviorIdentity()).thenReturn(Optional.empty());
        assertThat(service("release-1").fingerprint()).isEmpty();
    }

    /**
     * Map iteration order must not reach the hash. {@code Map.copyOf} explicitly does not promise
     * an order, so two runs of one JVM can hand the composer the same entries in different orders;
     * DOCENGINE-C14N-1 sorts keys, which is what makes that safe.
     */
    @Test
    void aiEntryOrderDoesNotChangeFingerprint() {
        when(aiExtraction.aiBehaviorIdentity()).thenReturn(Optional.of(enrichingAi()));
        String sorted = service("release-1").fingerprint().orElseThrow();

        java.util.LinkedHashMap<String, String> reversed = new java.util.LinkedHashMap<>();
        List<String> keys = new ArrayList<>(enrichingAi().keySet());
        java.util.Collections.reverse(keys);
        keys.forEach(key -> reversed.put(key, enrichingAi().get(key)));
        when(aiExtraction.aiBehaviorIdentity()).thenReturn(Optional.of(reversed));

        assertThat(service("release-1").fingerprint().orElseThrow()).isEqualTo(sorted);
    }

    /**
     * The stub adapter's refusal, at the composer. A stub run still finalizes real engine_result
     * rows and still reaches HUMAN_REVIEW_REQUIRED, and the /version probe still answers from a
     * worker that took no part in it — so nothing downstream can tell the difference. The adapter
     * declining an identity is what makes the difference visible here.
     */
    @Test
    void anAdapterWithNoBehaviorIdentityDisablesReuse() {
        when(adapter.behaviorIdentity()).thenReturn(Optional.empty());
        assertThat(service("release-1").fingerprint()).isEmpty();
    }

    @Test
    void workerProbeFailureDisablesReuse() {
        when(worker.version()).thenReturn(Optional.empty());
        assertThat(service("release-1").fingerprint()).isEmpty();
    }

    @Test
    void missingEngineReleaseDisablesReuse() {
        assertThat(service("").fingerprint()).isEmpty();
        assertThat(service(null).fingerprint()).isEmpty();
    }

    @Test
    void unparseableDefinitionDisablesReuseInsteadOfGuessing() {
        when(packs.fingerprintViewForCurrentOrg())
                .thenReturn(
                        List.of(
                                new RulePackLoader.PackFingerprint(
                                        "PAYSTUB",
                                        "1.2.0",
                                        new BigDecimal("0.70"),
                                        "{\"broken\": }")));
        assertThat(service("release-1").fingerprint()).isEmpty();
    }

    /**
     * The closed input list. Runtime config (render batch size, timeouts, OCR budget) is EXCLUDED
     * with rationale: batching re-chunks per-page renders whose per-page output is invariant, and
     * timeouts change failure modes, not successful outputs — a timed-out parse never finalizes,
     * so it can never be a reuse source. RULE: any future config key that alters parse OUTPUT must
     * be added to the composer — and to this pinned list.
     */
    @Test
    void fingerprintDocumentPinsTheExactInputList() {
        ObjectNode document = service("release-1").composeDocument().orElseThrow();
        List<String> keys = new ArrayList<>();
        document.fieldNames().forEachRemaining(keys::add);
        assertThat(keys)
                .containsExactlyInAnyOrder(
                        "engineRelease",
                        "parserAdapter",
                        "worker",
                        "classificationPacks",
                        "extractionSchemas",
                        "aiExtraction");
        assertThat(document.get("worker").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("version", "libraries");
        // Switched off collapses to the one entry that fully describes the seam's contribution:
        // the stage returns skipped("AI_DISABLED") before reading a budget or an adapter, so
        // listing the inert knobs would invalidate every stored fingerprint in an AI-off
        // deployment the day someone edits a model name that nothing reads.
        assertThat(document.get("aiExtraction").fieldNames())
                .toIterable()
                .containsExactly("enabled");

        when(aiExtraction.aiBehaviorIdentity()).thenReturn(Optional.of(enrichingAi()));
        ObjectNode enriching =
                service("release-1").composeDocument().orElseThrow().withObject("/aiExtraction");
        List<String> aiKeys = new ArrayList<>();
        enriching.fieldNames().forEachRemaining(aiKeys::add);
        assertThat(aiKeys).containsExactlyInAnyOrderElementsOf(enrichingAi().keySet());
    }
}
