package com.pragmaticds.docengine.reuse;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pragmaticds.docengine.ai.AiBehaviorIdentity;
import com.pragmaticds.docengine.classification.rules.RulePackLoader;
import com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader;
import com.pragmaticds.docengine.orchestration.BehaviorFingerprintPort;
import com.pragmaticds.docengine.orchestration.ParserPort;
import com.pragmaticds.docengine.platform.behavior.BehaviorViewScope;
import com.pragmaticds.docengine.platform.behavior.HeldBehaviorView;
import com.pragmaticds.docengine.results.canonical.CanonicalJsonWriter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * The parse-once behavior fingerprint: SHA-256 over the DOCENGINE-C14N-1 bytes of
 * {@code {engineRelease, parserAdapter, worker, classificationPacks, extractionSchemas,
 * aiExtraction}} — everything that decides what a parse of given bytes PRODUCES.
 *
 * <p>It is computed at two moments, and the difference between them is the design:
 *
 * <ul>
 *   <li>{@link #fingerprint()} — PROSPECTIVELY, at upload, to match candidates. It is a
 *       PREDICTION of what a parse started now would execute under. A wrong prediction costs a
 *       reuse hit and nothing else, because it is never stored.
 *   <li>{@link #fingerprintForCompletedRun} — at FINALIZING, to STAMP. It is a claim about a parse
 *       that has already happened, so it is composed only from what the run recorded as it ran,
 *       and refuses to answer at all when anything cannot be accounted for.
 * </ul>
 *
 * <p>Both compose the identical document, so equality between a stamp and a prospective value
 * means what it says. The closed input list (pinned by test):
 *
 * <ul>
 *   <li>{@code engineRelease} — Java behavior (rungs, normalizers, splitter, classifier, in-code
 *       constants). From {@code docengine.reuse.engine-release} when set (an operator contract for
 *       stable reuse across provably identical rebuilds), else Spring Boot build-info
 *       (version+time: every image rebuild changes the fingerprint — over-trigger, the correct
 *       direction). Neither available ⇒ NO fingerprint ⇒ reuse disabled, parse normally.
 *   <li>{@code parserAdapter} — WHICH adapter executes the stages. The stub declines to have an
 *       identity, so a stub parse has no fingerprint at all: not stamped, not reused.
 *   <li>{@code worker} — the live {@code /version} identity {@code {version, libraries}}; OCR
 *       engines are in the library list. Probe failure ⇒ no fingerprint.
 *   <li>{@code classificationPacks} — the org's post-shadowing winning pack per active type:
 *       {@code {documentTypeCode, version, minConfidence, definition}}; splitting behavior
 *       ({@code startsDocument}) lives inside definitions, and type retirement drops packs out.
 *   <li>{@code extractionSchemas} — same shape per winning schema.
 *   <li>{@code aiExtraction} — the AI enrichment seam: its gates, budgets, the {@code
 *       provider/model} the ADAPTER reports, and a content hash of each dialect's prompt. The AI
 *       stage writes {@code extracted_field} rows and a reconciliation status into the very result
 *       reuse serves, so it decides parse output as plainly as a schema does. It composes itself —
 *       see {@link com.pragmaticds.docengine.ai.AiBehaviorIdentity} — and declining to answer withholds
 *       the fingerprint entirely.
 * </ul>
 *
 * <p><b>Why the AI seam is not a {@code BehaviorViewScope.ViewKind}.</b> That machinery exists for
 * per-org views served from a CACHE, which can differ from the database and can be replaced between
 * one stage and the next — hence the record-as-served token and the held-view guard. The AI seam has
 * no such view: its dialects are static finals built at class load from classpath resources, and its
 * gates and budgets are {@code @Value} constants injected at startup. Nothing about it can change
 * while a JVM runs, so there is no mid-run replacement to straddle and nothing a recorded token
 * could prove that reading it at composition time does not. It belongs with {@code engineRelease},
 * {@code parserAdapter} and {@code worker}, which are read the same way for the same reason.
 *
 * <p>EXCLUDED with rationale: envelope constants (they version the serialization of results, not
 * parse behavior — a reused package serves its own honestly-versioned envelope) and runtime config
 * (render batching re-chunks invariant per-page output; timeouts change failure modes, and a
 * timed-out parse never finalizes, so it can never be a reuse source). RULE: any future config key
 * that alters parse OUTPUT must be added here and to the pinned input-list test.
 *
 * <p>Definitions are STRICT-parsed from the entity String and embedded as trees, so C14N owns key
 * order — jsonb reorders keys and {@code definition::text} is never trusted. Any failure anywhere
 * yields {@link Optional#empty()}: no fingerprint, no reuse, normal parse.
 */
@Service
public class ReuseFingerprintService implements BehaviorFingerprintPort {

    private static final Logger log = LoggerFactory.getLogger(ReuseFingerprintService.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper STRICT_MAPPER =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final boolean enabled;
    private final String engineReleaseOverride;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final WorkerVersionProbe worker;
    private final ParserPort adapter;
    private final RulePackLoader packs;
    private final ExtractionSchemaLoader schemas;
    private final AiBehaviorIdentity aiExtraction;

    public ReuseFingerprintService(
            @Value("${docengine.reuse.enabled:true}") boolean enabled,
            @Value("${docengine.reuse.engine-release:}") String engineReleaseOverride,
            ObjectProvider<BuildProperties> buildProperties,
            WorkerVersionProbe worker,
            ParserPort adapter,
            RulePackLoader packs,
            ExtractionSchemaLoader schemas,
            AiBehaviorIdentity aiExtraction) {
        this.enabled = enabled;
        this.engineReleaseOverride = engineReleaseOverride;
        this.buildProperties = buildProperties;
        this.worker = worker;
        this.adapter = adapter;
        this.packs = packs;
        this.schemas = schemas;
        this.aiExtraction = aiExtraction;
    }

    /** The current org's prospective behavior fingerprint, or empty when it cannot be honest. */
    public Optional<String> fingerprint() {
        return composeDocument().map(document -> new CanonicalJsonWriter().write(document).sha256());
    }

    /**
     * The fingerprint describing what the run on THIS thread actually executed under, or empty ⇒
     * the parse must be left unstamped and therefore permanently non-reusable.
     *
     * <p>Three proof obligations, beyond everything {@link #fingerprint()} already requires. Each
     * one exists because failing it means the composed document would describe a behavior that is
     * not the behavior that ran:
     *
     * <ol>
     *   <li>the run RECORDED a view for each loader — a loader never consulted cannot be vouched
     *       for, and recording two different views means the run straddled a cache replacement;
     *   <li>each recorded token still matches the loader's currently-HELD snapshot, read purely
     *       (never through a path that would mint, refresh, or record) — otherwise the view the
     *       composer is about to read is not the view the run used; and
     *   <li>every worker version the stage rows recorded equals the version the probe reports —
     *       otherwise the run straddled a worker upgrade.
     * </ol>
     */
    @Override
    public Optional<String> fingerprintForCompletedRun(Set<String> workerVersionsUsed) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            // ONE pure read of each loader's held snapshot: its identity token AND the view that
            // token names, together. heldFingerprintView() must be the PURE accessor, never the
            // minting/recording serving path — a recording accessor would fill the recorded slot the
            // check is about to read, comparing it against itself (trivially true, even for a loader
            // this run never consulted).
            Optional<HeldBehaviorView<RulePackLoader.PackFingerprint>> heldPacks =
                    packs.heldFingerprintView();
            Optional<HeldBehaviorView<ExtractionSchemaLoader.SchemaFingerprint>> heldSchemas =
                    schemas.heldFingerprintView();

            // The token each held snapshot carries is validated against what the run recorded. A
            // pass here proves BOTH held snapshots are present (executedUnderCurrentView returns
            // false on an empty held token), so the stamp below can compose from them.
            if (!executedUnderCurrentView(
                            BehaviorViewScope.ViewKind.CLASSIFICATION_PACKS,
                            heldPacks.map(HeldBehaviorView::token))
                    || !executedUnderCurrentView(
                            BehaviorViewScope.ViewKind.EXTRACTION_SCHEMAS,
                            heldSchemas.map(HeldBehaviorView::token))) {
                return Optional.empty();
            }

            // Compose the stamp from EXACTLY the views whose tokens the guard just validated — never
            // a re-read. The bytes stamped are the bytes of the view the run is proven to have
            // executed under, closing the guard→compose window.
            Optional<ObjectNode> document =
                    compose(heldPacks.orElseThrow().view(), heldSchemas.orElseThrow().view());
            if (document.isEmpty()) {
                return Optional.empty();
            }
            String probedWorkerVersion = document.get().get("worker").get("version").asText();
            for (String used : workerVersionsUsed) {
                if (used != null && !used.equals(probedWorkerVersion)) {
                    log.info(
                            "behavior fingerprint withheld: the run straddles a worker version"
                                    + " change, so no single fingerprint describes it");
                    return Optional.empty();
                }
            }
            return Optional.of(new CanonicalJsonWriter().write(document.get()).sha256());
        } catch (RuntimeException unavailable) {
            log.debug(
                    "executed behavior fingerprint unavailable exception={}",
                    unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Whether the run recorded, for {@code kind}, a view that is STILL the one held now. The two
     * sides are produced independently and that independence is the whole guard: {@code recorded}
     * is what the loaders stamped into the scope AS THEY SERVED this run, and {@code heldToken} is a
     * pure read of the snapshot held at this instant. {@code heldToken} must never be obtained
     * through a path that mints, refreshes, or records — do that and this compares the recorded slot
     * against a token the read itself just wrote there, so a loader the run never consulted passes.
     */
    private static boolean executedUnderCurrentView(
            BehaviorViewScope.ViewKind kind, Optional<String> heldToken) {
        Optional<String> recorded = BehaviorViewScope.recorded(kind);
        if (recorded.isEmpty()) {
            log.info("behavior fingerprint withheld: this run never pinned a {} view", kind);
            return false;
        }
        if (heldToken.isEmpty()) {
            log.info(
                    "behavior fingerprint withheld: the {} view the run pinned is no longer held,"
                            + " so it cannot be confirmed as still current",
                    kind);
            return false;
        }
        if (!recorded.get().equals(heldToken.get())) {
            log.info(
                    "behavior fingerprint withheld: the {} view changed between the parse and its"
                            + " finalization",
                    kind);
            return false;
        }
        return true;
    }

    /**
     * The PROSPECTIVE composed (pre-canonicalization) fingerprint document — over the CURRENT
     * (serving) views — package-visible for the pin test and used by {@link #fingerprint()}.
     *
     * <p>Reading the current views through {@code fingerprintViewForCurrentOrg()} is correct HERE
     * and only here: this is a prediction with no run to describe, so minting-and-recording the
     * present view is exactly right. The completed-run stamp must NOT come this way — it composes
     * from the guard-validated HELD views instead (see {@link #fingerprintForCompletedRun}).
     */
    Optional<ObjectNode> composeDocument() {
        if (!enabled) {
            // The master switch makes the whole feature INERT, not merely non-serving. Composing
            // anyway would still probe the worker over HTTP and still write a column nothing may
            // read — side effects for a feature the operator turned off. (It also desynchronised
            // every queue-driven mock-worker IT, which is how the omission was caught.)
            return Optional.empty();
        }
        try {
            return compose(
                    packs.fingerprintViewForCurrentOrg(), schemas.fingerprintViewForCurrentOrg());
        } catch (RuntimeException unavailable) {
            // Class name only — a definition body or response fragment must never reach a log.
            log.debug(
                    "behavior fingerprint unavailable exception={}",
                    unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Builds the fingerprint document from the GIVEN pack and schema views — the ONE composition the
     * prospective fingerprint (current views) and the completed-run stamp (the guard-validated held
     * views) share, so a stamp and a prediction over identical views are byte-identical. Callers own
     * the {@code enabled} gate and the fail-open {@code catch}; this method only assembles.
     */
    private Optional<ObjectNode> compose(
            List<RulePackLoader.PackFingerprint> packView,
            List<ExtractionSchemaLoader.SchemaFingerprint> schemaView) {
        Optional<String> engineRelease = engineRelease();
        if (engineRelease.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> parserAdapter = adapter.behaviorIdentity();
        if (parserAdapter.isEmpty()) {
            return Optional.empty();
        }
        Optional<ObjectNode> workerIdentity = workerIdentity();
        if (workerIdentity.isEmpty()) {
            return Optional.empty();
        }
        Optional<java.util.Map<String, String>> aiIdentity = aiExtraction.aiBehaviorIdentity();
        if (aiIdentity.isEmpty()) {
            return Optional.empty();
        }

        ObjectNode document = JSON.objectNode();
        document.put("engineRelease", engineRelease.get());
        document.put("parserAdapter", parserAdapter.get());
        document.set("worker", workerIdentity.get());
        document.set("classificationPacks", classificationPacks(packView));
        document.set("extractionSchemas", extractionSchemas(schemaView));
        // Entries go in as they come; DOCENGINE-C14N-1 sorts keys, so map iteration order — which
        // Map.copyOf explicitly does not promise — can never move the hash.
        ObjectNode ai = JSON.objectNode();
        aiIdentity.get().forEach(ai::put);
        document.set("aiExtraction", ai);
        return Optional.of(document);
    }

    private Optional<String> engineRelease() {
        if (engineReleaseOverride != null && !engineReleaseOverride.isBlank()) {
            return Optional.of(engineReleaseOverride.trim());
        }
        BuildProperties properties = buildProperties.getIfAvailable();
        if (properties == null) {
            return Optional.empty();
        }
        String version = properties.getVersion();
        java.time.Instant time = properties.getTime();
        if (version == null && time == null) {
            return Optional.empty();
        }
        return Optional.of((version == null ? "unversioned" : version)
                + "@"
                + (time == null ? "untimed" : time.toString()));
    }

    private Optional<ObjectNode> workerIdentity() {
        return worker.version()
                .filter(body -> body.hasNonNull("worker") && body.has("libraries"))
                .map(
                        body -> {
                            ObjectNode identity = JSON.objectNode();
                            identity.put("version", body.get("worker").asText());
                            identity.set("libraries", body.get("libraries").deepCopy());
                            return identity;
                        });
    }

    private ArrayNode classificationPacks(List<RulePackLoader.PackFingerprint> view) {
        ArrayNode array = JSON.arrayNode();
        view.stream()
                .sorted(
                        java.util.Comparator.comparing(
                                RulePackLoader.PackFingerprint::documentTypeCode))
                .forEach(
                        pack -> {
                            ObjectNode node = array.addObject();
                            node.put("documentTypeCode", pack.documentTypeCode());
                            node.put("version", pack.version());
                            node.put("minConfidence", pack.minConfidence());
                            node.set("definition", parseDefinition(pack.definition()));
                        });
        return array;
    }

    private ArrayNode extractionSchemas(List<ExtractionSchemaLoader.SchemaFingerprint> view) {
        ArrayNode array = JSON.arrayNode();
        view.stream()
                .sorted(
                        java.util.Comparator.comparing(
                                ExtractionSchemaLoader.SchemaFingerprint::documentTypeCode))
                .forEach(
                        schema -> {
                            ObjectNode node = array.addObject();
                            node.put("documentTypeCode", schema.documentTypeCode());
                            node.put("version", schema.version());
                            node.set("definition", parseDefinition(schema.definition()));
                        });
        return array;
    }

    private static JsonNode parseDefinition(String definition) {
        try {
            return STRICT_MAPPER.readTree(definition);
        } catch (java.io.IOException notStrictJson) {
            throw new IllegalStateException("definition is not strict JSON", notStrictJson);
        }
    }
}
