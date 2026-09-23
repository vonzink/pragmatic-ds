package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.config.AiHttpClientFactory;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.provider.AiModelProvider;
import com.pragmaticds.rag.provider.AnthropicProvider;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.repository.BrainRepository;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.when;

class ModelRouterServiceTest {

    private static final UUID BRAIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final AiRequest REQUEST = AiRequest.forGuidelineAnswer("test prompt");

    private RagProperties properties(String defaultProvider, String fallbackProvider) {
        return new RagProperties(
                new RagProperties.Routing(defaultProvider, fallbackProvider),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/test"),
                new RagProperties.Admin("test-key"),
                new RagProperties.Analyze(null),
                new RagProperties.RateLimit(10, 60, 120));
    }

    /** Default RuntimeSettings mock: answer=anthropic (no model), utility same. */
    private RuntimeSettings defaultSettings(String defaultProvider) {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn(defaultProvider);
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn(defaultProvider);
        when(s.utilityModel()).thenReturn(null);
        return s;
    }

    /** Brain stub with null provider/model columns (falls back to global settings). */
    private Brain brainWithNoOverrides() {
        Brain b = new Brain(BRAIN, "default", "Default Brain");
        // provider/model columns are null → resolver falls back to global settings
        return b;
    }

    /** Brain stub with explicit answer and utility provider+model. */
    private Brain brainWith(String answerProvider, String answerModel,
                            String utilityProvider, String utilityModel) {
        Brain b = new Brain(BRAIN, "test", "Test Brain");
        b.setAnswerProvider(answerProvider);
        b.setAnswerModel(answerModel);
        b.setUtilityProvider(utilityProvider);
        b.setUtilityModel(utilityModel);
        return b;
    }

    /** BrainRepository stub returning the given brain for BRAIN id. */
    private BrainRepository brainRepo(Brain brain) {
        BrainRepository repo = mock(BrainRepository.class);
        when(repo.findById(BRAIN)).thenReturn(Optional.of(brain));
        return repo;
    }

    /** Shared registry so metrics tests can inspect the recorded rag.ai.call timers. */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** ModelRouterService with a permissive validator + placeholder local key (Phase 4b-2 ctor). */
    private ModelRouterService router(List<AiModelProvider> providers, RagProperties props,
                                      RuntimeSettings settings, BrainRepository repo) {
        return new ModelRouterService(providers, props, settings, repo,
                new LocalEndpointValidator(""), new AiHttpClientFactory(10_000, 60_000),
                meterRegistry, "test-local-key");
    }

    // ------------------------------------------------------------------
    // Existing tests — updated to pass BrainRepository mock

    @Test
    void routesToDefaultProvider() {
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = router(
                List.of(capturingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                repo);

        var routed = router.generate(REQUEST, BRAIN);

        assertEquals("anthropic", routed.response().providerName());
        assertFalse(routed.fallbackUsed());
    }

    @Test
    void fallsBackWhenPrimaryFails() {
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = router(
                List.of(failingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                repo);

        var routed = router.generate(REQUEST, BRAIN);

        assertEquals("openai", routed.response().providerName());
        assertTrue(routed.fallbackUsed());
    }

    @Test
    void recordsPerProviderCallMetrics() {
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = router(
                List.of(failingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                repo);

        router.generate(REQUEST, BRAIN);

        // The primary failure and the fallback success are each timed under rag.ai.call
        // with provider/outcome tags, so error rate and latency are observable per provider.
        assertEquals(1L, meterRegistry.get("rag.ai.call")
                .tags("provider", "anthropic", "outcome", "failure").timer().count());
        assertEquals(1L, meterRegistry.get("rag.ai.call")
                .tags("provider", "openai", "outcome", "success").timer().count());
    }

    @Test
    void throwsWhenPrimaryFailsAndNoFallbackConfigured() {
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = router(
                List.of(failingProvider("anthropic")),
                properties("anthropic", "anthropic"),
                defaultSettings("anthropic"),
                repo);

        assertThrows(RuntimeException.class, () -> router.generate(REQUEST, BRAIN));
    }

    @Test
    void missingDefaultProviderFailsAtRequestTime() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);
        var router = router(
                List.of(capturingProvider("openai")),
                properties("anthropic", "openai"),
                s,
                brainRepo(brainWithNoOverrides()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> router.generate(REQUEST, BRAIN));
        assertTrue(ex.getMessage().contains("ANTHROPIC_API_KEY"));
    }

    @Test
    void missingFallbackProviderDoesNotPreventStartup() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);
        var router = router(
                List.of(capturingProvider("anthropic")),
                properties("anthropic", "bogus"),
                s,
                brainRepo(brainWithNoOverrides()));

        var routed = router.generate(REQUEST, BRAIN);
        assertEquals("anthropic", routed.response().providerName());
        assertFalse(routed.fallbackUsed());
    }

