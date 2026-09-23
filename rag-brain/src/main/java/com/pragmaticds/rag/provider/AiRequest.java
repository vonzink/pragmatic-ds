package com.pragmaticds.rag.provider;

import org.springframework.ai.content.Media;

import java.util.List;

/**
 * Provider-agnostic request to a chat model.
 *
 * @param prompt      the fully built prompt (text)
 * @param temperature 0..1, keep low for guideline answers
 * @param maxTokens   completion budget
 * @param purpose     which routing lane this call uses (ANSWER = customer-facing
 *                    answers; UTILITY = internal plumbing like reranking;
 *                    ANALYZE = folder-brain document analysis/refine)
 * @param provider    caller-supplied provider override, paired with {@code model};
 *                    null = let the router resolve the lane. Only meaningful together
 *                    with a model — see the pairing rule below.
 * @param model       model override paired with {@code provider}, or the
 *                    router-populated lane model; null = provider default.
 * @param media       native document/image blocks (PDF/image) sent alongside the
 *                    text; empty for a plain text call. Providers that support
 *                    vision attach these to the user message.
 *
 * <p><b>Pairing rule:</b> a provider and model travel together or not at all. A model
 * name is only ever valid for its own provider, so the router ignores a model supplied
 * without a provider rather than risk posting, say, a Claude model id to OpenAI.
 */
public record AiRequest(String prompt, double temperature, int maxTokens,
                        Purpose purpose, String provider, String model, List<Media> media) {

    public AiRequest {
        media = media == null ? List.of() : List.copyOf(media);
    }

    public enum Purpose { ANSWER, UTILITY, ANALYZE }

    /** True when this request names a complete (provider, model) pair to honour. */
    public boolean hasProviderPair() {
        return provider != null && !provider.isBlank() && model != null && !model.isBlank();
    }

    public static AiRequest forGuidelineAnswer(String prompt) {
        return new AiRequest(prompt, 0.2, 1500, Purpose.ANSWER, null, null, List.of());
    }

    public static AiRequest forUtility(String prompt, double temperature, int maxTokens) {
        return new AiRequest(prompt, temperature, maxTokens, Purpose.UTILITY, null, null, List.of());
    }

    /**
     * The analyze lane's temperature when a caller does not name one.
     *
     * <p>Low on purpose: an underwriting answer that changes between identical runs is not a
     * second opinion, it is noise. A release may raise it, and the run's provenance records what
     * was used.
     */
    public static final double ANALYZE_TEMPERATURE = 0.1;

    /**
     * Folder-brain analysis: low temperature, larger completion budget, vision blocks.
     * Pass a provider+model pair to pin this analyzer to a specific model; pass null,null
     * to use the analyze lane.
     */
    public static AiRequest forAnalysis(String prompt, List<Media> media, int maxTokens,
                                        String provider, String model) {
        return forAnalysis(prompt, media, maxTokens, provider, model, ANALYZE_TEMPERATURE);
    }

    /**
     * Analysis at a caller-chosen temperature.
     *
     * <p>An instance release declares its own, and a manifest that says one thing while the run
     * executes at another makes the release's own provenance false.
     */
    public static AiRequest forAnalysis(String prompt, List<Media> media, int maxTokens,
                                        String provider, String model, double temperature) {
        return new AiRequest(prompt, temperature, maxTokens, Purpose.ANALYZE, provider, model,
                media);
    }

    /**
     * Text-only refine pass: analyze lane, no vision blocks, provider default model.
     * Always uses the analyze-lane default model; per-analyzer overrides do not apply
     * to refine.
     */
    public static AiRequest forRefine(String prompt, int maxTokens) {
        return new AiRequest(prompt, 0.1, maxTokens, Purpose.ANALYZE, null, null, List.of());
    }

    public AiRequest withModel(String model) {
        return new AiRequest(prompt, temperature, maxTokens, purpose, provider, model, media);
    }
}
