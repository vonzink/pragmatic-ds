package com.pragmaticds.rag.lab.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader half of the engine's own coverage gate.
 *
 * <p>The engine runs {@code ConsumerCoverageTableIT} to prove its published contract matches the
 * types its migrations actually seed. Nothing proved the next hop: that the Income Lab accepts the
 * INCOME types the engine serves. It did not. {@code SUPPORTED_DOCUMENT_TYPES} was written on
 * 2026-08-17 against the four income types the engine had then and never revisited, while the engine
 * grew to twenty-two. Because an unsupported document is only <em>warned</em> about
 * ({@code UNSUPPORTED_DOCUMENT_IGNORED}) and never blocks analysis, the gap was silent: a package
 * whose self-employment income lives on a Schedule C analysed cleanly and simply left that income
 * out.
 *
 * <p>{@code engine-contract/income-document-types.json} is a pinned copy of the engine's contract
 * §5 INCOME rows. Refresh it whenever the engine pin moves — that refresh is the moment this drift
 * becomes visible, and this test is what makes it loud.
 */
class EngineIncomeTypeCoverageTest {

    private static final String PIN = "engine-contract/income-document-types.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theIncomeLabAcceptsEveryIncomeTypeTheEngineServes() throws IOException {
        JsonNode pin = readPin();
        Set<String> engineIncomeTypes = incomeTypes(pin);
        Set<String> supported = IncomeEnvelopeCompatibility.SUPPORTED_DOCUMENT_TYPES;

        Set<String> unhandled = new TreeSet<>(engineIncomeTypes);
        unhandled.removeAll(supported);

        assertTrue(
                unhandled.isEmpty(),
                () ->
                        "The engine serves INCOME types the Income Lab silently ignores: "
                                + unhandled
                                + ". Engine pin "
                                + pin.path("engineRef").asText()
                                + " ("
                                + pin.path("source").asText()
                                + " §"
                                + pin.path("sourceSection").asText()
                                + "). An ignored income document does not fail — it drops that"
                                + " income from the analysis. Widen"
                                + " IncomeEnvelopeCompatibility.SUPPORTED_DOCUMENT_TYPES, or"
                                + " deliberately exclude the type and say so here.");
    }

    @Test
    void theIncomeLabClaimsNoTypeTheEngineDoesNotServe() throws IOException {
        JsonNode pin = readPin();
        Set<String> engineIncomeTypes = incomeTypes(pin);

        Set<String> invented = new TreeSet<>(IncomeEnvelopeCompatibility.SUPPORTED_DOCUMENT_TYPES);
        invented.removeAll(engineIncomeTypes);

        assertTrue(
                invented.isEmpty(),
                () ->
                        "The Income Lab accepts types the engine's contract does not list as"
                                + " INCOME: "
                                + invented
                                + ". Either the engine retired them or they were never real; a type"
                                + " the engine cannot produce can never match a document.");
    }

    /** The pin must name the engine revision it was taken from, or it cannot be audited. */
    @Test
    void thePinRecordsTheEngineRevisionItWasTakenFrom() throws IOException {
        JsonNode pin = readPin();
        assertEquals("pds-document-engine", pin.path("engineRepo").asText());
        assertTrue(pin.path("engineRef").asText().length() > 0, "engineRef must be set");
        assertEquals(
                40,
                pin.path("engineCommit").asText().length(),
                "engineCommit must be a full 40-character sha");
    }

    private static Set<String> incomeTypes(JsonNode pin) {
        JsonNode array = pin.path("incomeTypes");
        assertTrue(array.isArray() && !array.isEmpty(), "incomeTypes must be a non-empty array");
        List<String> codes = new ArrayList<>();
        array.forEach(node -> codes.add(node.asText()));
        Set<String> unique = new TreeSet<>(codes);
        assertEquals(codes.size(), unique.size(), "incomeTypes must not repeat a code");
        return unique;
    }

    private static JsonNode readPin() throws IOException {
        try (InputStream in =
                EngineIncomeTypeCoverageTest.class.getClassLoader().getResourceAsStream(PIN)) {
            assertNotNull(in, PIN + " is missing from the test resources");
            return MAPPER.readTree(in);
        }
    }
}
