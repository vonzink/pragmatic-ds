package com.pragmaticds.docengine.extraction.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The narrowing that makes the instance probe safe.
 *
 * <p>The probe re-reads a document's identity page by page. It must do so with the SCHEMA'S OWN
 * extractor ladder — an anchor change in the pack has to change how the period is found here too,
 * automatically — which is why it narrows the real schema rather than carrying a second definition
 * of what a statement period looks like. These pin that the narrowing keeps the authored specs
 * intact and drops everything else, since a projection that quietly rebuilt a field would be the
 * duplicate-implementation problem this design exists to avoid.
 */
class SchemaDefinitionInstanceKeyTest {

    private static FieldSpec field(String name) {
        return new FieldSpec(name, DataType.STRING, false, null, false, List.of());
    }

    private static final FieldSpec START = field("statementPeriodStart");
    private static final FieldSpec END = field("statementPeriodEnd");
    private static final FieldSpec BALANCE = field("beginningBalance");

    @Test
    void the_projection_keeps_the_key_fields_by_identity_and_drops_the_rest() {
        SchemaDefinition schema =
                new SchemaDefinition(
                        "BANK_STATEMENT",
                        "1.3.0",
                        List.of(START, END, BALANCE),
                        List.of("statementPeriodStart", "statementPeriodEnd"));

        SchemaDefinition probe = schema.instanceKeyProjection();

        // Same objects, not rebuilt ones: the authored extractor ladder travels intact.
        assertThat(probe.fields()).containsExactly(START, END);
        assertThat(probe.instanceKey()).containsExactly("statementPeriodStart", "statementPeriodEnd");
        assertThat(probe.documentTypeCode()).isEqualTo("BANK_STATEMENT");
        assertThat(probe.version()).isEqualTo("1.3.0");
    }

    @Test
    void a_schema_declaring_no_key_projects_to_nothing_and_the_probe_does_nothing() {
        SchemaDefinition schema =
                new SchemaDefinition("PAYSTUB", "1.0.0", List.of(START, BALANCE));

        assertThat(schema.instanceKey()).isEmpty();
        assertThat(schema.instanceKeyProjection().fields()).isEmpty();
    }

    @Test
    void the_pre_phase_c_constructor_declares_no_key() {
        // Every schema authored before V25 parses through this shape, and the mechanism must stay
        // inert for all of them.
        assertThat(new SchemaDefinition("W2", "1.1.0", List.of(BALANCE)).instanceKey()).isEmpty();
    }
}
