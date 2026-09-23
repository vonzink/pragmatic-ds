package com.pragmaticds.rag.service.retrieval;

/**
 * Trust tier used to order evidence (spec §6.4, D6). Lower {@link #rank()} = higher
 * authority. The five tiers, in authority order:
 * <ol>
 *   <li>{@link #COMPANY_RULE} — company rule / internal policy
 *       ({@code brain_documents sourceType=INTERNAL_POLICY} + live Rules).</li>
 *   <li>{@link #CURRENT_PAGE_GUIDE} — the route/topic-matched page guide.</li>
 *   <li>{@link #PRIMARY_EXTERNAL} — primary external source
 *       ({@code brain_source_links authority=PRIMARY}: agency guides, HUD).</li>
 *   <li>{@link #SECONDARY_EXTERNAL} — approved secondary source ({@code authority=SECONDARY}).</li>
 *   <li>{@link #BACKGROUND} — general background / context ({@code authority=BACKGROUND}).</li>
 * </ol>
 *
 * <p><b>Why an explicit {@code rank} field (not {@code ordinal()}):</b> spec §6.4
 * numbers the tiers 1–5; encoding the rank as data keeps the sort key decoupled
 * from enum declaration order and reads self-documenting. {@code rank()} is the
 * sort key used by {@link AuthorityFilterService#order}.
 *
 * <p>This lives as a code constant first (D6); it is promotable to an editable
 * {@code authority.yaml} / {@code brain_settings} later without changing the
 * filter's interface.
 */
public enum AuthorityTier {

    /** Company rule / internal policy — highest authority. */
    COMPANY_RULE(1),

    /** The current route/topic-matched page guide. */
    CURRENT_PAGE_GUIDE(2),

    /** Primary external source (agency selling/servicing guides, HUD handbook). */
    PRIMARY_EXTERNAL(3),

    /** Approved supporting/secondary external source. */
    SECONDARY_EXTERNAL(4),

    /** General background / context only — lowest authority. */
    BACKGROUND(5);

    private final int rank;

    AuthorityTier(int rank) {
        this.rank = rank;
    }

    /** Sort key — lower = higher authority (spec §6.4). */
    public int rank() {
        return rank;
    }
}
