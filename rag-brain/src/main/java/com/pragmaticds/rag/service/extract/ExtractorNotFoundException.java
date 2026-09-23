package com.pragmaticds.rag.service.extract;

/** No extractor with the given slug is defined in the brain's pack → maps to 404. */
public class ExtractorNotFoundException extends RuntimeException {
    public ExtractorNotFoundException(String slug) {
        super("No extractor '" + slug + "' defined for this brain");
    }
}
