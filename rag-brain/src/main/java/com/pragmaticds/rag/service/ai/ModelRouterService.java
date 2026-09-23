package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.config.AiHttpClientFactory;
import com.pragmaticds.rag.config.RagProperties;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.provider.AiModelProvider;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.provider.OpenAiCompatibleProvider;
import com.pragmaticds.rag.repository.BrainRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes requests to the per-purpose provider resolved per brain (Phase 4a),
 * falling back to global RuntimeSettings when brain columns are unset, and to
 * the configured fallback provider if the primary call fails.
 *
 * Resolution order (paired — never mixing a model name across providers):
 *   1. Brain's answer_/utility_provider + model column (when non-null/non-blank)
 *   2. Global RuntimeSettings answer/utility provider + model
 *   3. Fallback provider on exception (always withModel(null))
 *
 * CRITICAL invariant: the fallback provider always receives request.withModel(null)
 * — a primary's model name must never be sent to a different provider.
 */
@Service
public class ModelRouterService {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterService.class);

    private final Map<String, AiModelProvider> providers;
    private final RagProperties.Routing routing;
    private final RuntimeSettings settings;
    private final BrainRepository brainRepository;
    private final LocalEndpointValidator localEndpointValidator;
    private final AiHttpClientFactory httpClientFactory;
    private final MeterRegistry meterRegistry;
    private final String localApiKey;

    /** Per-base-URL cache of providers bound to a brain's own local endpoint. */
    private final Map<String, AiModelProvider> localProviderCache = new ConcurrentHashMap<>();

    public ModelRouterService(List<AiModelProvider> providerBeans, RagProperties properties,
                              RuntimeSettings settings, BrainRepository brainRepository,
                              LocalEndpointValidator localEndpointValidator,
                              AiHttpClientFactory httpClientFactory,
                              MeterRegistry meterRegistry,
                              @Value("${brain.providers.local.api-key:}") String localApiKey) {
        this.providers = providerBeans.stream()
                .collect(Collectors.toMap(AiModelProvider::getProviderName, Function.identity()));
        this.routing = properties.routing();
        this.settings = settings;
        this.brainRepository = brainRepository;
        this.localEndpointValidator = localEndpointValidator;
        this.httpClientFactory = httpClientFactory;
        this.meterRegistry = meterRegistry;
        // Local servers usually ignore the key; Spring AI still requires a non-blank one.
        this.localApiKey = (localApiKey == null || localApiKey.isBlank()) ? "not-needed" : localApiKey;

        if (!providers.containsKey(routing.defaultProvider())) {
            log.warn("Default AI provider '{}' is not configured. Available providers: {}",
                    routing.defaultProvider(), providers.keySet());
        }
        String fallback = routing.fallbackProvider();
        if (fallback != null && !fallback.isBlank() && !providers.containsKey(fallback)) {
            log.warn("Fallback AI provider '{}' is not configured. Available providers: {}",
                    fallback, providers.keySet());
        }
    }

    /**
     * Generates a response for the given request, resolving the provider+model
     * from the brain's columns first, then global settings.
     *
     * @return the response plus whether the fallback provider had to be used
     */
    public RoutedResponse generate(AiRequest request, UUID brainId) {
        Outcome outcome = route(request, brainId, null, FallbackPolicy.CONFIGURED);
        return new RoutedResponse(outcome.response(), outcome.resolution().fallbackUsed());
    }

    /**
     * What a caller permits when its requested provider or model cannot be used.
     *
     * <p>{@code CONFIGURED} is the historical behaviour and the default everywhere: an
     * unregistered provider falls through to the lane, an unregistered lane provider falls
     * through to the routing default, and a failed primary call is retried on the fallback
     * provider running <em>its own</em> default model.
     *
     * <p>{@code NONE} refuses all three. A v2 release pins an exact provider and model as part of
     * its identity, and a run that silently answered from a different model would still record
     * that release id — the provenance would be a lie. Failing closed is the only way the pin
     * means anything.
     */
    public enum FallbackPolicy {
        NONE,
        CONFIGURED
    }

    /**
     * The Lab's entry point: identical routing, fallback, and metrics — sanitized reporting.
     *
     * <p>Two things the ordinary {@link #generate} cannot give a Lab caller:
     *
     * <ul>
     *   <li><b>The router's own resolution.</b> {@link #resolve} is package-private and a
     *       caller-supplied pair naming a provider with no API key is silently dropped in favour of
     *       the lane, so any caller that mirrors the resolution order is reporting an
     *       approximation. {@link Resolution} is what this router actually decided and what
     *       actually answered, so a run's provenance states fact rather than inference.
     *   <li><b>Payload-free failure.</b> A provider exception carries the request URI and often the
     *       provider's response body. On this path it is replaced by
     *       {@link SanitizedProviderException} — provider name, failure class, correlation id — with
     *       no message and no cause, so nothing can reach a Lab response, the global unexpected
     *       handler, or a log line. Logging here is by class name only for the same reason.
     * </ul>
     *
     * <p>{@link #generate} is untouched, so every existing caller keeps its current behaviour and
     * its current (verbose) logging.
     */
    public SanitizedResponse generateSanitized(AiRequest request, UUID brainId,
                                               String correlationId) {
        return generateSanitized(request, brainId, correlationId, FallbackPolicy.CONFIGURED);
    }

    /** The pinned form: {@link FallbackPolicy#NONE} refuses every silent substitution. */
    public SanitizedResponse generateSanitized(AiRequest request, UUID brainId,
                                               String correlationId,
                                               FallbackPolicy fallbackPolicy) {
        Outcome outcome = route(request, brainId,
                Objects.requireNonNull(correlationId, "correlationId"),
                Objects.requireNonNull(fallbackPolicy, "fallbackPolicy"));
        return new SanitizedResponse(outcome.response(), outcome.resolution());
    }

    /** One routed call. {@code correlationId} non-null selects sanitized logging and failures. */
    private Outcome route(AiRequest request, UUID brainId, String correlationId,
                          FallbackPolicy fallbackPolicy) {
        Brain brain = brainRepository.findById(brainId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + brainId));

        // A caller-supplied (provider, model) pair wins over the lane — that is how a
        // per-analyzer model is pinned. Honoured only when the provider is registered
        // (i.e. its API key is configured); otherwise we fall through to the lane, and
        // the override model is dropped with it so it can never reach a foreign provider.
        ResolvedModel resolved = null;
        boolean pairHonoured = true;
        if (request.hasProviderPair()) {
            if (providers.containsKey(request.provider())) {
                resolved = new ResolvedModel(request.provider(), request.model());
            } else {
                if (fallbackPolicy == FallbackPolicy.NONE) {
                    throw new SanitizedProviderException(
                            SanitizedProviderException.Code.PROVIDER_PIN_UNAVAILABLE,
                            request.provider(), "IllegalStateException", correlationId);
                }
                pairHonoured = false;
                if (correlationId == null) {
                    log.warn("Requested provider '{}' is not configured; falling back to the {} lane. "
                                    + "Registered providers: {}",
                            request.provider(), request.purpose().name().toLowerCase(Locale.ROOT),
                            providers.keySet());
                } else {
                    log.warn("Requested provider '{}' is not configured; using the {} lane [{}]",
                            request.provider(), request.purpose().name().toLowerCase(Locale.ROOT),
                            correlationId);
                }
            }
        }
        if (resolved == null) {
            resolved = resolve(brain, request.purpose());
        }

        AiModelProvider primary;
        if ("local".equals(resolved.provider())
                && brain.getLocalBaseUrl() != null && !brain.getLocalBaseUrl().isBlank()) {
            primary = localProviderFor(brain.getLocalBaseUrl());   // per-brain endpoint
        } else {
            primary = providers.get(resolved.provider());
            if (primary == null && fallbackPolicy == FallbackPolicy.NONE) {
                throw new SanitizedProviderException(
                        SanitizedProviderException.Code.PROVIDER_PIN_UNAVAILABLE,
                        resolved.provider(), "IllegalStateException", correlationId);
            }
            if (primary == null) {
                log.warn("Configured {} provider '{}' is not registered; using default '{}'",
                        request.purpose().name().toLowerCase(Locale.ROOT),
                        resolved.provider(), routing.defaultProvider());
                primary = providers.get(routing.defaultProvider());
            }
            if (primary == null) {
                if (correlationId != null) {
                    throw new SanitizedProviderException(
                            SanitizedProviderException.Code.PROVIDER_NOT_CONFIGURED,
                            resolved.provider(), "IllegalStateException", correlationId);
                }
                throw missingProvider(resolved.provider(), request.purpose());
            }
        }

        // Outside the try on purpose: a provider that would silently drop this request's documents
        // is a configuration fault, not a provider failure, and must not be caught below and
        // "recovered" by falling back to a second provider that would drop them too.
        requireMediaSupport(request, primary, correlationId, null);

        Timer.Sample primarySample = Timer.start(meterRegistry);
        try {
            AiResponse response = primary.generate(request.withModel(resolved.model()));
            recordCall(primarySample, primary.getProviderName(), resolved.model(), request.purpose(), "success");
            return new Outcome(response, resolution(request, pairHonoured, resolved, response, false));
        } catch (Exception primaryFailure) {
            recordCall(primarySample, primary.getProviderName(), resolved.model(), request.purpose(), "failure");
            logProviderFailure(primary, primaryFailure, correlationId);

            AiModelProvider fallback = fallbackPolicy == FallbackPolicy.NONE
                    ? null
                    : providers.get(routing.fallbackProvider());
            if (fallback == null || fallback == primary) {
                throw sanitize(primaryFailure, primary,
                        SanitizedProviderException.Code.PROVIDER_CALL_FAILED, correlationId);
            }
            // Checked BEFORE the "falling back" line is logged: a fallback that would drop this
            // request's documents never happens, so the log must not claim it did. The fallback
            // provider is chosen by config, not by capability. Answering a document-carrying
            // analysis from the prompt text alone yields a confident, plausible, evidence-free
            // verdict recorded as SUCCESS — strictly worse than an error, because nothing
            // downstream can tell the two apart.
            requireMediaSupport(request, fallback, correlationId, primaryFailure);

            if (correlationId == null) {
                log.warn("Falling back to provider '{}'", fallback.getProviderName());
            } else {
                log.warn("Falling back to provider '{}' [{}]",
                        fallback.getProviderName(), correlationId);
            }
            // The fallback always runs its own default model — a primary's
            // model name must never be sent to a different provider.
            Timer.Sample fallbackSample = Timer.start(meterRegistry);
            try {
                AiResponse response = fallback.generate(request.withModel(null));
                recordCall(fallbackSample, fallback.getProviderName(), null, request.purpose(), "success");
                return new Outcome(response,
                        resolution(request, pairHonoured, resolved, response, true));
            } catch (Exception fallbackFailure) {
                recordCall(fallbackSample, fallback.getProviderName(), null, request.purpose(), "failure");
                throw sanitize(fallbackFailure, fallback,
                        SanitizedProviderException.Code.PROVIDER_FALLBACK_FAILED, correlationId);
            }
        }
    }

    private static Resolution resolution(AiRequest request, boolean pairHonoured,
                                         ResolvedModel resolved, AiResponse response,
                                         boolean fallbackUsed) {
        return new Resolution(request.provider(), request.model(), pairHonoured,
                resolved.provider(), resolved.model(),
                response.providerName(), response.modelName(), fallbackUsed);
    }

    private void logProviderFailure(AiModelProvider provider, Exception failure,
                                    String correlationId) {
        if (correlationId == null) {
            log.error("Primary AI provider '{}' failed: {}",
                    provider.getProviderName(), failure.getMessage());
            return;
        }
        // Class name only: a provider exception message routinely quotes the request URI and the
        // provider's own response body, and a Lab log line must carry neither.
        log.error("AI provider '{}' failed ({}) [{}]",
                provider.getProviderName(), failure.getClass().getSimpleName(), correlationId);
    }

    /**
     * Refuse to hand a media-carrying request to a provider that would drop the media.
     *
     * <p>Silence is the whole problem: {@link AiModelProvider#supportsMedia() a provider that does
     * not carry media} still answers, still returns a well-formed result, and still gets recorded
     * as SUCCESS — from the prompt text alone. A classifier asked to name a document type without
     * ever seeing the document does not fail; it guesses, and it guesses the same way every time.
     * An explicit failure is recoverable. A confident evidence-free answer is not.
     *
     * <p>Text-only requests are unaffected: {@code media} is empty, so every non-vision lane keeps
     * the fallback ladder it has today.
     *
     * @param primaryFailure the failure that caused this fallback, or null when checking the
     *                       primary. On the legacy path it becomes the cause, so the reason the
     *                       first provider failed is not lost behind this one.
     */
    private void requireMediaSupport(AiRequest request, AiModelProvider provider,
                                     String correlationId, Exception primaryFailure) {
        if (request.media().isEmpty() || provider.supportsMedia()) {
            return;
        }
        if (correlationId == null) {
            String reason = "AI provider '" + provider.getProviderName() + "' cannot send "
                    + "document/image blocks; refusing to answer " + request.media().size()
                    + " document(s) from the prompt text alone";
            log.error(reason);
            throw primaryFailure == null
                    ? new IllegalStateException(reason)
                    : new IllegalStateException(reason + "; the primary provider failed first: "
                            + primaryFailure.getMessage(), primaryFailure);
        }
        // Sanitized path: the provider name is the whole diagnosis, and a document count is not
        // payload, so both are safe. No message and no cause, exactly as everywhere else here.
        log.error("AI provider '{}' cannot send document/image blocks [{}]",
                provider.getProviderName(), correlationId);
        throw new SanitizedProviderException(
                SanitizedProviderException.Code.PROVIDER_MEDIA_UNSUPPORTED,
                provider.getProviderName(), "IllegalStateException", correlationId);
    }

    /** Legacy callers get the original exception; Lab callers get a code, never a cause. */
    private static RuntimeException sanitize(Exception failure, AiModelProvider provider,
                                             SanitizedProviderException.Code code,
                                             String correlationId) {
        if (correlationId == null) {
            return failure instanceof RuntimeException unchecked
                    ? unchecked
                    : new IllegalStateException(failure);
        }
        return new SanitizedProviderException(code, provider.getProviderName(),
                failure.getClass().getSimpleName(), correlationId);
    }

    /** Internal carrier so both entry points share one invocation and one fallback ladder. */
    private record Outcome(AiResponse response, Resolution resolution) {}

    /**
     * Records latency + count for one AI provider call, tagged so operators can
     * watch cost/latency/error rate per provider and model (rag.ai.call timer).
     */
    private void recordCall(Timer.Sample sample, String provider, String model,
                            AiRequest.Purpose purpose, String outcome) {
        sample.stop(Timer.builder("rag.ai.call")
                .description("AI provider call latency and count by provider/model/outcome")
                .tag("provider", provider == null ? "unknown" : provider)
                .tag("model", model == null ? "default" : model)
                .tag("purpose", purpose.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome)
                .register(meterRegistry));
    }

    /**
     * Resolves a paired (provider, model) for the given purpose.
     * UTILITY and ANSWER use the brain column then the global default.
     * ANALYZE prefers an explicit analyze.provider override, else behaves exactly like ANSWER
     * (so an unset analyze lane is byte-for-byte today's behaviour). A model name is only ever
     * paired with its own provider — never mixed across providers. Package-private for testing.
     */
    ResolvedModel resolve(Brain brain, AiRequest.Purpose purpose) {
        if (purpose == AiRequest.Purpose.UTILITY) {
            String brainProvider = brain.getUtilityProvider();
            if (brainProvider != null && !brainProvider.isBlank()) {
                return new ResolvedModel(brainProvider, brain.getUtilityModel());
            }
            return new ResolvedModel(settings.utilityProvider(), settings.utilityModel());
        }
        if (purpose == AiRequest.Purpose.ANALYZE) {
            String analyzeProvider = settings.analyzeProvider();
            if (analyzeProvider != null && !analyzeProvider.isBlank()) {
                return new ResolvedModel(analyzeProvider, settings.analyzeModel());
            }
            // fall through to the ANSWER resolution below
        }
        String brainProvider = brain.getAnswerProvider();
        if (brainProvider != null && !brainProvider.isBlank()) {
            return new ResolvedModel(brainProvider, brain.getAnswerModel());
        }
        return new ResolvedModel(settings.answerProvider(), settings.answerModel());
    }

    /**
     * Returns (and caches) a provider bound to a brain's own local endpoint. The base
     * URL is SSRF-validated on first use (link-local/metadata always blocked; allowlist
     * enforced when set) — also enforced at admin write time. The per-request model is
     * passed via withModel(...), so one cached client per base URL serves any model.
     */
    private AiModelProvider localProviderFor(String baseUrl) {
        return localProviderCache.computeIfAbsent(baseUrl, url -> {
            localEndpointValidator.validate(url);   // SSRF check at first use
            return new OpenAiCompatibleProvider("local", url, localApiKey, "",
                    httpClientFactory.restClientBuilder());
        });
    }

    private IllegalStateException missingProvider(String provider, AiRequest.Purpose purpose) {
        String hint = switch (provider) {
            case "anthropic" -> "Set ANTHROPIC_API_KEY or choose another configured answer provider.";
            case "openai" -> "Set OPENAI_API_KEY or choose another configured provider.";
            case "local" -> "Set LOCAL_LLM_BASE_URL and a local model, or choose another provider.";
            default -> "Configure this provider in environment variables or choose a registered provider.";
        };
        return new IllegalStateException("AI provider '" + provider + "' is not configured for "
                + purpose.name().toLowerCase() + " requests. " + hint
                + " Registered providers: " + providers.keySet());
    }

    record ResolvedModel(String provider, String model) {}

    public Set<String> providerNames() {
        return java.util.Set.copyOf(providers.keySet());
    }

    public record RoutedResponse(AiResponse response, boolean fallbackUsed) {
    }

    /**
     * What this router decided and what actually answered — the fact a Lab run records instead of
     * a mirrored guess.
     *
     * @param requestedProvider the caller's pinned provider, or null when it named no pair
     * @param requestedModel the caller's pinned model, or null
     * @param requestedPairHonored false exactly when a pinned pair was DROPPED because that
     *     provider is not registered, and the lane was used instead
     * @param resolvedProvider the provider the resolution chose before any fallback
     * @param resolvedModel the model the resolution chose, or null for the provider's own default
     * @param answeringProvider the provider that actually produced the response
     * @param answeringModel the model that actually produced the response
     * @param fallbackUsed true when the primary failed and the fallback provider answered
     */
    public record Resolution(
            String requestedProvider,
            String requestedModel,
            boolean requestedPairHonored,
            String resolvedProvider,
            String resolvedModel,
            String answeringProvider,
            String answeringModel,
            boolean fallbackUsed) {}

    /** One sanitized routed call: the response plus the router's own resolution. */
    public record SanitizedResponse(AiResponse response, Resolution resolution) {

        public SanitizedResponse {
            Objects.requireNonNull(response, "response");
            Objects.requireNonNull(resolution, "resolution");
        }

        public boolean fallbackUsed() {
            return resolution.fallbackUsed();
        }
    }

    /**
     * A payload-free provider failure for the Lab path: a stable code, the provider name, the
     * failure's class name, and the correlation id. No message, no cause, so neither a request URI
     * nor a provider response body can travel into a Lab response, log, or audit row.
     */
    public static final class SanitizedProviderException extends RuntimeException {

        /** Stable, value-free provider failure taxonomy. */
        public enum Code {
            /** No provider is registered for the resolved lane. */
            PROVIDER_NOT_CONFIGURED,
            /** The primary provider failed and no distinct fallback was available. */
            PROVIDER_CALL_FAILED,
            /** The fallback provider failed too. */
            PROVIDER_FALLBACK_FAILED,
            /** A pinned provider or model was unavailable and the release forbids substitution. */
            PROVIDER_PIN_UNAVAILABLE,
            /**
             * The request carried document/image blocks and the provider that would have answered
             * it does not send them. Refused rather than answered from the prompt text alone.
             */
            PROVIDER_MEDIA_UNSUPPORTED
        }

        private final Code code;
        private final String provider;
        private final String failureClass;
        private final String correlationId;

        public SanitizedProviderException(Code code, String provider, String failureClass,
                                          String correlationId) {
            super(Objects.requireNonNull(code, "code").name(), null, false, true);
            this.code = code;
            this.provider = provider;
            this.failureClass = failureClass;
            this.correlationId = correlationId;
        }

        public Code code() {
            return code;
        }

        public String provider() {
            return provider;
        }

        /** The simple class name of the underlying failure — never its message. */
        public String failureClass() {
            return failureClass;
        }

        public String correlationId() {
            return correlationId;
        }

        @Override
        public String toString() {
            return "SanitizedProviderException[code=" + code + ", provider=" + provider
                    + ", failureClass=" + failureClass + ", correlationId=" + correlationId + "]";
        }
    }
}
