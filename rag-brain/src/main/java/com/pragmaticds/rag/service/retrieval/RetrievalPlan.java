package com.pragmaticds.rag.service.retrieval;

import java.util.Set;

/**
 * Which indexes a question should hit (spec §7.4). Phase 6 keeps this minimal —
 * just the set of {@link SourceKind} indexes. Weights and page-boost (spec §7.4)
 * are deferred to Phase 7; do not add them until something consumes them.
 *
 * <p>The default plan for an ordinary guideline question (no {@code pageRoute},
 * intent {@code GUIDELINE_QUESTION}) is exactly {@code {CORPUS}} — i.e. today's
 * behavior.
 *
 * @param indexes the indexes to retrieve from; always contains {@link SourceKind#CORPUS}
 */
public record RetrievalPlan(Set<SourceKind> indexes) {

    /** True when {@code kind} is one of the planned indexes. */
    public boolean includes(SourceKind kind) {
        return indexes.contains(kind);
    }
}
