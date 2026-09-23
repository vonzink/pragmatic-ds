package com.pragmaticds.docengine.platform.ai;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;

/** The canonical paystub structured-output schema and its strict validator. */
public final class PaystubExtractionSchema {

    private static final String RESOURCE = "/ai/schema/paystub.schema.json";

    private final String schemaJson;
    private final Schema schema;

    public PaystubExtractionSchema() {
        this.schemaJson = AiExtractionResourceLoader.read(RESOURCE);
        SchemaRegistry registry =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        this.schema = registry.getSchema(schemaJson);
        this.schema.initializeValidators();
    }

    public String schemaJson() {
        return schemaJson;
    }

    public List<String> validationErrors(String structuredJson) {
        if (structuredJson == null || structuredJson.isBlank()) {
            return List.of("invalid_json");
        }
        try {
            return schema.validate(structuredJson, InputFormat.JSON).stream()
                    .map(error -> error.toString())
                    .toList();
        } catch (RuntimeException invalidJson) {
            return List.of("invalid_json");
        }
    }
}
