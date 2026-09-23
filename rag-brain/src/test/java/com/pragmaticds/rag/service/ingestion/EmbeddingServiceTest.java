package com.pragmaticds.rag.service.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the split retry policy: the interactive {@link EmbeddingService#embed}
 * query path retries at most once (so a provider 429 cannot pin request threads),
 * while {@link EmbeddingService#embedBatch} ingestion keeps the long backoff, and
 * non-rate-limit errors propagate immediately with no wait.
 */
class EmbeddingServiceTest {

    /** Counts sleeps and never actually waits, so the retry logic runs instantly. */
    private static final class NoSleepEmbeddingService extends EmbeddingService {
        int sleeps = 0;

        NoSleepEmbeddingService(ObjectProvider<EmbeddingModel> provider) {
            super(provider);
        }

        @Override
        boolean sleepBeforeRetry(long millis) {
            sleeps++;
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EmbeddingModel> providerFor(EmbeddingModel model) {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return provider;
    }

    private static RuntimeException rateLimit() {
        return new RuntimeException("HTTP 429 Too Many Requests");
    }

    @Test
    void queryPathRetriesAtMostOnceThenFailsFast() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        AtomicInteger calls = new AtomicInteger();
        when(model.embed("q")).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw rateLimit();
        });
        NoSleepEmbeddingService service = new NoSleepEmbeddingService(providerFor(model));

        assertThrows(RuntimeException.class, () -> service.embed("q"));

        assertEquals(2, calls.get(), "query path should attempt exactly twice (one retry)");
        assertEquals(1, service.sleeps, "query path should sleep at most once");
    }

    @Test
    void queryPathSucceedsAfterOneRetry() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        float[] vector = {0.1f, 0.2f};
        AtomicInteger calls = new AtomicInteger();
        when(model.embed("q")).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw rateLimit();
            }
            return vector;
        });
        NoSleepEmbeddingService service = new NoSleepEmbeddingService(providerFor(model));

        assertArrayEquals(vector, service.embed("q"));
        assertEquals(2, calls.get());
        assertEquals(1, service.sleeps);
    }

    @Test
    void nonRateLimitErrorPropagatesImmediatelyWithoutRetry() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        RuntimeException boom = new RuntimeException("500 internal error");
        when(model.embed("q")).thenThrow(boom);
        NoSleepEmbeddingService service = new NoSleepEmbeddingService(providerFor(model));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.embed("q"));

        assertSame(boom, thrown);
        assertEquals(0, service.sleeps, "non-429 errors must not trigger a retry/sleep");
    }

    @Test
    void ingestionPathRetriesUpToSixAttempts() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        List<String> texts = List.of("a", "b");
        AtomicInteger calls = new AtomicInteger();
        when(model.embed(texts)).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new RuntimeException("rate_limit_exceeded");
        });
        NoSleepEmbeddingService service = new NoSleepEmbeddingService(providerFor(model));

        assertThrows(RuntimeException.class, () -> service.embedBatch(texts));

        assertEquals(6, calls.get(), "ingestion path should attempt six times");
        assertEquals(5, service.sleeps, "ingestion path should back off five times");
    }
}
