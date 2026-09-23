package com.pragmaticds.docengine.platform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BankStatementExtractionSchemaTest {

    private final BankStatementExtractionSchema schema = new BankStatementExtractionSchema();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "good-clean-multipage.json",
                "good-missing-summary-field.json",
                "good-negative-and-parens-money.json",
                "malformed-unparseable-money.json"
            })
    void shipped_schema_accepts_structurally_valid_golden_responses(String fixture)
            throws IOException {
        assertThat(schema.validationErrors(fixture(fixture))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "malformed-extra-property.json",
                "malformed-missing-required-property.json",
                "malformed-wrong-type.json"
            })
    void shipped_schema_rejects_structural_deviations(String fixture) throws IOException {
        assertThat(schema.validationErrors(fixture(fixture))).isNotEmpty();
    }

    @Test
    void cacheable_synthetic_few_shot_conforms_to_the_shipped_schema() throws IOException {
        assertThat(
                        schema.validationErrors(
                                resource("/ai/prompt/bank-statement.few-shot.json")))
                .isEmpty();
    }

    private static String fixture(String name) throws IOException {
        return resource("/ai/golden/" + name);
    }

    private static String resource(String path) throws IOException {
        try (InputStream input =
                BankStatementExtractionSchemaTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing synthetic fixture: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
