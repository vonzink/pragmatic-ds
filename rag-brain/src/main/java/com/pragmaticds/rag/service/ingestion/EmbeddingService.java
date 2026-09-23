package com.pragmaticds.rag.service.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin wrapper around Spring AI's EmbeddingModel so the rest of the app
 * never depends on a specific provider. Currently backed by OpenAI
 * text-embedding-3-small (Anthropic has no embeddings API).
 *
 * Handles OpenAI 429 rate limits with exponential backoff — large documents
 * (e.g. the 1,000+ page FHA handbook) can exhaust the tokens-per-minute
 * budget mid-ingest, and the correct behavior there is to wait, not fail.
 *
 * The retry policy is deliberately split by call site. {@link #embedBatch}
 * (ingestion) keeps the long exponential backoff. {@link #embed} runs on the
 * interactive /ask query path, so it retries at most once quickly: a long
 * per-request sleep there would pin Tomcat worker threads and stall the whole
 * service during a provider rate-limit window — the caller (AskService) already
 * turns a fast embedding failure into a graceful escalation.
 *
 * To switch providers (e.g. Bedrock Titan), change the injected bean —
 * no other code changes. NOTE: if the new model has a different dimension,
 * re-embed all chunks and migrate the vector(1536) column accordingly.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /** Ingestion: wait out rate limits (5s, 10s, 20s, 40s, 80s). */
    private static final int INGEST_MAX_ATTEMPTS = 6;
    private static final long INGEST_INITIAL_BACKOFF_MS = 5_000;

    /** Interactive query path: one quick retry only, then fail fast to escalation. */
    private static final int QUERY_MAX_ATTEMPTS = 2;
    private static final long QUERY_INITIAL_BACKOFF_MS = 1_000;

    private final ObjectProvider<EmbeddingModel> embeddingModel;

    public EmbeddingService(@Qualifier("openAiEmbeddingModel") ObjectProvider<EmbeddingModel> embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        return withRateLimitRetry(() -> model().embed(text), QUERY_MAX_ATTEMPTS, QUERY_INITIAL_BACKOFF_MS);
    }

    public List<float[]> embedBatch(List<String> texts) {
        return withRateLimitRetry(() -> model().embed(texts), INGEST_MAX_ATTEMPTS, INGEST_INITIAL_BACKOFF_MS);
    }

    /** Formats a vector as a pgvector literal, e.g. "[0.12,-0.34,...]". */
    public static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * Retries 429 rate-limit errors with exponential backoff, up to
     * {@code maxAttempts} total attempts starting at {@code initialBackoffMs}
     * and doubling. Other errors propagate immediately.
     */
    private <T> T withRateLimitRetry(java.util.function.Supplier<T> call, int maxAttempts, long initialBackoffMs) {
        long backoff = initialBackoffMs;
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (!isRateLimit(e) || attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("Embedding rate limit hit (attempt {}/{}); waiting {}ms before retry",
                        attempt, maxAttempts, backoff);
                if (!sleepBeforeRetry(backoff)) {
                    throw e; // interrupted — abort retries, preserve the rate-limit error
                }
                backoff *= 2;
            }
        }
    }

    /**
     * Sleeps before the next retry. Returns {@code false} if interrupted (the
     * interrupt flag is restored and the caller should abort). Package-private
     * and overridable so tests can exercise the retry logic without real waits.
     */
    boolean sleepBeforeRetry(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isRateLimit(RuntimeException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("429") || message.contains("rate_limit_exceeded"));
    }

    private EmbeddingModel model() {
        EmbeddingModel model = embeddingModel.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("OpenAI embeddings are not configured. "
                    + "Set OPENAI_API_KEY before ingesting documents or running retrieval.");
        }
        return model;
    }
}
