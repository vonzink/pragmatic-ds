package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.ValueScope;
import com.pragmaticds.docengine.extraction.schema.ValueSpec;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The value rung's next-line join (bank statement field rules, task 4): a joint account holder's
 * second name prints on the line below the first ({@code OR DIEGO R LOPEZ}), and the field must
 * hold both names, with both lines' spans as evidence.
 */
class JoinNextLineTest {
    private static final String NAME =
            "(?<![A-Za-z])[A-Z][A-Z'.-]+(?: [A-Z][A-Z'.-]*){1,4}(?: (?:JR|SR|II|III|IV))?(?= To Contact)";
    private static final String JOIN = "^(?:OR|AND) ([A-Z][A-Z'.-]+(?: [A-Z][A-Z'.-]*){1,4})$";
    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    private static SpanRef span(long id, String text, String x, String y, String w, String h) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal(h)),
                BigDecimal.ONE);
    }

    private static PageContent page(SpanRef... spans) {
        return new PageContent(UUID.randomUUID(), 0, List.of(spans), List.of());
    }

    private static SchemaDefinition schema(FieldSpec... fields) {
        return new SchemaDefinition("BANK_STATEMENT", "1.0.0", List.of(fields));
    }

    private static FieldOutcome only(List<FieldOutcome> outcomes) {
        assertThat(outcomes).hasSize(1);
        return outcomes.get(0);
    }

    private static ExtractorSpec regexJoin(String pattern, String join) {
        return new ExtractorSpec(ExtractionMethod.REGEX, 0.6, null, null,
                new ValueSpec(pattern, 0, ValueScope.PAGE, 0, join));
    }

    private static FieldSpec holder(ExtractorSpec... rungs) {
        return new FieldSpec("accountHolderName", DataType.STRING, true, "personName", false, List.of(rungs));
    }

    @Test
    void a_joint_holder_on_the_next_line_is_joined() {
        PageContent page = page(
                span(1, "MARIA", "40", "200", "28", "9"), span(2, "T", "72", "200", "8", "9"),
                span(3, "LOPEZ", "84", "200", "40", "9"), span(4, "JR", "128", "200", "12", "9"),
                span(5, "To", "300", "200", "12", "9"), span(6, "Contact", "316", "200", "38", "9"),
                span(7, "OR", "40", "212", "14", "9"), span(8, "DIEGO", "58", "212", "32", "9"),
                span(9, "R", "94", "212", "8", "9"), span(10, "LOPEZ", "106", "212", "40", "9"),
                span(11, "2201", "40", "224", "30", "9"), span(12, "ELM", "74", "224", "70", "9"));
        FieldOutcome outcome = only(engine.extract(schema(holder(regexJoin(NAME, JOIN))), List.of(page)));
        assertThat(outcome.displayedText()).isEqualTo("MARIA T LOPEZ JR OR DIEGO R LOPEZ");
        assertThat(outcome.valueEvidence()).extracting(EvidenceRef::spanId)
                .containsExactly(1L, 2L, 3L, 4L, 7L, 8L, 9L, 10L);
    }

    @Test
    void an_address_line_is_not_joined() {
        PageContent page = page(
                span(1, "MARIA", "40", "200", "28", "9"), span(2, "LOPEZ", "72", "200", "40", "9"),
                span(3, "To", "300", "200", "12", "9"), span(4, "Contact", "316", "200", "38", "9"),
                span(5, "2201", "40", "212", "26", "9"), span(6, "ELM", "70", "212", "38", "9"),
                span(7, "ST", "112", "212", "12", "9"));
        FieldOutcome outcome = only(engine.extract(schema(holder(regexJoin(NAME, JOIN))), List.of(page)));
        assertThat(outcome.displayedText()).isEqualTo("MARIA LOPEZ");
        assertThat(outcome.valueEvidence()).hasSize(2);
    }

    @Test
    void only_one_line_is_ever_joined() {
        PageContent page = page(
                span(1, "MARIA", "40", "200", "28", "9"), span(2, "LOPEZ", "72", "200", "40", "9"),
                span(3, "To", "300", "200", "12", "9"), span(4, "Contact", "316", "200", "38", "9"),
                span(5, "OR", "40", "212", "14", "9"), span(6, "DIEGO", "58", "212", "28", "9"), span(7, "LOPEZ", "90", "212", "40", "9"),
                span(8, "AND", "40", "224", "20", "9"), span(9, "ARJUN", "64", "224", "20", "9"), span(10, "LOPEZ", "88", "224", "40", "9"));
        FieldOutcome outcome = only(engine.extract(schema(holder(regexJoin(NAME, JOIN))), List.of(page)));
        assertThat(outcome.displayedText()).isEqualTo("MARIA LOPEZ OR DIEGO LOPEZ");
    }

    @Test
    void a_value_on_the_last_line_stands_alone() {
        PageContent page = page(
                span(1, "MARIA", "40", "200", "28", "9"), span(2, "LOPEZ", "72", "200", "40", "9"),
                span(3, "To", "300", "200", "12", "9"), span(4, "Contact", "316", "200", "38", "9"));
        FieldOutcome outcome = only(engine.extract(schema(holder(regexJoin(NAME, JOIN))), List.of(page)));
        assertThat(outcome.displayedText()).isEqualTo("MARIA LOPEZ");
    }
}
