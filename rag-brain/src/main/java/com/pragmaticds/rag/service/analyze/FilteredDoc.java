package com.pragmaticds.rag.service.analyze;

import java.util.List;

/**
 * A document whose pages were trimmed before the model saw it. Surfaced to the caller so
 * the suite can show "kept 9 of 62 pages" and offer a re-run without filtering.
 *
 * @param matchedForms the keep-rule names that matched (e.g. 1040, Schedule C)
 */
public record FilteredDoc(String id, String fileName, int pagesTotal, int pagesKept,
                          List<String> matchedForms) {
    public FilteredDoc {
        matchedForms = matchedForms == null ? List.of() : List.copyOf(matchedForms);
    }
}
