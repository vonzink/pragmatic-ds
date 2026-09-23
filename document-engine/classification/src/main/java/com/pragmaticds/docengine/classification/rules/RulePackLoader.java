package com.pragmaticds.docengine.classification.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.classification.domain.ClassificationRulePack;
import com.pragmaticds.docengine.classification.domain.DocumentType;
import com.pragmaticds.docengine.classification.repo.ClassificationRulePackRepository;
import com.pragmaticds.docengine.classification.repo.DocumentTypeRepository;
import com.pragmaticds.docengine.platform.behavior.BehaviorViewScope;
import com.pragmaticds.docengine.platform.behavior.HeldBehaviorView;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Loads and parses the rule packs that APPLY to the current org, from the rows the org may SEE
 * (own + global built-ins).
 *
 * <p><b>The shadowing rule:</b> for each {@code document_type_code}, if the org has any active
 * pack of that type, the org's packs hide EVERY global pack of that type — a tenant's private
 * mortgage pack replaces the shipped generic one wholesale, it never merges with it. Within the
 * surviving scope, the highest version wins (numeric segment-wise semver compare, so 1.10.0 beats
 * 1.2.0). Packs whose document type is not active-and-visible are skipped: retiring a type
 * retires its packs without touching them.
 *
 * <p>Parsed packs cache per org as ONE snapshot that also carries the pack view the parse-once
 * behavior fingerprint describes, so the description and the behavior can never drift apart;
 * {@link #invalidate} is the hook for pack administration (Spec 2's pack-upload endpoint) and for
 * tests that mutate pack rows underneath a warm cache. Note that invalidating mid-run is SAFE for
 * reuse, not merely tolerated: the run's recorded token stops matching and the parse is left
 * unstamped rather than described wrongly.
 *
 * <p>Failures: no applicable pack at all → {@link ErrorCode#NO_RULE_PACK}; an unparseable
 * definition → {@link ErrorCode#INTERNAL} carrying the pack ID ONLY — the definition body never
 * reaches a log line or an error param.
 */
@Service
public class RulePackLoader {

    private static final Logger log = LoggerFactory.getLogger(RulePackLoader.class);

    private final ClassificationRulePackRepository packs;
    private final DocumentTypeRepository types;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();

    /**
     * ONE cached load: the parsed packs the classifier EXECUTES against and the fingerprint view
     * that DESCRIBES them, minted together from a single read, under one identity token.
     *
     * <p>They used to be separate — {@code activePacksForCurrentOrg} served the cache while the
     * fingerprint re-read the database — and that gap is exactly what let one fingerprint describe
     * two different outputs: a pack row inserted underneath a warm cache changed the description
     * without changing the behavior. Bound into one object they cannot disagree, and the token
     * lets a completed run prove at stamping time that the view it ran under is still the view in
     * hand.
     */
    private record Snapshot(String token, List<RulePack> packs, List<PackFingerprint> view) {}

    public RulePackLoader(ClassificationRulePackRepository packs, DocumentTypeRepository types) {
        this.packs = packs;
        this.types = types;
    }

    public List<RulePack> activePacksForCurrentOrg() {
        List<RulePack> loaded = snapshotForCurrentOrg().packs();
        if (loaded.isEmpty()) {
            throw new DomainException(ErrorCode.NO_RULE_PACK, 500);
        }
        return loaded;
    }

    /** Invalidation hook: pack rows changed for this org (or a global change — clear each org). */
    public void invalidate(UUID orgId) {
        cache.remove(orgId);
    }

    /** Global built-ins changed: every org's view is stale. */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * The winning (post-shadowing, post-active-type-filter) pack row per document type — the org's
     * effective classification view, verbatim, for the parse-once behavior fingerprint. Sorted by
     * type code; the definition travels as the raw column String and is canonicalized by the
     * fingerprint composer (C14N owns key order — {@code definition::text} is never trusted).
     *
     * <p>Read from the SAME cached snapshot the classifier executes against, never freshly from
     * the database. A fingerprint's whole job is to describe engine behavior, and the engine's
     * behavior is the cache.
     */
    public List<PackFingerprint> fingerprintViewForCurrentOrg() {
        return snapshotForCurrentOrg().view();
    }

    /**
     * The snapshot CURRENTLY HELD for this org — its identity token AND the fingerprint view that
     * token names, read together as one thing — or empty when none is held. A completed run
     * compares this token against what it recorded before anything may be stamped, AND composes the
     * stamp from this same view; an empty answer (or a token mismatch) means the cache was replaced
     * since the run consulted it, so nothing may vouch for the run's behavior.
     *
     * <p>This is a PURE read: it never mints, refreshes, or records. That purity is the guard. Its
     * predecessor routed through {@link #snapshotForCurrentOrg()}, which mints-on-demand AND
     * records; used as the "expected" side of the stamp check it made the check compare the
     * recorded slot against a token that reading it had just written there ({@code putIfAbsent}
     * filling an empty slot), so a loader the run never consulted compared equal to itself and an
     * unpinned parse was stamped anyway. Read the held snapshot WITHOUT going through the serving
     * path and the two sides of the comparison are produced independently.
     *
     * <p>The token and the view come back in ONE object from ONE read so the finalizer never reads
     * the view a second time: validating the token and composing the stamp both use this snapshot,
     * closing the window in which a re-read could compose a view the guard never validated.
     */
    public Optional<HeldBehaviorView<PackFingerprint>> heldFingerprintView() {
        Snapshot held = cache.get(TenantContext.require());
        return held == null
                ? Optional.empty()
                : Optional.of(new HeldBehaviorView<>(held.token(), held.view()));
    }

    private Snapshot snapshotForCurrentOrg() {
        UUID orgId = TenantContext.require();
        Snapshot snapshot = cache.computeIfAbsent(orgId, this::load);
        if (snapshot.packs().isEmpty()) {
            // "This org has no packs" is never cached: an operator's FIRST pack has always taken
            // effect without a restart, and turning that into a restart-only change would be a
            // silent operability regression hiding inside a caching refactor.
            cache.remove(orgId, snapshot);
        }
        BehaviorViewScope.record(BehaviorViewScope.ViewKind.CLASSIFICATION_PACKS, snapshot.token());
        return snapshot;
    }

    /** One winning pack row's behavior-relevant identity, for the reuse fingerprint. */
    public record PackFingerprint(
            String documentTypeCode,
            String version,
            java.math.BigDecimal minConfidence,
            String definition) {}

    private List<ClassificationRulePack> winningRows(UUID orgId) {
        Set<String> activeTypes =
                types.findActiveVisibleTo(orgId).stream()
                        .map(DocumentType::getCode)
                        .collect(Collectors.toSet());

        List<ClassificationRulePack> visible =
                packs.findActiveVisibleTo(orgId).stream()
                        .filter(pack -> activeTypes.contains(pack.getDocumentTypeCode()))
                        .toList();

        // Shadowing: org scope wins the whole type when it has any pack for it.
        Map<String, List<ClassificationRulePack>> byType = new HashMap<>();
        for (ClassificationRulePack pack : visible) {
            byType.computeIfAbsent(pack.getDocumentTypeCode(), key -> new ArrayList<>()).add(pack);
        }

        List<ClassificationRulePack> winners = new ArrayList<>();
        for (List<ClassificationRulePack> candidates : byType.values()) {
            boolean orgHasPack = candidates.stream().anyMatch(pack -> pack.getOrgId() != null);
            winners.add(
                    candidates.stream()
                            .filter(pack -> (pack.getOrgId() != null) == orgHasPack)
                            .max(RulePackLoader::compareVersions)
                            .orElseThrow());
        }
        return winners;
    }

    private Snapshot load(UUID orgId) {
        List<ClassificationRulePack> winners = winningRows(orgId);

        List<RulePack> loaded = new ArrayList<>();
        for (ClassificationRulePack winner : winners) {
            loaded.add(parse(winner));
        }
        loaded.sort(java.util.Comparator.comparing(RulePack::documentTypeCode));

        List<PackFingerprint> view =
                winners.stream()
                        .map(
                                row ->
                                        new PackFingerprint(
                                                row.getDocumentTypeCode(),
                                                row.getVersion(),
                                                row.getMinConfidence(),
                                                row.getDefinition()))
                        .sorted(java.util.Comparator.comparing(PackFingerprint::documentTypeCode))
                        .toList();

        return new Snapshot(UUID.randomUUID().toString(), List.copyOf(loaded), view);
    }

    private RulePack parse(ClassificationRulePack row) {
        try {
            JsonNode definition = mapper.readTree(row.getDefinition());
            double targetScore = definition.path("targetScore").asDouble();
            if (targetScore <= 0) {
                throw new IllegalArgumentException("targetScore must be positive");
            }
            List<Anchor> anchors = new ArrayList<>();
            for (JsonNode anchor : definition.path("anchors")) {
                anchors.add(
                        new Anchor(
                                anchor.path("id").asText(),
                                AnchorKind.fromWire(anchor.path("kind").asText()),
                                anchor.path("pattern").asText(),
                                anchor.path("weight").asDouble(),
                                // Explicit, and defaulted here rather than left to the JSON:
                                // path() returns MissingNode for an absent key and nothing in
                                // this method validates a schema, so an unread key would be a
                                // silent no-op in a seeded pack (Spec 5a, T6).
                                anchor.path("startsDocument").asBoolean(false)));
            }
            if (anchors.isEmpty()) {
                throw new IllegalArgumentException("pack has no anchors");
            }
            // Optional since Phase D; hasNonNull, not path().asInt(), because an absent key must
            // stay null ("declares nothing") rather than becoming a zero-page maximum.
            Integer plausiblePageMax = null;
            if (definition.hasNonNull("plausiblePageMax")) {
                plausiblePageMax = definition.get("plausiblePageMax").asInt();
                if (plausiblePageMax < 1) {
                    throw new IllegalArgumentException("plausiblePageMax must be positive");
                }
            }
            return new RulePack(
                    row.getDocumentTypeCode(),
                    row.getVersion(),
                    row.getMinConfidence().doubleValue(),
                    targetScore,
                    List.copyOf(anchors),
                    plausiblePageMax);
        } catch (Exception e) {
            // Pack id only: the definition body may be arbitrarily broken junk and is
            // operator-supplied data — it never reaches a log line or an error param.
            log.error("unparseable rule pack id={}", row.getId());
            throw new DomainException(
                    ErrorCode.INTERNAL, 500, Map.of("rulePackId", String.valueOf(row.getId())));
        }
    }

    /** Segment-wise numeric semver compare; non-numeric segments fall back to string order. */
    private static int compareVersions(ClassificationRulePack a, ClassificationRulePack b) {
        String[] left = a.getVersion().split("\\.");
        String[] right = b.getVersion().split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            String ls = i < left.length ? left[i] : "0";
            String rs = i < right.length ? right[i] : "0";
            int compared;
            try {
                compared = Integer.compare(Integer.parseInt(ls), Integer.parseInt(rs));
            } catch (NumberFormatException e) {
                compared = ls.compareTo(rs);
            }
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }
}
