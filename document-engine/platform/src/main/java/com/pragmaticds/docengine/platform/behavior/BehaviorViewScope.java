package com.pragmaticds.docengine.platform.behavior;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The record of WHICH behavior views a single pipeline run actually executed under.
 *
 * <p>The parse-once fingerprint exists to answer one question: would parsing these bytes again,
 * right now, produce what the stored parse produced? A fingerprint can only answer that if it
 * describes the behavior that ACTUALLY RAN. The engine's behavior views are loaded through caches
 * ({@code RulePackLoader}, {@code ExtractionSchemaLoader}), and a cache can differ from the
 * database and can be replaced between one stage and the next, so "what the loaders would return
 * if asked now" is not the same statement as "what the loaders returned while this parse ran".
 * Only the second one is safe to stamp.
 *
 * <p>This scope closes that gap by recording the views AS THEY ARE SERVED. Every loader stamps the
 * identity token of the snapshot it hands to the pipeline; at the end of the run the finalizer
 * composes the fingerprint from those recorded tokens and refuses to stamp anything it cannot
 * account for. Three refusals are built in, and every one of them means NO fingerprint, which
 * means no reuse — the deliberate direction, because a missing reuse hit costs work while a wrong
 * one costs a wrong answer:
 *
 * <ul>
 *   <li>a view that was never recorded (that loader was never consulted, so nothing can vouch for
 *       what it would have said);
 *   <li>a view recorded TWICE with different tokens (the cache was replaced mid-run, so the run
 *       itself is a blend of two behaviors); and
 *   <li>a recorded token that no longer matches the loader's current snapshot at composition time
 *       — checked by the composer, not here.
 * </ul>
 *
 * <p>Thread confinement is deliberate. The pipeline runs one job on one executor thread
 * ({@code JobService.submit} → {@code StageRunner.run}), and the loaders are consulted from that
 * thread inside CLASSIFYING and EXTRACTING. A record from any other thread finds no open scope and
 * is a silent no-op — which fails toward "nothing recorded", i.e. toward no stamp. Upload-time
 * fingerprint computation runs with no scope open at all, for the same reason: it is a PREDICTION
 * used to match candidates, never a claim about a completed parse.
 */
public final class BehaviorViewScope {

    /** The behavior views a parse's output depends on. Adding a view means adding it here. */
    public enum ViewKind {
        /** The org's winning classification rule packs — what decides every page's type. */
        CLASSIFICATION_PACKS,
        /** The org's winning extraction schemas — what decides every field and how it is found. */
        EXTRACTION_SCHEMAS
    }

    private static final ThreadLocal<Recording> ACTIVE = new ThreadLocal<>();

    private BehaviorViewScope() {}

    private static final class Recording {
        private final Map<ViewKind, String> tokens = new EnumMap<>(ViewKind.class);
        private final Set<ViewKind> conflicted = EnumSet.noneOf(ViewKind.class);
    }

    /**
     * Opens a recording scope for one pipeline run on this thread. Closing it is mandatory — the
     * runner does it in a try-with-resources, so a crashed run cannot leak a scope onto a pooled
     * thread and let the NEXT job inherit another job's recorded views.
     *
     * <p>A nested open would silently discard the outer run's records, so it is rejected outright
     * rather than papered over.
     */
    public static Scope open() {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("a behavior view scope is already open on this thread");
        }
        ACTIVE.set(new Recording());
        return ACTIVE::remove;
    }

    /** The handle returned by {@link #open()}; closing ends the run's recording. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Records that {@code token} is the view this run is executing under. No-op outside a scope.
     * A second, DIFFERENT token for the same kind marks the kind conflicted permanently: the run
     * saw two behaviors and no single token describes it.
     */
    public static void record(ViewKind kind, String token) {
        Recording recording = ACTIVE.get();
        if (recording == null || token == null) {
            return;
        }
        String existing = recording.tokens.putIfAbsent(kind, token);
        if (existing != null && !existing.equals(token)) {
            recording.conflicted.add(kind);
        }
    }

    /**
     * The single token this run executed under for {@code kind}, or empty when there is no scope,
     * nothing was recorded, or the run recorded two different views.
     */
    public static Optional<String> recorded(ViewKind kind) {
        Recording recording = ACTIVE.get();
        if (recording == null || recording.conflicted.contains(kind)) {
            return Optional.empty();
        }
        return Optional.ofNullable(recording.tokens.get(kind));
    }
}
