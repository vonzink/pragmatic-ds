package com.pragmaticds.docengine.extraction.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads individual capture patterns out of the SHIPPED paystub@1.0.0 seed, so a test can run the
 * real pattern over a hostile input rather than a transcription of it. A regression test written
 * against a copied pattern proves only that the copy behaves — not that what ships does.
 */
public final class SeedPatterns {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SeedPatterns() {}

    /** The {@code value.pattern} of the given field's rung (0-based) in the shipped seed. */
    public static String valuePattern(String fieldName, int rung) {
        try {
            for (JsonNode field : JSON.readTree(V7PaystubSeed.DEFINITION).path("fields")) {
                if (fieldName.equals(field.path("name").asText())) {
                    JsonNode rungs = field.path("extractors");
                    if (rung >= rungs.size()) {
                        throw new IllegalArgumentException(
                                fieldName + " has " + rungs.size() + " rungs, asked for " + rung);
                    }
                    return rungs.get(rung).path("value").path("pattern").asText();
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("shipped seed is not parseable JSON", e);
        }
        throw new IllegalArgumentException("no field " + fieldName + " in the shipped seed");
    }
}
