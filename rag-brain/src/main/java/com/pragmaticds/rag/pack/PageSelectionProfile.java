package com.pragmaticds.rag.pack;

import java.util.List;

/**
 * One named page-selection whitelist, declared in a pack's optional page-selection.yaml.
 * Tells the engine which pages of a multi-page PDF are worth sending to the vision model
 * (e.g. federal tax forms) so the rest never becomes vision tokens. Immutable; travels
 * with the pack so the list can be tuned without a code change.
 *
 * @param name               profile id referenced by AnalyzerConfig.pageSelectionProfile; [a-z0-9-]+
 * @param minPages           PDFs with this many pages or fewer are sent whole (not worth filtering)
 * @param continuationWindow how many following pages to retain after a kept page, for multi-page
 *                           forms whose later pages carry no header. 0 disables continuation keeping.
 * @param keep               a page is KEPT when its text matches any pattern of any rule
 * @param dropHints          markers of a clear non-federal boundary (state return, worksheet);
 *                           they stop continuation keeping. Never force a drop on their own.
 */
public record PageSelectionProfile(
        String name,
        int minPages,
        int continuationWindow,
        List<KeepRule> keep,
        List<String> dropHints
) {
    public PageSelectionProfile {
        keep = keep == null ? List.of() : List.copyOf(keep);
        dropHints = dropHints == null ? List.of() : List.copyOf(dropHints);
    }

    /** One form worth keeping, and the case-insensitive text fragments that identify it. */
    public record KeepRule(String name, List<String> patterns) {
        public KeepRule {
            patterns = patterns == null ? List.of() : List.copyOf(patterns);
        }
    }
}
