package com.pragmaticds.docengine.reuse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Live {@code GET /version} probe of the parser worker, for the parse-once behavior fingerprint.
 *
 * <p>Deliberately its OWN tiny client rather than an edit to {@code parsing/client/WorkerClient}
 * (a concurrent branch owns that file): same base-url and shared-secret configuration, a short
 * timeout so the upload path never hangs on it, and a small TTL cache so repeated uploads do not
 * re-probe. WORKER_CONTRACT.md blesses {@code /version} as the source for parser-version
 * stamping; here it names the worker behavior a parse would run under.
 *
 * <p>Failure is an ANSWER, not an error: an unreachable or malformed worker yields
 * {@link Optional#empty()}, the fingerprint becomes unavailable, and the upload parses normally —
 * fail-open-to-parse, never fail-the-upload.
 *
 * <p><b>The TTL and worker upgrades.</b> The cache serves BOTH the upload-time prediction and the
 * FINALIZING stamp, so a worker upgraded inside the window is invisible to both together. This is
 * NOT a stamp-integrity hole: the stamp is separately cross-checked against the worker version each
 * SUCCEEDED stage row actually recorded ({@code StageRunner.workerVersionsUsedBy} vs the probe), so
 * a run whose stages executed under a newer worker than the cache believes is left unstamped, never
 * stamped wrongly — the cross-check is sufficient THERE regardless of TTL. What the cross-check does
 * NOT cover is the SERVE path: the upload prediction has no stage rows to check against, so for up
 * to the TTL after an upgrade a same-bytes re-upload could match a pre-upgrade stamp and be served a
 * result the current worker might not reproduce. Bypassing the cache on the stamp path would not
 * touch that (the exposure is on the prediction), so the only lever is the TTL. The cache exists to
 * coalesce a BURST of near-simultaneous uploads into one probe (sub-second to a few seconds apart);
 * the default is therefore kept short (15s) — long enough for that, short enough to bound the
 * post-upgrade stale-serve window. Full closure would need a worker-upgrade cache-invalidation
 * signal, which does not exist yet.
 */
@Component
public class WorkerVersionProbe {

    private static final Logger log = LoggerFactory.getLogger(WorkerVersionProbe.class);

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration cacheTtl;
    private final AtomicReference<CachedVersion> cache = new AtomicReference<>();

    private record CachedVersion(JsonNode body, Instant fetchedAt) {}

    public WorkerVersionProbe(
            @Value("${docengine.worker.base-url:http://localhost:9091}") String baseUrl,
            @Value("${docengine.worker.shared-secret:}") String sharedSecret,
            @Value("${docengine.reuse.worker-probe-timeout-seconds:5}") long timeoutSeconds,
            @Value("${docengine.reuse.worker-probe-cache-seconds:15}") long cacheSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (sharedSecret != null && !sharedSecret.isBlank()) {
            builder.defaultHeader("X-Worker-Secret", sharedSecret);
        }
        this.client = builder.build();
        this.cacheTtl = Duration.ofSeconds(cacheSeconds);
    }

    /** The parsed {@code /version} body, cached briefly; empty on any failure. */
    public Optional<JsonNode> version() {
        CachedVersion cached = cache.get();
        if (cached != null && cached.fetchedAt().plus(cacheTtl).isAfter(Instant.now())) {
            return Optional.of(cached.body());
        }
        try {
            String body = client.get().uri("/version").retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                return Optional.empty();
            }
            JsonNode parsed = mapper.readTree(body);
            cache.set(new CachedVersion(parsed, Instant.now()));
            return Optional.of(parsed);
        } catch (Exception unavailable) {
            // Class name only: the exception may quote a response body.
            log.debug(
                    "worker /version probe failed exception={}",
                    unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
