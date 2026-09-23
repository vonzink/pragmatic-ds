package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.GroupKind;
import com.pragmaticds.docengine.extraction.schema.GroupSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.ShippedPaystubSeed;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The paystub schema THAT SHIPS after V53 — {@code paystub@1.5.0} — read out of the migration by
 * {@link ShippedPaystubSeed} and parsed by the real {@link ExtractionSchemaLoader}, so what is
 * pinned here is the definition production loads, never a transcription.
 *
 * <p>Three properties matter. The eight new fields are DECLARED (the loader accepts them, with
 * their {@code AI} rungs and the earnings ROW group). They are AI-only: deterministically every one
 * of them is an honest MISSING, the five line fields as a single null-keyed occurrence each,
 * because no member can address a row and a frame needs one. And the ten fields that were already
 * there are exactly the ten that were there — the bump adds, it never edits.
 */
class ShippedPaystubEarningsLinesTest {

    private static final List<String> LEGACY_TEN =
            List.of(
                    "borrowerName",
                    "employerName",
                    "payPeriodStart",
                    "payPeriodEnd",
                    "payDate",
                    "payFrequency",
                    "currentGrossPay",
                    "ytdGrossPay",
                    "netPay",
                    "federalWithholding");

    private static final List<String> TOTALS =
            List.of("currentTotalDeductions", "ytdTotalDeductions", "ytdNetPay");

    private static final List<String> LINE_FIELDS =
            List.of(
                    "earningDescription",
                    "earningHours",
                    "earningRate",
                    "earningCurrentAmount",
                    "earningYtdAmount");

    private static final SchemaDefinition SHIPPED =
            new ExtractionSchemaLoader(null)
                    .parseAuthored(
                            "PAYSTUB", ShippedPaystubSeed.VERSION, ShippedPaystubSeed.DEFINITION);

    private static Map<String, FieldSpec> byName() {
        return SHIPPED.fields().stream()
                .collect(Collectors.toMap(FieldSpec::name, spec -> spec, (a, b) -> a));
    }

    @Test
    void the_shipped_seed_is_1_5_0_with_the_ten_legacy_fields_first_and_eight_new_ones() {
        assertThat(ShippedPaystubSeed.VERSION).isEqualTo("1.5.0");
        List<String> names = SHIPPED.fields().stream().map(FieldSpec::name).toList();
        assertThat(names).hasSize(18);
        assertThat(names.subList(0, 10)).containsExactlyElementsOf(LEGACY_TEN);
        assertThat(names.subList(10, 18))
                .containsExactly(
                        "currentTotalDeductions",
                        "ytdTotalDeductions",
                        "ytdNetPay",
                        "earningDescription",
                        "earningHours",
                        "earningRate",
                        "earningCurrentAmount",
                        "earningYtdAmount");
    }

    @Test
    void every_new_field_is_optional_not_sensitive_and_read_only_by_the_ai_rung() {
        Map<String, FieldSpec> fields = byName();
        for (String name : List.of(TOTALS, LINE_FIELDS).stream().flatMap(List::stream).toList()) {
            FieldSpec field = fields.get(name);
            assertThat(field).as(name).isNotNull();
            assertThat(field.required()).as("%s required", name).isFalse();
            assertThat(field.sensitive()).as("%s sensitive", name).isFalse();
            assertThat(field.extractors())
                    .as("%s rungs", name)
                    .isNotEmpty()
                    .allSatisfy(rung -> assertThat(rung.method()).isEqualTo(ExtractionMethod.AI));
        }
        assertThat(fields.get("earningDescription").dataType()).isEqualTo(DataType.STRING);
        for (String money :
                List.of(
                        "earningHours",
                        "earningRate",
                        "earningCurrentAmount",
                        "earningYtdAmount",
                        "currentTotalDeductions",
                        "ytdTotalDeductions",
                        "ytdNetPay")) {
            assertThat(fields.get(money).dataType()).as(money).isEqualTo(DataType.MONEY);
        }
    }

