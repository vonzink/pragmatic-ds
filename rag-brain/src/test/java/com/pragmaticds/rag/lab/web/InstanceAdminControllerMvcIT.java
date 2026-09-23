package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.lab.domain.LabIdempotencyRecord;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.InstanceSnapshotService;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.instance.VerifiedInstanceReleaseReader;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.EncodedManifest;
import com.pragmaticds.rag.lab.release.InstanceManifestCodec;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.release.LabReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabIdempotencyRecordRepository;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Full application regression for the real admin filter and complete advice ordering chain. */
@SpringBootTest(properties = {
        "ragbrain.instances.enabled=true",
        "ragbrain.lab.enabled=false",
        "ragbrain.rag.admin.api-key=instance-mvc-integration-key"
})
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class InstanceAdminControllerMvcIT {
    private static final String ADMIN_KEY = "instance-mvc-integration-key";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired MockMvc mvc;
    @Autowired BrainRepository brains;
    @Autowired LabInstanceRepository instances;
    @Autowired LabInstancePointerRepository pointers;
    @Autowired LabIdempotencyRecordRepository receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired InstanceReleaseResolver resolver;
    @Autowired InstanceSnapshotService snapshots;
    @Autowired InstanceManifestCodec codec;
    @Autowired LabInstanceReleaseRepository releases;
    @Autowired VerifiedInstanceReleaseReader manifests;
    @PersistenceContext EntityManager entityManager;

    @Test
    void realAdminFilterRejectsMissingAndInvalidKeysBeforeTheController() throws Exception {
        String route = "/api/ai/admin/instances?brain=" + UUID.randomUUID();

        mvc.perform(get(route)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing or invalid admin API key"));
        mvc.perform(get(route).header("X-Admin-Api-Key", "wrong-key")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing or invalid admin API key"));
    }

    @Test
    void authenticatedMissingInstanceUsesTheScoped404Taxonomy() throws Exception {
        mvc.perform(get("/api/ai/admin/instances/missing?brain=" + UUID.randomUUID()).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INSTANCE_NOT_FOUND"));
    }

    @Test
    void verifiedV1SlugMismatchFailsClosedThroughTheRealAdviceChain() throws Exception {
        UUID brain = brain();
        String slug = "legacy-mismatch";
        seedLegacy(brain, slug, "other-slug");
        entityManager.clear();

        mvc.perform(get("/api/ai/admin/instances/" + slug + "?brain=" + brain).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_UNSUPPORTED"));
    }

    @Test
    void invalidIdempotencyCommandUsesTheScoped400Taxonomy() throws Exception {
        UUID brain = brain();
        String slug = "invalid-command";
        seedV2(brain, slug);

        mvc.perform(patch("/api/ai/admin/instances/" + slug + "?brain=" + brain)
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "k".repeat(201))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Updated\",\"purpose\":\"Updated purpose\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSTANCE_REQUEST_INVALID"));
    }

    @Test
    void reusedIdempotencyKeyUsesTheScoped409Taxonomy() throws Exception {
        UUID brain = brain();
        String slug = "key-reuse";
        seedV2(brain, slug);

        mvc.perform(patch("/api/ai/admin/instances/" + slug + "?brain=" + brain)
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"First\",\"purpose\":\"First purpose\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/ai/admin/instances/" + slug + "?brain=" + brain)
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Second\",\"purpose\":\"Second purpose\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void invalidStoredReceiptUsesTheSafe500Taxonomy() throws Exception {
        UUID brain = brain();
        String slug = "invalid-receipt";
        seedV2(brain, slug);
        String key = "bad-receipt";
        receipts.saveAndFlush(new LabIdempotencyRecord(brain, "instance.disable", key,
                sha256(canonical("POST", brain.toString(), slug, "disable")),
                "INSTANCE_COMMAND_RESULT", UUID.randomUUID(), 99L));

        mvc.perform(post("/api/ai/admin/instances/" + slug + "/disable?brain=" + brain)
                        .header("X-Admin-Api-Key", ADMIN_KEY).header("Idempotency-Key", key))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INSTANCE_REQUEST_FAILED"));
    }

    @Test
    void postgresqlJsonbRoundTripKeepsExactDecimalsAcrossResolverAndAllSnapshotDtos() throws Exception {
        UUID brain = brain();
        String slug = "decimal-v2";
        seedV2(brain, slug);
        entityManager.clear();

        ResolvedInstanceRelease live = resolver.live(new InstanceKey(brain, slug));
        InstanceReleaseManifest manifest = assertInstanceOf(DecodedInstanceManifest.V2.class, live.manifest()).manifest();
        assertDecimal("0.250", manifest.behavior().temperature());
        assertDecimal("1.50", manifest.limits().maximumExpectedCostUsd());
        assertDecimal("0.950", manifest.evaluations().minimumScore());
        assertEquals(1, snapshots.list(brain).size());
        assertEquals(slug, snapshots.detail(new InstanceKey(brain, slug)).instance().getSlug());

        mvc.perform(get("/api/ai/admin/instances?brain=" + brain).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].manifestVersion").value(2))
                .andExpect(jsonPath("$[0].provider").value("openai"))
                .andExpect(jsonPath("$[0].model").value("gpt-decimal"))
                .andExpect(jsonPath("$[0].collectionCount").value(1));
        mvc.perform(get("/api/ai/admin/instances/" + slug + "?brain=" + brain).header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("openai"))
                .andExpect(jsonPath("$.model").value("gpt-decimal"))
                .andExpect(jsonPath("$.collectionCount").value(1));
        mvc.perform(get("/api/ai/admin/instances/" + slug + "/releases?brain=" + brain)
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].manifestVersion").value(2))
                .andExpect(jsonPath("$[0].provider").value("openai"))
                .andExpect(jsonPath("$[0].model").value("gpt-decimal"))
                .andExpect(jsonPath("$[0].collectionCount").value(1));
    }

    /**
     * The entity's mapped manifest is not a read boundary, and the exact JSONB read is.
     *
     * <p>Hibernate deep-copies a JSON attribute through its format mapper on the way in and
     * deserializes through it on the way out, and that mapper returns a {@code BigDecimal} as a
     * {@code Double}. {@code 0.250} cannot be recovered from a {@code Double}, so a manifest
     * decoded from the entity map would no longer canonicalize to the digest stored beside it —
     * which is why the codec refuses a floating-point carrier instead of widening it, and why the
     * promotion gate and the evaluation service read through the verified reader rather than
     * through {@code release.getManifest()}. Both of them decoded the entity map until this test
     * existed, so neither could read back a release that had actually been stored.
     */
    @Test
    void theMappedManifestIsLossyAndOnlyTheExactJsonbReadDecodesARelease() {
        UUID brain = brain();
        String slug = "mapped-manifest";
        UUID releaseId = seedV2(brain, slug);
        entityManager.clear();

        LabInstanceRelease stored = releases
                .findByIdAndBrainIdAndInstanceSlug(releaseId, brain, slug).orElseThrow();

        // The hazard itself, named rather than assumed.
        Map<?, ?> behavior = assertInstanceOf(Map.class, stored.getManifest().get("behavior"));
        Object temperature = behavior.get("temperature");
        assertInstanceOf(Number.class, temperature);
        assertFalse(temperature instanceof BigDecimal,
                "a JSONB read does not hand back the temperature it was written with");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(stored.getManifest()),
                "and the codec refuses it rather than hashing a different contract");

        // The exact-bytes read is the one that works, and it keeps the scale the digest covers.
        InstanceReleaseManifest manifest = assertInstanceOf(DecodedInstanceManifest.V2.class,
                manifests.read(stored)).manifest();
        assertDecimal("0.250", manifest.behavior().temperature());
        assertDecimal("1.50", manifest.limits().maximumExpectedCostUsd());
        assertDecimal("0.950", manifest.evaluations().minimumScore());
        assertEquals(100L, manifest.limits().maximumInputTokens());
    }

    /**
     * The state every newly created instance is in: a candidate authored, nothing promoted.
     *
     * <p>Registration writes a release and no pointer, so this is the common case and not an
     * edge one. The whole administration surface has to work here — the instance read, its
     * release list, and the mutation round-trip that stores a body-free row and rebuilds the
     * response from it, on the first call and on the replay.
     */
    @Test
    void aNeverPromotedInstanceIsFullyAdministrableAndItsMutationReplaysCleanly() throws Exception {
        UUID brain = brain();
        String slug = "never-promoted";
        UUID candidateId = seedCandidateWithoutPointer(brain, slug);
        entityManager.clear();

        String detail = mvc.perform(get("/api/ai/admin/instances/" + slug + "?brain=" + brain)
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andExpect(jsonPath("$.hasCandidateRelease").value(true))
                .andReturn().getResponse().getContentAsString();
        assertTrue(detail.contains("\"liveRelease\":null"), detail);
        assertTrue(detail.contains("\"liveReleaseNumber\":null"), detail);

        mvc.perform(get("/api/ai/admin/instances/" + slug + "/releases?brain=" + brain)
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].releaseId").value(candidateId.toString()))
                .andExpect(jsonPath("$[0].provenance").value("CANDIDATE"))
                .andExpect(jsonPath("$[0].live").value(false));

        // Same key and same canonical request twice: the first call stores the row, the second
        // rebuilds the identical response from it. Neither may fail for want of a live release.
        String body = "{\"displayName\":\"Renamed\",\"purpose\":\"Renamed purpose\"}";
        String first = patchOnce(brain, slug, body);
        String replayed = patchOnce(brain, slug, body);
        assertTrue(first.contains("\"liveRelease\":null"), first);

        // The two bodies are compared with the timestamps taken out, and the timestamps are then
        // compared against the row itself. The first response carries the in-memory
        // OffsetDateTime at nanosecond precision while the replay is rebuilt from a Postgres
        // TIMESTAMPTZ, which keeps microseconds and ROUNDS to them — so the two genuinely differ
        // in the last digit, and no amount of string surgery on the responses is the right place
        // to say so. Splitting the claim in two says exactly what is true: a replay describes the
        // same instance state, and its timestamps are the ones that were stored. Every other
        // field, and a missing field, still has to match.
        assertEquals(bodyWithoutTimestamps(first), bodyWithoutTimestamps(replayed),
                first + " != " + replayed);

        Map<String, Object> row = jdbc.queryForMap(
                "select instance_created_at, instance_updated_at from lab_instance_command_result "
                        + "where brain_id = ? and instance_slug = ?", brain, slug);
        assertEquals(instant(row.get("instance_created_at")),
                instant(timestampField(replayed, "createdAt")),
                "the replay's createdAt is the one the row holds");
        assertEquals(instant(row.get("instance_updated_at")),
                instant(timestampField(replayed, "updatedAt")),
                "the replay's updatedAt is the one the row holds");
        // Exactly one stored row for this instance: the second call replayed it rather than
        // re-running the update, which is the path that dereferences the absent live release.
        Integer stored = jdbc.queryForObject(
                "select count(*) from lab_instance_command_result where brain_id = ? and instance_slug = ?",
                Integer.class, brain, slug);
        assertEquals(1, stored.intValue());
    }

    private String patchOnce(UUID brain, String slug, String body) throws Exception {
        return mvc.perform(patch("/api/ai/admin/instances/" + slug + "?brain=" + brain)
                        .header("X-Admin-Api-Key", ADMIN_KEY)
                        .header("Idempotency-Key", "unpromoted-patch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed"))
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andReturn().getResponse().getContentAsString();
    }

    /** A registered instance with an authored candidate and no pointer — what creation leaves. */
    private UUID seedCandidateWithoutPointer(UUID brain, String slug) {
        instances.saveAndFlush(new LabInstance(brain, slug, "Unpromoted instance", "Safe test fixture"));
        EncodedManifest encoded = codec.encode(v2());
        byte[] canonical = new LabManifestWriter().canonicalize(encoded.json());
        UUID id = UUID.randomUUID();
        jdbc.update("insert into lab_instance_release "
                        + "(id, brain_id, instance_slug, release_number, provenance_mode, manifest, manifest_sha256) "
                        + "values (?, ?, ?, 1, 'CANDIDATE', cast(? as jsonb), ?)",
                id, brain, slug, new String(canonical, StandardCharsets.UTF_8), encoded.sha256());
        return id;
    }

    private UUID brain() {
        return brains.saveAndFlush(new Brain(UUID.randomUUID(), "instance-mvc-" + UUID.randomUUID(), "Instance MVC")).getId();
    }

    /** Returns the release id, so a caller can read back the exact row that was written. */
    private UUID seedV2(UUID brain, String slug) {
        instances.saveAndFlush(new LabInstance(brain, slug, "Decimal instance", "Safe test fixture"));
        EncodedManifest encoded = codec.encode(v2());
        LabManifestWriter writer = new LabManifestWriter();
        byte[] canonical = writer.canonicalize(encoded.json());
        UUID releaseId = persistRelease(brain, slug, canonical, encoded.sha256());
        pointer(brain, slug, releaseId);
        return releaseId;
    }

    private void seedLegacy(UUID brain, String slug, String embeddedSlug) {
        instances.saveAndFlush(new LabInstance(brain, slug, "Legacy instance", "Safe test fixture"));
        LabManifestWriter writer = new LabManifestWriter();
        byte[] canonical = writer.canonicalize(legacy(embeddedSlug).toCanonicalMap());
        pointer(brain, slug, persistRelease(brain, slug, canonical, writer.sha256Hex(canonical)));
    }

    /** Insert exact canonical JSONB deliberately: Hibernate's untyped JSON map is not the read boundary under test. */
    private UUID persistRelease(UUID brain, String slug, byte[] canonical, String digest) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into lab_instance_release "
                        + "(id, brain_id, instance_slug, release_number, provenance_mode, manifest, manifest_sha256) "
                        + "values (?, ?, ?, 1, 'PRODUCTION', cast(? as jsonb), ?)",
                id, brain, slug, new String(canonical, StandardCharsets.UTF_8), digest);
        return id;
    }

    private void pointer(UUID brain, String slug, UUID releaseId) {
        LabInstancePointer pointer = new LabInstancePointer();
        pointer.setBrainId(brain);
        pointer.setInstanceSlug(slug);
        pointer.setProductionReleaseId(releaseId);
        pointers.saveAndFlush(pointer);
    }

    private static InstanceReleaseManifest v2() {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1", Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN, InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("openai", "gpt-decimal", InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of(new InstanceReleaseManifest.CollectionRef(UUID.randomUUID(), 1))),
                new InstanceReleaseManifest.BehaviorContract("system", "task", "query", new BigDecimal("0.250")),
                List.of(new InstanceReleaseManifest.ToolContract("income.calculate", "1", "a".repeat(64), "b".repeat(64))),
                new InstanceReleaseManifest.OutputContract("output", "c".repeat(64)),
                new InstanceReleaseManifest.LimitContract(100, 20, 20, 20, 1, new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract("golden", 1, new BigDecimal("0.950")));
    }

    private static LabReleaseManifest legacy(String slug) {
        return new LabReleaseManifest(LabReleaseManifest.MANIFEST_VERSION, LabManifestWriter.CANONICALIZATION_VERSION,
                slug, "income-v2", new LabReleaseManifest.Pinned(
                new LabReleaseManifest.Analyzer("Income", "v2", "base", "schema", "schema.json", "a".repeat(64)),
                new LabReleaseManifest.Retrieval("income", "income", 4),
                new LabReleaseManifest.EngineContract("application/json", List.of("1.0.0"), List.of("DOCENGINE-C14N-1"), List.of("PAYSTUB"), List.of("WARNING")),
                new LabReleaseManifest.Calculator(List.of("income.calculate"))),
                new LabReleaseManifest.ObservedInference(LabReleaseManifest.SelectionSource.ANALYZE_LANE, "openai", "gpt-test", null, 800, 1,
                        new LabReleaseManifest.ResolutionInputs(null, null, null, null, "openai", "gpt-test", null, null, "openai", "gpt-test", false)),
                new LabReleaseManifest.PrototypeLimitations(LabReleaseManifest.PROTOTYPE_LIMITATIONS_CODE, LabReleaseManifest.LIVE_DEPENDENCIES));
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(new BigDecimal(expected), actual);
        assertEquals(new BigDecimal(expected).scale(), actual.scale());
    }

    private static String canonical(String... fields) {
        StringBuilder value = new StringBuilder();
        for (String field : fields) value.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field);
        return value.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The response body with its two timestamp members removed, so the rest can be compared whole.
     *
     * <p>Both members must be present and non-null to be removed, so dropping one from the
     * contract fails here rather than quietly making the two bodies match.
     */
    private static ObjectNode bodyWithoutTimestamps(String json) {
        ObjectNode body = (ObjectNode) readTree(json);
        for (String field : List.of("createdAt", "updatedAt")) {
            assertTrue(body.hasNonNull(field), field + " must be present in " + json);
            body.remove(field);
        }
        return body;
    }

    /** One timestamp member, read as text. */
    private static String timestampField(String json, String field) {
        JsonNode value = readTree(json).get(field);
        assertTrue(value != null && value.isTextual(), field + " must be a timestamp in " + json);
        return value.textValue();
    }

    /** Compares instants, so a driver's own offset for a TIMESTAMPTZ is not the thing asserted. */
    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return OffsetDateTime.parse(String.valueOf(value)).toInstant();
    }

    private static JsonNode readTree(String json) {
        try {
            return JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new AssertionError("the response was not JSON: " + json, malformed);
        }
    }
}
