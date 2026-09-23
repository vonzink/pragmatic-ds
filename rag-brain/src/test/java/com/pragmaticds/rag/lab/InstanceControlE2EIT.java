package com.pragmaticds.rag.lab;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.lab.corpus.CorpusCollectionService;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.parsed.ParsedDataCompatibilityService;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.release.EncodedManifest;
import com.pragmaticds.rag.lab.release.InstanceManifestCodec;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.analyze.InstanceOutputSchemaRegistry;
import com.pragmaticds.rag.lab.release.LabManifestWriter;
import com.pragmaticds.rag.lab.instance.InstanceCandidateService;
import com.pragmaticds.rag.lab.instance.InstanceConstraintValidator.CreateInstanceCommand;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.repository.LabInstanceRepository;
import com.pragmaticds.rag.lab.run.RunGroupCommand;
import com.pragmaticds.rag.lab.run.RunGroupDispatcher;
import com.pragmaticds.rag.lab.run.RunGroupRecoveryService;
import com.pragmaticds.rag.lab.run.RunGroupService;
import com.pragmaticds.rag.lab.run.RunGroupStatusService;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.provider.AiModelProvider;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.repository.BrainRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The execution plane, live for the first time in any test: a real dispatcher claiming under the
 * real advisory lock, real leases, real terminal transitions, real reservation settlement, and a
 * real recovery sweep — against Testcontainers PostgreSQL with the startup validator's full
 * prerequisite set satisfied and only the Document Engine boundary mocked.
 *
 * <p>What this file proves deliberately stops short of a successful provider round-trip: the
 * failure legs are the ones that guard money and history. A member whose engine read fails at
 * re-verification records a value-free code and releases its reservation; a sibling keeps its own
 * outcome (one member never decides for another); a cancelled member never reaches a claim; an
 * expired lease becomes {@code INTERRUPTED} exactly once with no replay; a group over its brain's
 * budget is refused before anything exists; and every failure code written anywhere matches the
 * value-free vocabulary shape — never an exception message, never a URL.
 */