    // ------------------------------------------------------------------
    // Settings-fallback tests (brain has null overrides → uses global settings)

    @Test
    void answerPurposeUsesAnswerSettings() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("openai");
        when(s.answerModel()).thenReturn("gpt-x");
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider openai = capturingProvider("openai");
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = router(
                List.of(openai, capturingProvider("anthropic")),
                properties("openai", "anthropic"),
                s,
                repo);

        router.generate(AiRequest.forGuidelineAnswer("test"), BRAIN);

        assertEquals("gpt-x", openai.lastRequest().model());
    }

    @Test
    void utilityPurposeUsesUtilitySettings() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("openai");
        when(s.answerModel()).thenReturn("gpt-x");
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider anthropic = capturingProvider("anthropic");
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = router(
                List.of(capturingProvider("openai"), anthropic),
                properties("openai", "anthropic"),
                s,
                repo);

        router.generate(AiRequest.forUtility("rerank prompt", 0.0, 800), BRAIN);

        assertTrue(anthropic.wasCalled(), "anthropic (utility provider) must have been called");
        assertNull(anthropic.lastRequest().model());
    }

    @Test
    void unknownConfiguredProviderFallsBackToDefaultProvider() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("gemini"); // not registered
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn("gemini");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider anthropic = capturingProvider("anthropic");
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = router(
                List.of(anthropic, capturingProvider("openai")),
                properties("anthropic", "openai"),
                s,
                repo);

        var routed = router.generate(AiRequest.forGuidelineAnswer("test"), BRAIN);

        // should fall back to routing.defaultProvider() = anthropic
        assertEquals("anthropic", routed.response().providerName());
        assertFalse(routed.fallbackUsed());
        assertTrue(anthropic.wasCalled());
    }

    @Test
    void fallbackProviderReceivesNoModelOverride() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn("claude-opus-4-5");
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider openai = capturingProvider("openai");
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = router(
                List.of(failingProvider("anthropic"), openai),
                properties("anthropic", "openai"),
                s,
                repo);

        var routed = router.generate(AiRequest.forGuidelineAnswer("test"), BRAIN);

        assertTrue(routed.fallbackUsed());
        // fallback must NOT receive the primary's model override
        assertNull(openai.lastRequest().model(),
                "Fallback provider must receive model=null, not the primary's model name");
    }

    @Test
    void providerNames() {
        var router = router(
                List.of(capturingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                mock(BrainRepository.class));

        assertEquals(Set.of("anthropic", "openai"), router.providerNames());
    }

    // ------------------------------------------------------------------
    // Brain-aware routing tests (Phase 4a)

    @Test
    void answerRequestUsesBrainAnswerProviderAndModel() {
        // brain explicitly configured: answer=anthropic/claude-x, utility=openai/gpt-x
        Brain brain = brainWith("anthropic", "claude-x", "openai", "gpt-x");
        BrainRepository repo = brainRepo(brain);

        CapturingProvider anthropic = capturingProvider("anthropic");
        var router = router(
                List.of(anthropic, capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                repo);

        var routed = router.generate(AiRequest.forGuidelineAnswer("p"), BRAIN);

        // must use the brain's paired (anthropic, claude-x)
        verify(repo).findById(BRAIN);
        assertEquals("anthropic", routed.response().providerName());
        assertEquals("claude-x", anthropic.lastRequest().model());
        assertFalse(routed.fallbackUsed());
    }

    @Test
    void utilityRequestUsesBrainUtilityProviderAndModel() {
        // brain's utility lane: openai/gpt-x
        Brain brain = brainWith("anthropic", "claude-x", "openai", "gpt-x");
        BrainRepository repo = brainRepo(brain);

        CapturingProvider openai = capturingProvider("openai");
        var router = router(
                List.of(capturingProvider("anthropic"), openai),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                repo);

        router.generate(AiRequest.forUtility("rerank", 0.0, 800), BRAIN);

        assertTrue(openai.wasCalled(), "utility request must go to brain's utility provider");
        assertEquals("gpt-x", openai.lastRequest().model());
    }

    @Test
    void brainWithNullAnswerProviderFallsBackToGlobalSettings() {
        // brain has no answer_provider set → must use global settings
        Brain brain = brainWithNoOverrides(); // all null
        BrainRepository repo = brainRepo(brain);

        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn("claude-global");
        when(s.utilityProvider()).thenReturn("openai");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider anthropic = capturingProvider("anthropic");
        var router = router(
                List.of(anthropic, capturingProvider("openai")),
                properties("anthropic", "openai"),
                s,
                repo);

        router.generate(AiRequest.forGuidelineAnswer("test"), BRAIN);

        assertEquals("claude-global", anthropic.lastRequest().model());
    }

    @Test
    void brainWithNullUtilityProviderFallsBackToGlobalSettings() {
        // brain has no utility_provider set → falls back to global utility settings
        Brain brain = brainWithNoOverrides();
        BrainRepository repo = brainRepo(brain);

        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn("openai");
        when(s.utilityModel()).thenReturn("gpt-global");

        CapturingProvider openai = capturingProvider("openai");
        var router = router(
                List.of(capturingProvider("anthropic"), openai),
                properties("anthropic", "openai"),
                s,
                repo);

        router.generate(AiRequest.forUtility("test", 0.0, 800), BRAIN);

        assertTrue(openai.wasCalled());
        assertEquals("gpt-global", openai.lastRequest().model());
    }

    @Test
    void brainFallbackOnErrorStillUsesNullModel() {
        // even with brain-aware routing, the fallback path must use withModel(null)
        Brain brain = brainWith("anthropic", "claude-x", "openai", "gpt-x");
        BrainRepository repo = brainRepo(brain);

        CapturingProvider openai = capturingProvider("openai");
        var router = router(
                List.of(failingProvider("anthropic"), openai),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                repo);

        var routed = router.generate(AiRequest.forGuidelineAnswer("test"), BRAIN);

        assertTrue(routed.fallbackUsed());
        assertNull(openai.lastRequest().model(),
                "Fallback provider must receive model=null even in brain-aware routing");
    }

    // ------------------------------------------------------------------
    // Per-brain local endpoint routing tests (Phase 4b-2)

    @Test
    void localProviderWithBaseUrlRoutesToBrainOwnEndpointNotGlobalLocalBean() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = """
                    {"id":"chatcmpl-test","object":"chat.completion","created":0,"model":"llama3",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"local-ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

        // brain: answer=local/llama3, and its own local_base_url set
        Brain brain = brainWith("local", "llama3", "local", "llama3");
        brain.setLocalBaseUrl(baseUrl);
        BrainRepository repo = brainRepo(brain);

        // global "local" bean is registered; it must NOT be used for this brain
        CapturingProvider globalLocal = capturingProvider("local");
        CapturingProvider fallback = capturingProvider("openai");
        LocalEndpointValidator validator = spy(new LocalEndpointValidator(""));

        var router = new ModelRouterService(
                List.of(globalLocal, fallback),
                properties("local", "openai"),
                defaultSettings("local"),
                repo,
                validator,
                new AiHttpClientFactory(10_000, 60_000),
                new SimpleMeterRegistry(),
                "test-local-key");

        try {
            var routed = router.generate(AiRequest.forGuidelineAnswer("p"), BRAIN);

            // The per-brain endpoint was selected (validator consulted with the brain's URL)
            verify(validator).validate(eq(baseUrl));
            // The global "local" bean was never used as the primary
            assertFalse(globalLocal.wasCalled(),
                    "global 'local' bean must not handle a brain that has its own local_base_url");
            assertFalse(routed.fallbackUsed());
            assertEquals("local", routed.response().providerName());
            assertEquals("local-ok", routed.response().content());
            assertFalse(fallback.wasCalled());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void localProviderWithoutBaseUrlUsesGlobalLocalBean() {
        // brain: answer=local but NO local_base_url → must use the registered global 'local' bean
        Brain brain = brainWith("local", "llama3", "local", "llama3");
        // no setLocalBaseUrl
        BrainRepository repo = brainRepo(brain);

        CapturingProvider globalLocal = capturingProvider("local");
        LocalEndpointValidator validator = spy(new LocalEndpointValidator(""));

        var router = new ModelRouterService(
                List.of(globalLocal, capturingProvider("openai")),
                properties("local", "openai"),
                defaultSettings("local"),
                repo,
                validator,
                new AiHttpClientFactory(10_000, 60_000),
                new SimpleMeterRegistry(),
                "test-local-key");

        var routed = router.generate(AiRequest.forGuidelineAnswer("p"), BRAIN);

        assertTrue(globalLocal.wasCalled(),
                "brain with provider=local and no local_base_url must use the global 'local' bean");
        assertEquals("llama3", globalLocal.lastRequest().model());
        assertEquals("local", routed.response().providerName());
        assertFalse(routed.fallbackUsed());
        // validator never consulted: no per-brain endpoint
        verify(validator, never()).validate(any());
    }

    @Test
    void unknownBrainIdThrows() {
        BrainRepository repo = mock(BrainRepository.class);
        UUID unknown = UUID.randomUUID();
        when(repo.findById(unknown)).thenReturn(Optional.empty());

        var router = router(
                List.of(capturingProvider("anthropic")),
                properties("anthropic", "anthropic"),
                defaultSettings("anthropic"),
                repo);

        assertThrows(IllegalArgumentException.class,
                () -> router.generate(REQUEST, unknown));
    }

    // ------------------------------------------------------------------
    // ANALYZE lane resolution (Task 1: routing lane for analyze/refine).
    // resolve(...) is package-private for exactly this kind of direct test.

    @Test
    void resolveAnalyzeFallsThroughToAnswerWhenUnset() {
        RuntimeSettings s = defaultSettings("anthropic");
        when(s.analyzeProvider()).thenReturn(null);
        when(s.analyzeModel()).thenReturn(null);
        Brain brain = brainWithNoOverrides();
        var router = router(
                List.of(capturingProvider("anthropic")),
                properties("anthropic", "anthropic"),
                s,
                brainRepo(brain));

        ModelRouterService.ResolvedModel r = router.resolve(brain, AiRequest.Purpose.ANALYZE);

        assertEquals("anthropic", r.provider());
        assertNull(r.model());
    }

    @Test
    void resolveAnalyzeUsesOverrideWhenProviderSet() {
        RuntimeSettings s = defaultSettings("anthropic");
        when(s.analyzeProvider()).thenReturn("anthropic");
        when(s.analyzeModel()).thenReturn("claude-sonnet-5");
        Brain brain = brainWithNoOverrides();
        var router = router(
                List.of(capturingProvider("anthropic")),
                properties("anthropic", "anthropic"),
                s,
                brainRepo(brain));

        ModelRouterService.ResolvedModel r = router.resolve(brain, AiRequest.Purpose.ANALYZE);

        assertEquals("anthropic", r.provider());
        assertEquals("claude-sonnet-5", r.model());
    }

    @Test
    void resolveAnalyzeUnsetFallsThroughToBrainAnswerColumnNotGlobalDefault() {
        // Regression pin: with analyze.provider unset, ANALYZE must fall through to the
        // full ANSWER resolution (brain column first, THEN global settings) — not skip
        // straight to settings.answerProvider(). The brain's answer column (openai/gpt-x)
        // must win over the global default (anthropic) configured below.
        RuntimeSettings s = defaultSettings("anthropic");
        when(s.analyzeProvider()).thenReturn(null);
        Brain brain = brainWith("openai", "gpt-x", "anthropic", "claude-x");
        var router = router(
                List.of(capturingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "anthropic"),
                s,
                brainRepo(brain));

        ModelRouterService.ResolvedModel r = router.resolve(brain, AiRequest.Purpose.ANALYZE);

        assertEquals("openai", r.provider());
        assertEquals("gpt-x", r.model());
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Per-analyzer provider+model override.
    //
    // Before this existed, AiRequest.model was overwritten by resolve() and silently
    // discarded, so an analyzer's model-override never reached a provider. The pair is
    // always provider+model together — the class invariant is that a model name is never
    // sent to a provider it does not belong to.

    @Test
    void explicitPairOverridesTheDefaultLane() {
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        CapturingProvider anthropic = capturingProvider("anthropic");
        CapturingProvider openai = capturingProvider("openai");
        var router = router(List.of(anthropic, openai),
                properties("openai", null), defaultSettings("openai"), repo);

        var routed = router.generate(
                AiRequest.forAnalysis("p", List.of(), 100, "anthropic", "claude-sonnet-4-5"), BRAIN);

        assertEquals("anthropic", routed.response().providerName());
        assertEquals("claude-sonnet-4-5", anthropic.lastRequest().model());
        assertFalse(openai.wasCalled(), "the default lane must not be used when a pair is given");
    }

    /**
     * An override naming a provider with no API key configured falls back to the default
     * lane rather than failing the run. The override model must NOT travel with it — that
     * would post a Claude model id to OpenAI.
     */
    @Test
    void unregisteredOverrideProviderFallsBackToDefaultLane() {
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        CapturingProvider openai = capturingProvider("openai");
        var router = router(List.of(openai),
                properties("openai", null), defaultSettings("openai"), repo);

        var routed = router.generate(
                AiRequest.forAnalysis("p", List.of(), 100, "grok", "grok-4.3"), BRAIN);

        assertEquals("openai", routed.response().providerName());
        assertNull(openai.lastRequest().model(),
                "the unreachable provider's model must never be sent to the fallback provider");
    }

    /** No override pair — behaviour is byte-for-byte what it was before. */
    @Test
    void noOverrideKeepsDefaultLaneResolution() {
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        CapturingProvider openai = capturingProvider("openai");
        var router = router(List.of(openai),
                properties("openai", null), defaultSettings("openai"), repo);

        var routed = router.generate(AiRequest.forAnalysis("p", List.of(), 100, null, null), BRAIN);

        assertEquals("openai", routed.response().providerName());
        assertNull(openai.lastRequest().model());
    }

    /** A model without a provider is not a pair, so it is ignored — the invariant holds. */
    @Test
    void modelWithoutProviderIsIgnored() {
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        CapturingProvider openai = capturingProvider("openai");
        var router = router(List.of(openai),
                properties("openai", null), defaultSettings("openai"), repo);

        router.generate(AiRequest.forAnalysis("p", List.of(), 100, null, "claude-sonnet-4-5"), BRAIN);

        assertNull(openai.lastRequest().model(),
                "an unpaired model must never reach a provider it may not belong to");
    }

    /** A provider that records the last AiRequest it received. */
    // ------------------------------------------------------------------
    // FallbackPolicy: a pinned release must never be answered by a different model

    /** A release that pins (provider, model) as part of its identity. */
    private static AiRequest pinned(String provider, String model) {
        return new AiRequest("prompt", 0.2, 1500, AiRequest.Purpose.ANSWER, provider, model,
                List.of());
    }

    @Test
    void anUnregisteredPinnedProviderIsRefusedRatherThanDroppedToTheLane() {
        CapturingProvider anthropic = capturingProvider("anthropic");
        var router = router(List.of(anthropic), properties("anthropic", "anthropic"),
                defaultSettings("anthropic"), brainRepo(brainWithNoOverrides()));

        var failure = assertThrows(ModelRouterService.SanitizedProviderException.class,
                () -> router.generateSanitized(pinned("grok", "grok-2"), BRAIN, "corr-1",
                        ModelRouterService.FallbackPolicy.NONE));

        assertEquals(ModelRouterService.SanitizedProviderException.Code.PROVIDER_PIN_UNAVAILABLE,
                failure.code());
        assertFalse(anthropic.wasCalled(),
                "the lane must not answer for a pin the release said not to substitute");
    }

    @Test
    void theSameUnregisteredPinStillFallsThroughUnderConfigured() {
        CapturingProvider anthropic = capturingProvider("anthropic");
        var router = router(List.of(anthropic), properties("anthropic", "anthropic"),
                defaultSettings("anthropic"), brainRepo(brainWithNoOverrides()));

        var response = router.generateSanitized(pinned("grok", "grok-2"), BRAIN, "corr-2");

        // Historical behaviour, unchanged: the pair is dropped and the lane answers.
        assertTrue(anthropic.wasCalled());
        assertFalse(response.resolution().requestedPairHonored());
    }

    @Test
    void aFailedPinnedCallIsNotRetriedOnAnotherProvidersDefaultModel() {
        CapturingProvider anthropic = capturingProvider("anthropic");
        var router = router(List.of(failingProvider("openai"), anthropic),
                properties("openai", "anthropic"), defaultSettings("openai"),
                brainRepo(brainWithNoOverrides()));

        var failure = assertThrows(ModelRouterService.SanitizedProviderException.class,
                () -> router.generateSanitized(pinned("openai", "gpt-x"), BRAIN, "corr-3",
                        ModelRouterService.FallbackPolicy.NONE));

        assertEquals(ModelRouterService.SanitizedProviderException.Code.PROVIDER_CALL_FAILED,
                failure.code());
        // The fallback runs its own default model. Answering from it would still record the
        // pinned release id, so the provenance would say something untrue.
        assertFalse(anthropic.wasCalled(), "no substitute model may answer a pinned request");
    }

    @Test
    void theSameFailureStillFallsBackUnderConfigured() {
        CapturingProvider anthropic = capturingProvider("anthropic");
        var router = router(List.of(failingProvider("openai"), anthropic),
                properties("openai", "anthropic"), defaultSettings("openai"),
                brainRepo(brainWithNoOverrides()));

        var response = router.generateSanitized(pinned("openai", "gpt-x"), BRAIN, "corr-4");

        assertTrue(anthropic.wasCalled());
        assertTrue(response.fallbackUsed());
        assertNull(anthropic.lastRequest().model(),
                "the fallback provider always runs its own default model");
    }

    private CapturingProvider capturingProvider(String name) {
        return new CapturingProvider(name);
    }

    private static class CapturingProvider implements AiModelProvider {
        private final String name;
        private AiRequest lastRequest;

        CapturingProvider(String name) {
            this.name = name;
        }

        @Override
        public AiResponse generate(AiRequest request) {
            this.lastRequest = request;
            return new AiResponse("{\"answer\":\"ok\"}", name, name + "-model", 100, 50);
        }

        @Override
        public String getProviderName() {
            return name;
        }

        @Override
        public String getModelName() {
            return name + "-model";
        }

        AiRequest lastRequest() {
            return lastRequest;
        }

        boolean wasCalled() {
            return lastRequest != null;
        }
    }

    /**
     * A failing provider that DOES carry media — the shape of a real Anthropic outage (expired
     * key, spent credit balance). Without {@code supportsMedia() == true} the primary guard fires
     * before the provider is ever called, and a fallback-guard test would pass for the wrong reason.
     */
    private AiModelProvider mediaCapableFailingProvider(String name) {
        return new AiModelProvider() {
            @Override
            public AiResponse generate(AiRequest request) {
                throw new RuntimeException(name + " API unavailable");
            }

            @Override
            public boolean supportsMedia() {
                return true;
            }

            @Override
            public String getProviderName() {
                return name;
            }

            @Override
            public String getModelName() {
                return name + "-model";
            }
        };
    }

    /** {@link #leakyProvider} that carries media — same reason as above. */
    private AiModelProvider mediaCapableLeakyProvider(String name) {
        return new AiModelProvider() {
            @Override
            public AiResponse generate(AiRequest request) {
                throw new IllegalStateException(
                        "POST https://api.example/v1/messages?key=CANARY-KEY-9 -> 500 "
                                + "{\"error\":\"CANARY-BODY-9\"}");
            }

            @Override
            public boolean supportsMedia() {
                return true;
            }

            @Override
            public String getProviderName() {
                return name;
            }

            @Override
            public String getModelName() {
                return name + "-model";
            }
        };
    }

    private AiModelProvider failingProvider(String name) {
        return new AiModelProvider() {
            @Override
            public AiResponse generate(AiRequest request) {
                throw new RuntimeException(name + " API unavailable");
            }

            @Override
            public String getProviderName() {
                return name;
            }

            @Override
            public String getModelName() {
                return name + "-model";
            }
        };
    }

    // ==================================================================
    // The Lab's sanitized entry point (Task 5)

    /** A provider whose exception message would leak a URI and a response body if carried. */
    private AiModelProvider leakyProvider(String name) {
        return new AiModelProvider() {
            @Override
            public AiResponse generate(AiRequest request) {
                throw new IllegalStateException(
                        "POST https://api.example/v1/messages?key=CANARY-KEY-9 -> 500 "
                                + "{\"error\":\"CANARY-BODY-9\"}");
            }

            @Override
            public String getProviderName() {
                return name;
            }

            @Override
            public String getModelName() {
                return name + "-model";
            }
        };
    }

    @Test
    void sanitizedRoutingReportsTheResolutionThatActuallyRan() {
        var router = router(
                List.of(capturingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()));

        var sanitized = router.generateSanitized(REQUEST, BRAIN, "corr-1");

        assertEquals("anthropic", sanitized.response().providerName());
        assertFalse(sanitized.fallbackUsed());
        ModelRouterService.Resolution resolution = sanitized.resolution();
        assertNull(resolution.requestedProvider());
        assertTrue(resolution.requestedPairHonored());
        assertEquals("anthropic", resolution.resolvedProvider());
        assertEquals("anthropic", resolution.answeringProvider());
        assertEquals("anthropic-model", resolution.answeringModel());
        assertFalse(resolution.fallbackUsed());
    }

    @Test
    void sanitizedRoutingReportsAnOverrideTheRouterDropped() {
        // The exact gap a caller mirroring the resolution order cannot see: a pinned pair naming a
        // provider with no API key is DROPPED and the lane is used instead.
        var router = router(
                List.of(capturingProvider("anthropic")),
                properties("anthropic", "anthropic"),
                defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()));

        ModelRouterService.Resolution resolution = router.generateSanitized(
                AiRequest.forAnalysis("p", List.of(), 100, "grok", "grok-4"),
                BRAIN, "corr-2").resolution();

        assertEquals("grok", resolution.requestedProvider());
        assertEquals("grok-4", resolution.requestedModel());
        assertFalse(resolution.requestedPairHonored(),
                "a dropped override must be reported, not silently reported as honoured");
        assertEquals("anthropic", resolution.resolvedProvider());
        assertEquals("anthropic", resolution.answeringProvider());
    }

    @Test
    void sanitizedRoutingHonoursAPinnedPairWhoseProviderIsRegistered() {
        var router = router(
                List.of(capturingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()));

        ModelRouterService.Resolution resolution = router.generateSanitized(
                AiRequest.forAnalysis("p", List.of(), 100, "openai", "gpt-x"),
                BRAIN, "corr-3").resolution();

        assertTrue(resolution.requestedPairHonored());
        assertEquals("openai", resolution.resolvedProvider());
        assertEquals("gpt-x", resolution.resolvedModel());
        assertEquals("openai", resolution.answeringProvider());
    }

    @Test
    void sanitizedRoutingPreservesFallbackBehaviourAndReportsIt() {
        var openai = capturingProvider("openai");
        var router = router(
                List.of(failingProvider("anthropic"), openai),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()));

        var sanitized = router.generateSanitized(REQUEST, BRAIN, "corr-4");

        assertTrue(sanitized.fallbackUsed());
        assertEquals("openai", sanitized.resolution().answeringProvider());
        assertEquals("anthropic", sanitized.resolution().resolvedProvider());
        assertTrue(openai.wasCalled());
        // The invariant the fallback exists to protect is unchanged on this path too.
        assertNull(openai.lastRequest().model());
    }

    @Test
    void aSanitizedProviderFailureCarriesNeitherMessageNorCause() {
        var router = router(
                List.of(leakyProvider("anthropic")),
                properties("anthropic", "anthropic"),
                defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()));

        var failure = assertThrows(ModelRouterService.SanitizedProviderException.class,
                () -> router.generateSanitized(REQUEST, BRAIN, "corr-5"));

        assertEquals(ModelRouterService.SanitizedProviderException.Code.PROVIDER_CALL_FAILED,
                failure.code());
        assertEquals("anthropic", failure.provider());
        assertEquals("IllegalStateException", failure.failureClass());
        assertEquals("corr-5", failure.correlationId());
        assertNull(failure.getCause(), "a cause would carry the provider's URI and body");
        assertFalse(failure.getMessage().contains("CANARY-KEY-9"));
        assertFalse(failure.getMessage().contains("CANARY-BODY-9"));
        assertFalse(failure.toString().contains("CANARY"));
    }

    @Test
    void aSanitizedFallbackFailureIsAlsoPayloadFree() {
        var router = router(
                List.of(failingProvider("anthropic"), leakyProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()));

        var failure = assertThrows(ModelRouterService.SanitizedProviderException.class,
                () -> router.generateSanitized(REQUEST, BRAIN, "corr-6"));

        assertEquals(ModelRouterService.SanitizedProviderException.Code.PROVIDER_FALLBACK_FAILED,
                failure.code());
        assertEquals("openai", failure.provider());
        assertFalse(failure.toString().contains("CANARY"));
    }

    @Test
    void sanitizedRoutingStillRecordsTheSameProviderMetric() {
        var registry = new SimpleMeterRegistry();
        var router = new ModelRouterService(List.of(capturingProvider("anthropic")),
                properties("anthropic", "anthropic"), defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()), new LocalEndpointValidator(""),
                new AiHttpClientFactory(10_000, 60_000), registry, "test-local-key");

        router.generateSanitized(REQUEST, BRAIN, "corr-7");

        assertEquals(1, registry.get("rag.ai.call")
                .tag("provider", "anthropic").tag("outcome", "success").timer().count());
    }

    @Test
    void theLegacyEntryPointStillThrowsTheProviderExceptionItself() {
        // Characterization: existing callers keep the verbose exception they have always seen.
        var router = router(
                List.of(leakyProvider("anthropic")),
                properties("anthropic", "anthropic"),
                defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()));

        var failure = assertThrows(IllegalStateException.class,
                () -> router.generate(REQUEST, BRAIN));

        assertTrue(failure.getMessage().contains("CANARY-BODY-9"));
    }

    // ------------------------------------------------------------------
    // Media guard: a provider that drops document blocks must never answer a
    // document-carrying request. Silence here is what let a whole batch of scanned
    // documents get classified from the manifest text alone, reported as SUCCESS.

    /** A request carrying one PDF block — what the documents analyzer actually sends. */
    private static AiRequest analysisWithOnePdf() {
        Media pdf = Media.builder()
                .mimeType(Media.Format.DOC_PDF)
                .data(new ByteArrayResource(new byte[]{1, 2, 3}))
                .build();
        return AiRequest.forAnalysis("classify these", List.of(pdf), 4000, null, null);
    }

    @Test
    void aMediaCarryingRequestIsRefusedRatherThanFallenBackToATextOnlyProvider() {
        CapturingProvider textOnlyFallback = capturingProvider("openai");
        var router = router(
                List.of(mediaCapableFailingProvider("anthropic"), textOnlyFallback),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()));

        assertThrows(IllegalStateException.class,
                () -> router.generate(analysisWithOnePdf(), BRAIN));

        // The point of the guard: the fallback never ran, so no evidence-free answer exists.
        assertFalse(textOnlyFallback.wasCalled(),
                "a provider that drops document blocks must not answer a document-carrying request");
    }

    @Test
    void theRefusalStillNamesWhyThePrimaryFailed() {
        var router = router(
                List.of(mediaCapableLeakyProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()));

        var failure = assertThrows(IllegalStateException.class,
                () -> router.generate(analysisWithOnePdf(), BRAIN));

        // On the legacy path the operator needs BOTH facts: the fallback could not carry the
        // documents, and the reason the primary failed in the first place (an expired key, a
        // spent credit balance) — otherwise the guard hides the thing that needs fixing.
        assertTrue(failure.getMessage().contains("openai"));
        assertTrue(failure.getMessage().contains("CANARY-BODY-9"),
                "the primary's failure reason must survive the refusal");
    }

    @Test
    void aTextOnlyRequestStillFallsBackNormally() {
        CapturingProvider textOnlyFallback = capturingProvider("openai");
        var router = router(
                List.of(failingProvider("anthropic"), textOnlyFallback),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()));

        var routed = router.generate(REQUEST, BRAIN);

        // The guard is scoped to media. Every text-only lane keeps the resilience it has today.
        assertEquals("openai", routed.response().providerName());
        assertTrue(routed.fallbackUsed());
        assertTrue(textOnlyFallback.wasCalled());
    }

    @Test
    void aTextOnlyPrimaryIsRefusedBeforeItIsEverCalled() {
        CapturingProvider textOnlyPrimary = capturingProvider("openai");
        CapturingProvider fallback = capturingProvider("anthropic-fallback");
        var router = router(
                List.of(textOnlyPrimary, fallback),
                properties("openai", "anthropic-fallback"),
                defaultSettings("openai"),
                brainRepo(brainWithNoOverrides()));

        assertThrows(IllegalStateException.class,
                () -> router.generate(analysisWithOnePdf(), BRAIN));

        // Refused outside the try: a config fault must not be caught as a "primary failure"
        // and then "recovered" by a second provider that would drop the documents too.
        assertFalse(textOnlyPrimary.wasCalled());
        assertFalse(fallback.wasCalled(), "the guard must not trigger the fallback ladder");
    }

    @Test
    void aSanitizedMediaRefusalIsPayloadFree() {
        var router = router(
                List.of(mediaCapableLeakyProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                brainRepo(brainWithNoOverrides()));

        var failure = assertThrows(ModelRouterService.SanitizedProviderException.class,
                () -> router.generateSanitized(analysisWithOnePdf(), BRAIN, "corr-media"));

        assertEquals(ModelRouterService.SanitizedProviderException.Code.PROVIDER_MEDIA_UNSUPPORTED,
                failure.code());
        assertEquals("openai", failure.provider());
        assertEquals("corr-media", failure.correlationId());
        // The Lab path's standing invariant: the primary's body must not ride along on the cause.
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("CANARY"));
    }

    @Test
    void anthropicDeclaresMediaSupportAndTheDefaultIsNo() {
        // The guard is only as good as this pairing: AnthropicProvider carries media blocks
        // (see its generate()), every other provider inherits the safe default.
        assertTrue(new AnthropicProvider(null, "claude-x").supportsMedia());
        assertFalse(capturingProvider("anything").supportsMedia());
    }
}