    @Test
    void the_totals_are_ungrouped_and_the_five_line_fields_share_one_row_group() {
        Map<String, FieldSpec> fields = byName();
        for (String total : TOTALS) {
            assertThat(fields.get(total).group()).as("%s group", total).isNull();
        }
        Set<GroupSpec> groups =
                LINE_FIELDS.stream().map(name -> fields.get(name).group()).collect(Collectors.toSet());
        // One region, one cap: the loader would refuse two caps over one region, and the stage
        // service's PAYSTUB_EARNINGS_MAX_ROWS is this number (AiPaystubExtractionIT pins the pair).
        assertThat(groups).hasSize(1);
        GroupSpec group = groups.iterator().next();
        assertThat(group.kind()).isEqualTo(GroupKind.ROW);
        assertThat(group.maxRows()).isEqualTo(40);
        assertThat(group.rowLabels()).isNull();
        assertThat(group.region()).isNotNull();
    }

    /**
     * What the deterministic reader does with them on a page that prints a real earnings block:
     * nothing it could be wrong about. The scalars are ungrouped MISSING; each line field is ONE
     * MISSING occurrence with a NULL key — the region-not-read shape — because no member declares
     * a ROW_CELL column header and the frame needs one. Keyed MISSING rows would be a claim about
     * rows nobody read.
     */
    @Test
    void deterministically_every_new_field_is_missing_and_the_line_fields_carry_no_key() {
        List<FieldOutcome> outcomes =
                new DefaultFieldExtractionEngine().extract(SHIPPED, List.of(earningsBlock()));
        Map<String, List<FieldOutcome>> byField =
                outcomes.stream().collect(Collectors.groupingBy(outcome -> outcome.field().name()));
        for (String name : List.of(TOTALS, LINE_FIELDS).stream().flatMap(List::stream).toList()) {
            assertThat(byField.get(name)).as("%s outcomes", name).hasSize(1);
            FieldOutcome outcome = byField.get(name).get(0);
            assertThat(outcome.method()).as("%s method", name).isEqualTo(ExtractionMethod.NONE);
            assertThat(outcome.groupKey()).as("%s key", name).isNull();
        }
        assertThat(outcomes).hasSize(18);
    }

    private static SpanRef span(long id, String text, String x, String y, String w) {
        return new SpanRef(
                id,
                text,
                new Box(new BigDecimal(x), new BigDecimal(y), new BigDecimal(w), new BigDecimal("8.0")),
                BigDecimal.ONE);
    }

    /** A stub's earnings block: caption row, two lines, a total — the region's own anchors. */
    private static PageContent earningsBlock() {
        return new PageContent(
                UUID.randomUUID(),
                0,
                List.of(
                        span(1, "Earnings", "40.0", "100.0", "31.0"),
                        span(2, "Description", "40.0", "112.0", "40.0"),
                        span(3, "Hours", "150.0", "112.0", "20.0"),
                        span(4, "Rate", "200.0", "112.0", "16.0"),
                        span(5, "Current", "250.0", "112.0", "28.0"),
                        span(6, "YTD", "320.0", "112.0", "14.0"),
                        span(7, "Regular", "40.0", "124.0", "28.0"),
                        span(8, "80.00", "150.0", "124.0", "20.0"),
                        span(9, "25.00", "200.0", "124.0", "20.0"),
                        span(10, "2,000.00", "250.0", "124.0", "32.0"),
                        span(11, "46,000.00", "320.0", "124.0", "36.0"),
                        span(12, "Overtime", "40.0", "136.0", "30.0"),
                        span(13, "0.00", "250.0", "136.0", "16.0"),
                        span(14, "1,000.00", "320.0", "136.0", "24.0"),
                        span(15, "Total", "40.0", "148.0", "20.0"),
                        span(16, "2,000.00", "250.0", "148.0", "32.0"),
                        span(17, "47,000.00", "320.0", "148.0", "36.0")),
                List.of(),
                List.of());
    }
}
