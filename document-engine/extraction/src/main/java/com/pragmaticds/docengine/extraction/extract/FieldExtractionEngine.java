package com.pragmaticds.docengine.extraction.extract;

import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import java.util.List;

/**
 * The pure extraction core — the seam between persistence (which projects a logical document's
 * pages into {@link PageContent}) and the extractors. No JPA, no Spring context required.
 *
 * <p>Contract: returns one {@link FieldOutcome} per field OCCURRENCE, in schema order. An
 * ungrouped field has exactly one occurrence with a null {@code groupKey} — the pre-Spec-5a
 * behaviour, unchanged. A field carrying a COLUMN group has exactly one occurrence per declared
 * key, in declared key order, present whether or not that column was filled: an empty column is a
 * MISSING occurrence, never an absent one (design D5). A field carrying a ROW group has one
 * occurrence per ROW the page actually prints inside the declared region, keyed by the row's
 * ordinal zero-padded to two digits and capped by {@code maxRows} — a region with fewer rows than
 * the cap fabricates nothing, and a region that cannot be located at all yields one missing
 * occurrence with a null key, because with no rows there is no ordinal to name.
 *
 * <p>Pages are scanned in document order; the first page where an occurrence's extractor ladder
 * succeeds wins it. Within a page, extractors run in spec order and the first success wins — a
 * schema encodes its own fallback ladder.
 */
public interface FieldExtractionEngine {

    List<FieldOutcome> extract(SchemaDefinition schema, List<PageContent> pages);
}
