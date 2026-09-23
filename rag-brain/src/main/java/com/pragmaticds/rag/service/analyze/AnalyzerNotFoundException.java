package com.pragmaticds.rag.service.analyze;

/** No analyzer with the given slug is defined in the brain's pack → maps to 404. */
public class AnalyzerNotFoundException extends RuntimeException {
    public AnalyzerNotFoundException(String slug) {
        super("No analyzer '" + slug + "' defined for this brain");
    }
}