@SpringBootTest(properties = {
        "ragbrain.instances.enabled=true",
        "ragbrain.instances.execution.enabled=true",
        // These contexts mock the parse boundary, so no engine is ever called — but the
        // startup validator requires a declared auth mode before execution or the
        // connector may run, because a base URL alone never proved this deployment may
        // talk to the engine. Synthetic: nothing here authenticates to anything.
        "ragbrain.lab.engine.api-key=synthetic-engine-key-not-a-real-key",
        // The validator's execution prerequisites, satisfied on purpose — this is the first
        // context that boots with the dispatcher constructed.
        "ragbrain.instances.retention.terminal-run-days=30",
        "ragbrain.lab.payload-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "ragbrain.rag.admin.api-key=e2e-it-key",
        "ragbrain.instances.models[0].provider=synthetic",
        "ragbrain.instances.models[0].model=synthetic-analyzer",
        "ragbrain.instances.models[0].context-token-ceiling=100000",
        "ragbrain.instances.models[0].output-token-ceiling=8000",
        "ragbrain.instances.models[0].tokenizer=CONSERVATIVE_RANGE",
        "ragbrain.instances.models[0].input-usd-per-million=1.00",
        "ragbrain.instances.models[0].output-usd-per-million=5.00",
        // The poller stays quiet so the tests drive claims deterministically.
        "ragbrain.instances.execution.poll-interval=PT1H"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class InstanceControlE2EIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    /** Value-free vocabulary: uppercase words, never message text, URLs, or identifiers. */
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    private static final UUID PACKAGE =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID SOURCE =
            UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID REGISTRATION =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID ENGINE_JOB =
            UUID.fromString("77777777-7777-4777-8777-777777777777");

    @Autowired BrainRepository brains;
    @Autowired LabInstanceRepository instances;
    @Autowired LabInstancePointerRepository pointers;
    @Autowired LabInstanceReleaseRepository releaseRows;
    @Autowired InstanceCandidateService candidates;
    @Autowired CorpusCollectionService collections;
    @Autowired CorpusSnapshotService snapshots;
    @Autowired InstanceManifestCodec codec;
    @Autowired RunGroupService runGroups;
    @Autowired RunGroupStatusService status;
    @Autowired RunGroupDispatcher dispatcher;
    @Autowired RunGroupRecoveryService recovery;
    @Autowired JdbcTemplate jdbc;
    @Autowired InstanceOutputSchemaRegistry schemas;

    @MockBean ParsedDataResolver parsedInputs;

    @AfterEach
    void resetResponder() {
        RESPONDER.set(request -> {
            throw new IllegalStateException("synthetic provider refuses by default");
        });
    }

    /**
     * A switchable synthetic provider. The default refuses every call; the success test swaps in
     * a responder that returns a schema-valid envelope with reported usage. Swapping is the
     * test's own act — nothing configured, nothing hidden.
     */
    static final java.util.concurrent.atomic.AtomicReference<
            java.util.function.Function<AiRequest, AiResponse>> RESPONDER =
            new java.util.concurrent.atomic.AtomicReference<>(request -> {
                throw new IllegalStateException("synthetic provider refuses by default");
            });

    @TestConfiguration
    static class SyntheticProviderConfiguration {
        @Bean
        AiModelProvider syntheticProvider() {
            return new AiModelProvider() {
                @Override
                public AiResponse generate(AiRequest request) {
                    return RESPONDER.get().apply(request);
                }

                @Override
                public String getProviderName() {
                    return "synthetic";
                }

                @Override
                public String getModelName() {
                    return "synthetic-analyzer";
                }
            };
        }
    }

    // ============================================================ 0. authoring writes once

    /**
     * Authoring appends releases, and never rewrites the row it has just written.
     *
     * <p><b>This is a regression test for a defect no test could see.</b> {@code lab_instance_release}
     * is append-only at the database — V34 installs a {@code BEFORE UPDATE} trigger that raises
     * {@code LAB_ROW_IMMUTABLE} — but the entity did not say so, and the manifest is a mutable
     * {@code Map} behind a JSON type. Hibernate builds its dirty-check snapshot by round-tripping
     * that map through the format mapper, which returns a {@code BigDecimal} as a {@code Double}
     * and a {@code Long} as an {@code Integer}. The snapshot therefore never equalled the value it
     * was copied from, so the flush that inserted a release also scheduled an {@code UPDATE} of
     * every one of its columns, the trigger refused it, and <em>creating an instance failed with a
     * 500 on any real database</em>. Nothing caught it because every other test of this path is
     * either mocked or standalone MockMvc, and {@link #seed} writes its release row by hand with
     * JDBC — which never goes near the entity at all.
     *
     * <p>So the manifest below is the ordinary one, decimals and all, and the assertions are about
     * the rows: a create writes release 1, an edit appends release 2 pointing back at it, and
     * release 1 afterwards is the row the create wrote.
     */
    @Test
    void authoringAppendsReleasesAndNeverRewritesTheRowItJustWrote() {
        String slug = "append-only";
        UUID brainId = UUID.randomUUID();
        Brain brain = new Brain(brainId, slug + "-brain", "E2E " + slug);
        brain.setActive(true);
        brains.save(brain);
        var collection = collections.create(brainId, slug + "-guidelines", "Guidelines",
                slug + "-collection-key");

        InstanceReleaseManifest first = manifest(collection.id(), collection.version(),
                realSchemaDigest(), "");

        InstanceCandidateService.CandidateRelease one = candidates.create(
                new CreateInstanceCommand(brainId, slug, "Append only", "Synthetic", first));

        LabInstanceRelease storedOne = releaseRows
                .findByIdAndBrainIdAndInstanceSlug(one.releaseId(), brainId, slug).orElseThrow();
        assertEquals(1, storedOne.getReleaseNumber());
        assertEquals(LabInstanceRelease.ProvenanceMode.CANDIDATE, storedOne.getProvenanceMode());
        assertEquals(one.manifestSha256(), storedOne.getManifestSha256());
        assertNull(storedOne.getPredecessorReleaseId());
        // The fixture really does carry the value shapes that broke the snapshot comparison.
        assertEquals("2", String.valueOf(storedOne.getManifest().get("manifestVersion")));
        assertTrue(first.limits().maximumExpectedCostUsd().scale() > 0
                        && first.evaluations().minimumScore().scale() > 0
                        && first.limits().maximumInputTokens() > 0L,
                "the manifest must hold a scaled decimal and a long, or this proves nothing");

        InstanceCandidateService.CandidateRelease two = candidates.addCandidate(
                new CreateInstanceCommand(brainId, slug, "Append only", "Synthetic",
                        manifest(collection.id(), collection.version(), realSchemaDigest(),
                                "income")));
        assertEquals(2, two.releaseNumber());
        assertEquals(one.releaseId(), two.predecessorReleaseId());
        assertNotEquals(one.manifestSha256(), two.manifestSha256());

        List<LabInstanceRelease> history =
                releaseRows.findByBrainIdAndInstanceSlugOrderByReleaseNumberAsc(brainId, slug);
        assertEquals(List.of(one.releaseId(), two.releaseId()),
                history.stream().map(LabInstanceRelease::getId).toList(),
                "an edit appends; it does not replace");
        assertEquals(one.manifestSha256(), history.get(0).getManifestSha256());
        assertEquals(storedOne.getCreatedAt(), history.get(0).getCreatedAt(),
                "release 1 is the row the create wrote, unrewritten");
    }

    @Test
    void claimsExecuteFailSafelyAndNeverDecideForSiblings() {
        Seeded seeded = seed("e2e-mixed");
        stubCompatibleParse(seeded);

        UUID snapshotId = freeze(seeded);
        RunGroupService.CreatedRunGroup created = runGroups.create(
                group(seeded, snapshotId, 3), "e2e-mixed-key");
        assertEquals(3, created.memberRunIds().size());
        assertEquals(new BigDecimal("3"),
                jdbc.queryForObject("SELECT count(*) FROM lab_spend_reservation "
                        + "WHERE brain_id = ? AND status = 'RESERVED'",
                        BigDecimal.class, seeded.brainId));

        // ---- member one: claimed under the real lock, fails at engine re-verification with a
        // value-free code, and gives its reservation back.
        when(parsedInputs.resolveRegistered(any(), any(), any(), any()))
                .thenThrow(new ParsedDataResolver.ParsedDataException(
                        ParsedDataResolver.ParsedDataException.Code.PARSE_ENGINE_UNAVAILABLE));
        UUID first = claim();
        dispatcher.executeClaimed(first);
        assertEquals("FAILED", runStatus(first));
        assertEquals("PARSE_ENGINE_UNAVAILABLE", failureCode(first));
        assertEquals("RELEASED", reservationStatus(first));

        // ---- member two: claimed, then the group is cancelled around it — a PROCESSING member
        // is reported, not relabelled — then it fails on its own terms.
        UUID second = claim();
        RunGroupStatusService.CancellationOutcome cancelled =
                status.cancel(seeded.brainId, created.groupId());
        assertEquals(1, cancelled.cancelledMembers());
        assertEquals(1, cancelled.stillProcessingMembers());

        dispatcher.executeClaimed(second);
        assertEquals("FAILED", runStatus(second));
        assertEquals("RELEASED", reservationStatus(second));

        // ---- the third member was cancelled while QUEUED and can never be claimed now.
        assertTrue(dispatcher.claimNext().isEmpty(),
                "a cancelled member must be unclaimable");

        // ---- mixed outcomes: the group concluded, each member retaining its own record.
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM lab_run WHERE run_group_id = ? ORDER BY member_index",
                String.class, created.groupId());
        assertEquals(List.of("FAILED", "FAILED", "CANCELLED"), statuses);
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT status FROM lab_run_group WHERE id = ?", String.class,
                created.groupId()));
        assertEquals(new BigDecimal("0"),
                jdbc.queryForObject("SELECT count(*) FROM lab_spend_reservation "
                        + "WHERE brain_id = ? AND status = 'RESERVED'",
                        BigDecimal.class, seeded.brainId));

        // ---- every code written anywhere is vocabulary, never a message.
        for (String code : jdbc.queryForList("SELECT failure_code FROM lab_run "
                + "WHERE run_group_id = ? AND failure_code IS NOT NULL",
                String.class, created.groupId())) {
            assertTrue(SAFE_CODE.matcher(code).matches(),
                    "failure code is not value-free vocabulary: " + code);
        }

        // ---- no terminal member holds an open measurement: preparation failures and
        // cancellations both settle their usage rows honestly UNAVAILABLE, never PENDING forever.
        assertEquals(List.of("UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"),
                jdbc.queryForList("SELECT u.usage_quality FROM lab_model_usage u "
                        + "JOIN lab_run r ON r.id = u.run_id "
                        + "WHERE r.run_group_id = ? ORDER BY r.member_index", String.class,
                        created.groupId()));
    }

    @Test
    void anExpiredLeaseBecomesInterruptedExactlyOnceAndIsNeverReplayed() {
        Seeded seeded = seed("e2e-lease");
        stubCompatibleParse(seeded);
        UUID snapshotId = freeze(seeded);
        RunGroupService.CreatedRunGroup created = runGroups.create(
                group(seeded, snapshotId, 1), "e2e-lease-key");

        UUID claimed = claim();
        assertEquals("PROCESSING", runStatus(claimed));

        // The node dies here. The run guard permits updating a PROCESSING row, so the test
        // expires the lease directly rather than waiting ten minutes.
        jdbc.update("UPDATE lab_run SET lease_expires_at = now() - interval '1 minute' "
                + "WHERE id = ?", claimed);

        assertEquals(1, recovery.recoverExpired());
        assertEquals("INTERRUPTED", runStatus(claimed));
        assertEquals("RELEASED", reservationStatus(claimed));
        // Nobody knows whether the provider ran, so the usage row says exactly that — and a
        // terminal run never sits PENDING forever.
        assertEquals("UNAVAILABLE", jdbc.queryForObject(
                "SELECT usage_quality FROM lab_model_usage WHERE run_id = ?", String.class,
                claimed));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT attempt FROM lab_run WHERE id = ?", Integer.class, claimed));

        // No replay: a second sweep finds nothing, the member stays terminal, and no new
        // member appeared anywhere.
        assertEquals(0, recovery.recoverExpired());
        assertEquals("INTERRUPTED", runStatus(claimed));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT count(*) FROM lab_run WHERE run_group_id = ?", Integer.class,
                created.groupId()));
        assertTrue(dispatcher.claimNext().isEmpty());
    }

    @Test
    void aGroupOverTheBrainsBudgetIsRefusedBeforeAnythingExists() {
        Seeded seeded = seed("e2e-budget");
        stubCompatibleParse(seeded);
        Brain brain = brains.findById(seeded.brainId).orElseThrow();
        // The smallest budget the column can hold: daily_cost_budget_usd is NUMERIC(10,4), so
        // anything below 0.0001 rounds to zero on write — and zero means "unlimited", which is
        // the opposite of what this test needs. Any real estimate is orders of magnitude above.
        brain.setDailyCostBudgetUsd(new BigDecimal("0.0001"));
        brains.save(brain);

        UUID snapshotId = freeze(seeded);
        RunGroupService.RunGroupException refused = assertThrows(
                RunGroupService.RunGroupException.class,
                () -> runGroups.create(group(seeded, snapshotId, 1), "e2e-budget-key"));

        assertEquals(RunGroupService.RunGroupException.Code.RUN_GROUP_BLOCKED, refused.code());
        assertTrue(refused.blockingCodes().contains("GROUP_EXCEEDS_DAILY_BUDGET"),
                "expected the budget blocker, got " + refused.blockingCodes());
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT count(*) FROM lab_run_group WHERE brain_id = ?", Integer.class,
                seeded.brainId));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT count(*) FROM lab_spend_reservation WHERE brain_id = ?", Integer.class,
                seeded.brainId));
    }

    @Test
    void aSuccessfulMemberSealsItsResultReportsRealUsageAndLeavesASiblingsFailureAlone() {
        Seeded seeded = seedExecutable("e2e-success");
        stubCompatibleParse(seeded);
        UUID snapshotId = freeze(seeded);
        RunGroupService.CreatedRunGroup created = runGroups.create(
                group(seeded, snapshotId, 2), "e2e-success-key");

        // ---- member one: the provider fails; the member fails alone. The analyzer reports a
        // provider failure in-band as an ERROR result rather than by throwing — the run must
        // still be FAILED with the result's own code, never sealed as a success.
        UUID first = claim();
        dispatcher.executeClaimed(first);
        assertEquals("FAILED", runStatus(first));
        assertEquals("MODEL_PROVIDER_FAILED", failureCode(first));
        assertEquals("RELEASED", reservationStatus(first));

        // ---- member two: a schema-valid envelope with reported usage.
        String envelope = "{\"envelopeVersion\":\"2.0\",\"analyzer\":\"income\","
                + "\"reportMarkdown\":\"Synthetic verification run.\","
                + "\"facts\":[],\"assumptions\":[],\"warnings\":[],"
                + "\"recommendations\":[],\"calculations\":[],\"missingItems\":[],"
                + "\"citations\":[],\"confidence\":0.9}";
        RESPONDER.set(request -> new AiResponse(
                envelope, "synthetic", "synthetic-analyzer", 1200, 300, null));
        UUID second = claim();
        dispatcher.executeClaimed(second);

        assertEquals("SUCCEEDED", runStatus(second),
                "expected success; failure code was " + failureCode(second));
        assertEquals("CONSUMED", reservationStatus(second));

        // The sealed output and provenance exist only as ciphertext, and the analyzer row the
        // run pinned is a real V33 row — historical identity, not a parallel invention.
        assertTrue((int) jdbc.queryForObject(
                "SELECT count(*) FROM lab_run_payload WHERE run_id = ?", Integer.class,
                second) >= 1, "a successful run must hold sealed payloads");
        UUID analysisRunId = jdbc.queryForObject(
                "SELECT analysis_run_id FROM lab_run WHERE id = ?", UUID.class, second);
        assertNotNull(analysisRunId);
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT count(*) FROM analysis_runs WHERE id = ?", Integer.class,
                analysisRunId));

        // ---- measured truth: the reported categories exactly, the unreported one null — and
        // the failed sibling honestly UNAVAILABLE rather than PENDING forever.
        assertEquals("REPORTED", jdbc.queryForObject(
                "SELECT usage_quality FROM lab_model_usage WHERE run_id = ?", String.class,
                second));
        assertEquals(1200, (int) jdbc.queryForObject(
                "SELECT actual_input_tokens FROM lab_model_usage WHERE run_id = ?",
                Integer.class, second));
        assertEquals(300, (int) jdbc.queryForObject(
                "SELECT actual_output_tokens FROM lab_model_usage WHERE run_id = ?",
                Integer.class, second));
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT count(*) FROM lab_model_usage WHERE run_id = ? "
                        + "AND actual_cached_tokens IS NOT NULL", Integer.class, second));
        assertEquals("UNAVAILABLE", jdbc.queryForObject(
                "SELECT usage_quality FROM lab_model_usage WHERE run_id = ?", String.class,
                first));

        // ---- one success beside one failure is PARTIAL: the members that worked still count.
        assertEquals("PARTIAL", jdbc.queryForObject(
                "SELECT status FROM lab_run_group WHERE id = ?", String.class,
                created.groupId()));
    }

    // ================================================================ plumbing

    private UUID claim() {
        Optional<UUID> claimed = dispatcher.claimNext();
        assertTrue(claimed.isPresent(), "expected a claimable member");
        return claimed.get();
    }

    private String runStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM lab_run WHERE id = ?",
                String.class, runId);
    }

    private String failureCode(UUID runId) {
        return jdbc.queryForObject("SELECT failure_code FROM lab_run WHERE id = ?",
                String.class, runId);
    }

    private String reservationStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM lab_spend_reservation WHERE run_id = ?",
                String.class, runId);
    }

    private RunGroupCommand group(Seeded seeded, UUID snapshotId, int members) {
        List<RunGroupCommand.RunMemberCommand> commands = new java.util.ArrayList<>();
        for (int index = 0; index < members; index++) {
            commands.add(new RunGroupCommand.RunMemberCommand(
                    "income", seeded.releaseId, REGISTRATION, snapshotId));
        }
        return new RunGroupCommand(seeded.brainId, LabRunGroup.Mode.INDEPENDENT, null, commands);
    }

    private UUID freeze(Seeded seeded) {
        return snapshots.freeze(new CorpusSnapshotService.SnapshotRequest(seeded.brainId,
                List.of(new CorpusSnapshotService.CollectionVersionRef(
                        seeded.collectionId, seeded.collectionVersion)))).id();
    }

    // ================================================================ seeding

    private record Seeded(UUID brainId, UUID releaseId, UUID collectionId,
                          long collectionVersion) {}

    /**
     * Like {@link #seed}, but the release can actually run: a real schema digest from the
     * registry this build ships, and no retrieval query — grounding on parsed facts alone, so
     * no embedding provider is ever needed.
     */
    private Seeded seedExecutable(String prefix) {
        // Blank, not null: the manifest codec types every behavior field as a string, and the
        // analysis service reads a blank query as "ground on parsed facts alone".
        return seed(prefix, realSchemaDigest(), "");
    }

    /** The digest the output-schema allowlist this build ships actually holds. */
    private String realSchemaDigest() {
        return schemas.list().stream()
                .filter(descriptor -> descriptor.schemaId().equals("analyzer-envelope-v2"))
                .findFirst().orElseThrow().sha256();
    }

    private Seeded seed(String prefix) {
        return seed(prefix, "a".repeat(64), "income");
    }

    private Seeded seed(String prefix, String schemaDigest, String retrievalQuery) {
        UUID brainId = UUID.randomUUID();
        Brain brain = new Brain(brainId, prefix + "-brain", "E2E " + prefix);
        brain.setActive(true);
        brains.save(brain);

        var collection = collections.create(brainId, prefix + "-guidelines",
                "Guidelines", prefix + "-collection-key");

        instances.save(new LabInstance(brainId, "income", "Income", "Synthetic fixture."));
        EncodedManifest encoded = codec.encode(manifest(collection.id(), collection.version(),
                schemaDigest, retrievalQuery));
        byte[] canonical = new LabManifestWriter().canonicalize(encoded.json());
        UUID releaseId = UUID.randomUUID();
        jdbc.update("INSERT INTO lab_instance_release "
                        + "(id, brain_id, instance_slug, release_number, provenance_mode, "
                        + "manifest, manifest_sha256) "
                        + "VALUES (?, ?, 'income', 1, 'PRODUCTION', cast(? as jsonb), ?)",
                releaseId, brainId, new String(canonical, StandardCharsets.UTF_8),
                encoded.sha256());
        LabInstancePointer pointer = new LabInstancePointer();
        pointer.setBrainId(brainId);
        pointer.setInstanceSlug("income");
        pointer.setProductionReleaseId(releaseId);
        pointer.setPointerVersion(1L);
        pointers.save(pointer);

        jdbc.update("INSERT INTO lab_engine_package_binding (engine_package_id, brain_id) "
                + "VALUES (?, ?) ON CONFLICT (engine_package_id) DO NOTHING", PACKAGE, brainId);
        jdbc.update("INSERT INTO lab_document_registration "
                        + "(id, brain_id, instance_slug, engine_package_id, engine_job_id, "
                        + " registration_mode, selected_revision, source_set_sha256) "
                        + "VALUES (?, ?, 'income', ?, ?, 'EXISTING_PARSE', 4, ?) "
                        + "ON CONFLICT (id) DO NOTHING",
                REGISTRATION, brainId, PACKAGE, ENGINE_JOB, "c".repeat(64));

        return new Seeded(brainId, releaseId, collection.id(), collection.version());
    }

    private void stubCompatibleParse(Seeded seeded) {
        ParsedDataResolver.VerifiedParsedInput pinned = pinnedInput();
        when(parsedInputs.review(any(), any(), any(), any())).thenReturn(pinned);
        when(parsedInputs.resolveRegistered(any(), any(), any(), any())).thenReturn(pinned);
    }

    /** A real record over a real minimal envelope — the mock maker cannot stub record accessors. */
    private static ParsedDataResolver.VerifiedParsedInput pinnedInput() {
        EngineArtifactDescriptor artifact =
                EngineArtifactDescriptor.of("engine-bytes".getBytes(StandardCharsets.UTF_8));
        EngineResultEnvelope envelope = new EngineResultEnvelope(artifact, "1.0.0",
                "DOCENGINE-C14N-1", PACKAGE,
                new EngineResultEnvelope.Generation(ENGINE_JOB, 1, 4, "c".repeat(64),
                        "PARSE_ONCE_CURRENT_PACKAGE"),
                List.of(), List.of(), List.of(), List.of(),
                new EngineResultEnvelope.Provenance(
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        new EngineResultEnvelope.ReleaseAvailability("UNAVAILABLE"),
                        List.of()));
        return new ParsedDataResolver.VerifiedParsedInput(REGISTRATION, PACKAGE, 4, ENGINE_JOB, 1,
                "1.0.0", "DOCENGINE-C14N-1", artifact.sha256(), artifact.byteCount(),
                "c".repeat(64), List.of(SOURCE), envelope, envelope,
                new ParsedDataCompatibilityService.CompatibilityDecision(true, null, List.of(), 1));
    }

    private static InstanceReleaseManifest manifest(UUID collectionId, long collectionVersion,
                                                    String schemaDigest, String retrievalQuery) {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("synthetic", "synthetic-analyzer",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of(
                        new InstanceReleaseManifest.CollectionRef(collectionId, collectionVersion))),
                new InstanceReleaseManifest.BehaviorContract("system", "task", retrievalQuery,
                        BigDecimal.ZERO),
                List.of(),
                new InstanceReleaseManifest.OutputContract("analyzer-envelope-v2", schemaDigest),
                new InstanceReleaseManifest.LimitContract(100_000L, 20_000, 8_000, 0, 2,
                        new BigDecimal("5.00")),
                new InstanceReleaseManifest.EvaluationContract("income-smoke", 1,
                        new BigDecimal("0.80")));
    }
}
