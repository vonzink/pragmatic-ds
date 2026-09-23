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
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The end-to-end half of the regex bound: a schema whose pattern cannot finish costs ITS field and
 * nothing else.
 *
 * <p>This is the case {@code POST /v1/extraction-schemas} opened. Authoring validates that a
 * pattern COMPILES, which says nothing about what it costs to RUN, and extraction runs on a shared
 * pool — so before {@code BoundedCharSequence} one org's authored schema could pin a thread and
 * degrade every other org's processing.
 */
class PathologicalPatternIsolationTest {

    /**
     * A shape that genuinely backtracks on Java 21. The textbook ReDoS patterns do not: see
     * {@code BoundedCharSequenceTest} for the measurements.
     */
    private static final String PATHOLOGICAL = "(.*a){20}b";

    private static SpanRef span(long id, String text, String x) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal("100.0"), new BigDecimal("60.0"),
                        new BigDecimal("10.0")),
                new BigDecimal("0.99"));
    }

    private static FieldSpec field(String name, String pattern) {
        return new FieldSpec(
                name,
                DataType.STRING,
                false,
                null,
                false,
                List.of(
                        new ExtractorSpec(
                                ExtractionMethod.REGEX,
                                0.9,
                                null,
                                null,
                                new ValueSpec(pattern, 0, ValueScope.PAGE))));
    }

    @Test
    @DisplayName("a pattern that cannot finish yields a missing field, not a hung document")
    void a_pathological_pattern_costs_only_its_own_field() {
        // A subject long enough that the pathological pattern cannot finish, carrying a value the
        // OTHER field's honest pattern will find.
        PageContent page =
                new PageContent(
                        UUID.randomUUID(),
                        0,
                        List.of(
                                span(1, "a".repeat(120), "72.0"),
                                span(2, "Total 1,234.56", "72.0")),
                        List.of());

        SchemaDefinition schema =
                new SchemaDefinition(
                        "PAYSTUB",
                        "1.0.0",
                        List.of(
                                field("hostile", PATHOLOGICAL),
                                field("honest", "\\d{1,3}(?:,\\d{3})*\\.\\d{2}")));

        long start = System.nanoTime();
        List<FieldOutcome> outcomes = new DefaultFieldExtractionEngine().extract(schema, List.of(page));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // It returned at all, and promptly. Unbounded, this call does not come back.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(20));

        // Every declared field still owes an outcome — the hostile one is a MISSING, which is the
        // honest gap a reviewer already knows how to read, not a silent disappearance.
        assertThat(outcomes).hasSize(2);
        FieldOutcome hostile =
                outcomes.stream().filter(o -> o.field().name().equals("hostile")).findFirst()
                        .orElseThrow();
        FieldOutcome honest =
                outcomes.stream().filter(o -> o.field().name().equals("honest")).findFirst()
                        .orElseThrow();

        assertThat(hostile.rawValue()).isNull();
        // The neighbouring field is untouched: one bad pattern must not suppress the page.
        assertThat(honest.rawValue()).isEqualTo("1,234.56");
    }
}
