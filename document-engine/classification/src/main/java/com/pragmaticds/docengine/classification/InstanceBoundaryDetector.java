package com.pragmaticds.docengine.classification;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Does this run of same-type pages actually hold MORE THAN ONE document?
 *
 * <p>The splitter cuts where the classified type changes and where a pack anchor declares a form
 * header. Neither can see the commonest same-type seam in a mortgage package: three consecutive
 * monthly bank statements for one account. Every page classifies {@code BANK_STATEMENT}, no type
 * changes, and no statement prints a header anchor distinguishable from its neighbours' — so all
 * three merge into one document, which then reports ONE beginning balance where there should be
 * three and fails an arithmetic reconciliation it was never wrong about.
 *
 * <p>What CAN tell them apart is the document's own printed identity — the statement period. This
 * port asks that question without {@code classification} learning how to answer it: the answer
 * needs extraction schemas and the extractor ladder, and {@code extraction} depends on
 * {@code classification}, never the reverse (ARCHITECTURE.md 2.1). Same seam, same direction, as
 * {@code ParserPort}.
 *
 * <p><b>Deterministic by construction.</b> The implementation reads values the engine already
 * knows how to read, with the ladder the schema already declares. No model, no inference, no
 * threshold beyond the extractor's own — which is why this runs inside SPLITTING rather than
 * behind a gate, and why re-running SPLITTING converges on the same cut instead of needing the
 * proposal persisted (the discipline Phase D's boundary extraction WILL need, because a model
 * call must never be repeated to reproduce a split).
 */
public interface InstanceBoundaryDetector {

    /** The no-op every context gets until {@code extraction} contributes the real one. */
    InstanceBoundaryDetector NONE = (documentTypeCode, orderedPageIds) -> Set.of();

    /**
     * Which of these pages BEGIN a new instance of the same document type?
     *
     * <p>Called with one candidate document's pages, in document order, after the type-and-anchor
     * grouping has run. The FIRST page is never returned — it already begins its document, and
     * returning it would cut a document off from itself.
     *
     * <p>Returns empty for a type that declares no instance key, for a run whose pages all carry
     * the same key, and for anything it cannot read. Never throws: a detector that fails leaves
     * the split exactly as the deterministic type-and-anchor pass made it.
     *
     * @param documentTypeCode the run's type, which selects the schema that declares the key
     * @param orderedPageIds the run's pages in document order
     */
    Set<UUID> pagesStartingNewInstance(String documentTypeCode, List<UUID> orderedPageIds);
}
