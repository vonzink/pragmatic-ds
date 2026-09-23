package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.config.AdminApiKeyFilter;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.config.RequestCorrelationFilter;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstancePointer;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.instance.InstancePromotionService;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.run.domain.LabInstancePointerEvent;
import com.pragmaticds.rag.lab.run.repository.LabInstancePointerEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two reads promotion and configuration cannot work without.
 *
 * <p>The pointer version is the load-bearing one. A promotion is a compare-and-set on the release
 * <em>and</em> the version, and until this route existed the version was only ever returned by a
 * move — so a client could satisfy the check once and never again after a colleague moved the
 * pointer, which is exactly the case the check exists for.
 *
 * <p>The configuration read has a matching negative: it must carry everything needed to re-author a
 * release <em>except</em> its prompts. Prompt text is the one genuinely sensitive part of a
 * manifest, and a route returning it would put it in reach of every reader for a convenience. The
 * test asserts the absence directly, because that is the kind of thing a later refactor adds back
 * without noticing.
 */
class InstancePointerControllerTest {

    private static final String ADMIN_KEY = "test-admin-key";
    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RELEASE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PREVIOUS = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID COLLECTION = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String SYSTEM_PROMPT = "You are an income analyzer for underwriting.";
    private static final String TASK_PROMPT = "Summarize qualifying monthly income.";

    private LabInstancePointerRepository pointers;
    private LabInstancePointerEventRepository events;
    private InstanceReleaseResolver releases;
    private InstancePromotionService promotions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        pointers = mock(LabInstancePointerRepository.class);
        events = mock(LabInstancePointerEventRepository.class);
        releases = mock(InstanceReleaseResolver.class);
        promotions = mock(InstancePromotionService.class);
        when(promotions.promotionEnabled()).thenReturn(true);

        when(pointers.findByBrainIdAndInstanceSlug(eq(BRAIN), eq("income")))
                .thenReturn(Optional.of(pointer(RELEASE, 4L)));
        when(events.findByBrainIdAndInstanceSlugOrderByPointerVersionDesc(eq(BRAIN), eq("income")))
                .thenReturn(List.of(new LabInstancePointerEvent(
                        BRAIN, "income", LabInstancePointerEvent.Action.ROLLBACK,
                        PREVIOUS, RELEASE, 4L, "ops@example.com", "reverting a bad promotion")));

        mvc = MockMvcBuilders
                .standaloneSetup(new InstancePointerController(pointers, events, releases, promotions))
                .addFilters(new RequestCorrelationFilter("X-Auth-Request-Email"),
                        new AdminApiKeyFilter(properties()))
                .setControllerAdvice(new InstancePointerExceptionHandler())
                .build();
    }

    @Test
    void bothRoutesAreAdminGated() throws Exception {
        mvc.perform(get("/api/ai/admin/instances/income/pointer")
                        .param("brain", BRAIN.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/admin/instances/income/releases/" + RELEASE + "/configuration")
                        .param("brain", BRAIN.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void servesTheVersionACompareAndSetNeeds() throws Exception {
        mvc.perform(get("/api/ai/admin/instances/income/pointer")
                        .param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveReleaseId").value(RELEASE.toString()))
                // Without this, a second promotion in a session cannot state what it believes.
                .andExpect(jsonPath("$.pointerVersion").value(4))
                .andExpect(jsonPath("$.promotionEnabled").value(true));
    }

    /**
     * The deployment switch is reported alongside the version, from the service that enforces it.
     *
     * <p>Composing a move on a deployment where promotion is off is wasted work that ends in
     * {@code INSTANCE_PROMOTION_DISABLED}. Reading the answer from
     * {@link InstancePromotionService#promotionEnabled()} rather than from the property a second
     * time is what stops what this route reports and what a move actually does from drifting
     * apart.
     */
    @Test
    void reportsTheDeploymentPromotionSwitchAsTheServiceEnforcesIt() throws Exception {
        when(promotions.promotionEnabled()).thenReturn(false);

        mvc.perform(get("/api/ai/admin/instances/income/pointer")
                        .param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                // Still a full read: the switch being off changes what may be done, not what
                // may be seen.
                .andExpect(jsonPath("$.pointerVersion").value(4))
                .andExpect(jsonPath("$.promotionEnabled").value(false));
    }

    @Test
    void anInstanceThatHasNeverBeenPromotedReadsAsNothingLive() throws Exception {
        when(pointers.findByBrainIdAndInstanceSlug(eq(BRAIN), eq("fresh")))
                .thenReturn(Optional.empty());
        when(events.findByBrainIdAndInstanceSlugOrderByPointerVersionDesc(eq(BRAIN), eq("fresh")))
                .thenReturn(List.of());

        // A first promotion asserts that nothing is live, so the two views agree with no special
        // case: null release, version zero.
        mvc.perform(get("/api/ai/admin/instances/fresh/pointer")
                        .param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveReleaseId").doesNotExist())
                .andExpect(jsonPath("$.pointerVersion").value(0))
                .andExpect(jsonPath("$.promotionEnabled").value(true))
                .andExpect(jsonPath("$.events").isEmpty());
    }

    @Test
    void carriesWhoMovedThePointerAndWhy() throws Exception {
        mvc.perform(get("/api/ai/admin/instances/income/pointer")
                        .param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].action").value("ROLLBACK"))
                .andExpect(jsonPath("$.events[0].fromReleaseId").value(PREVIOUS.toString()))
                .andExpect(jsonPath("$.events[0].toReleaseId").value(RELEASE.toString()))
                .andExpect(jsonPath("$.events[0].pointerVersion").value(4))
                .andExpect(jsonPath("$.events[0].actorId").value("ops@example.com"))
                .andExpect(jsonPath("$.events[0].changeReason")
                        .value("reverting a bad promotion"));
    }

    @Test
    void servesEverythingNeededToReauthorARelease() throws Exception {
        when(releases.byId(any(), eq(RELEASE))).thenReturn(resolved());

        mvc.perform(get("/api/ai/admin/instances/income/releases/" + RELEASE + "/configuration")
                        .param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseNumber").value(3))
                .andExpect(jsonPath("$.live").value(true))
                .andExpect(jsonPath("$.parsedData.canonicalizationVersion")
                        .value("DOCENGINE-C14N-1"))
                .andExpect(jsonPath("$.model.model").value("claude-opus-5"))
                .andExpect(jsonPath("$.corpus[0].collectionId").value(COLLECTION.toString()))
                // The pin is the version, not just the collection; re-authoring must carry it.
                .andExpect(jsonPath("$.corpus[0].collectionVersion").value(7))
                .andExpect(jsonPath("$.output.schemaId").value("analyzer-envelope-v2"))
                .andExpect(jsonPath("$.limits.maximumInputTokens").value(100000))
                .andExpect(jsonPath("$.evaluations.scenarioSetId").value("income-smoke"));
    }

    @Test
    void neverServesTheReleasesPrompts() throws Exception {
        when(releases.byId(any(), eq(RELEASE))).thenReturn(resolved());

        MvcResult result = mvc.perform(
                        get("/api/ai/admin/instances/income/releases/" + RELEASE + "/configuration")
                                .param("brain", BRAIN.toString())
                                .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                // Said to exist, never shown. A client re-authoring this release needs to know it
                // owes prompts rather than inferring it from an absence that might mean anything.
                .andExpect(jsonPath("$.behaviorPresent").value(true))
                .andReturn();
        String body = result.getResponse().getContentAsString();

        // Asserted on the response bytes rather than on a field name, because the way this
        // protection gets lost is a later refactor serving the whole manifest.
        assertFalse(body.contains(SYSTEM_PROMPT), "the system prompt must never be served");
        assertFalse(body.contains(TASK_PROMPT), "the task prompt must never be served");
        assertFalse(body.contains("behavior\":{"), "the behavior contract must not be serialized");
        assertFalse(body.contains("temperature"), "temperature travels with the prompts");
    }

    @Test
    void refusesAReleaseThisBuildCannotDecode() throws Exception {
        when(releases.byId(any(), eq(RELEASE)))
                .thenThrow(new InstanceReleaseResolver.ReleaseResolutionException(
                        InstanceReleaseResolver.ReleaseResolutionException.Code.RELEASE_NOT_FOUND));

        mvc.perform(get("/api/ai/admin/instances/income/releases/" + RELEASE + "/configuration")
                        .param("brain", BRAIN.toString())
                        .header("X-Admin-Api-Key", ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_FOUND"));
    }

    private static LabInstancePointer pointer(UUID releaseId, long version) {
        LabInstancePointer pointer = new LabInstancePointer();
        pointer.setBrainId(BRAIN);
        pointer.setInstanceSlug("income");
        pointer.setProductionReleaseId(releaseId);
        pointer.setPointerVersion(version);
        return pointer;
    }

    private static ResolvedInstanceRelease resolved() {
        LabInstanceRelease release = new LabInstanceRelease();
        release.setId(RELEASE);
        release.setBrainId(BRAIN);
        release.setInstanceSlug("income");
        release.setReleaseNumber(3);

        InstanceReleaseManifest manifest = new InstanceReleaseManifest(
                2,
                new InstanceReleaseManifest.ParsedDataContract(
                        "1.0.0", "DOCENGINE-C14N-1", Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-opus-5",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of(
                        new InstanceReleaseManifest.CollectionRef(COLLECTION, 7L))),
                new InstanceReleaseManifest.BehaviorContract(
                        SYSTEM_PROMPT, TASK_PROMPT, "income", BigDecimal.ZERO),
                List.of(),
                new InstanceReleaseManifest.OutputContract(
                        "analyzer-envelope-v2", "a".repeat(64)),
                new InstanceReleaseManifest.LimitContract(
                        100_000L, 20_000, 8_000, 0, 2, new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract(
                        "income-smoke", 1, new BigDecimal("0.80")));

        return new ResolvedInstanceRelease(
                new LabInstance(BRAIN, "income", "Income", "Analyze income."),
                release, new DecodedInstanceManifest.V2(manifest), true);
    }

    private static RagProperties properties() {
        return new RagProperties(new RagProperties.Routing("anthropic", "openai"),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/documents"),
                new RagProperties.Admin(ADMIN_KEY), new RagProperties.Analyze(null),
                new RagProperties.RateLimit(10, 60, 120));
    }
}
