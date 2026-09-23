package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.match.Box;
import com.pragmaticds.docengine.classification.rules.AnchorKind;
import com.pragmaticds.docengine.extraction.schema.DataType;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractorSpec;
import com.pragmaticds.docengine.extraction.schema.FieldSpec;
import com.pragmaticds.docengine.extraction.schema.LabelSpec;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.TableSpec;
import com.pragmaticds.docengine.extraction.schema.ValueScope;
import com.pragmaticds.docengine.extraction.schema.ValueSpec;
import com.pragmaticds.docengine.parsing.domain.LayoutElementType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TABLE_CLUSTER on the paystub grid: the header cell in the topmost row picks the column, the
 * col-0 row-label cell picks the row, the value comes from the crossing cell — with the CELLS'
 * element ids on the evidence, header cell first, row-label cell second.
 */
class TableClusterExtractionTest {

    private static final String MONEY = "\\$?\\d{1,3}(?:,\\d{3})*\\.\\d{2}";

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    private static ExtractorSpec tableRung(String rowLabel, String columnHeader) {
        return new ExtractorSpec(
                ExtractionMethod.TABLE_CLUSTER,
                1.0,
                null,
                new TableSpec(
                        new LabelSpec(AnchorKind.LITERAL, rowLabel),
                        new LabelSpec(AnchorKind.LITERAL, columnHeader)),
                new ValueSpec(MONEY, 0, ValueScope.LINE));
    }

    private static FieldSpec moneyField(String name, ExtractorSpec... rungs) {
        return new FieldSpec(name, DataType.MONEY, true, "money", false, List.of(rungs));
    }

    private static SchemaDefinition schema(FieldSpec... fields) {
        return new SchemaDefinition("PAYSTUB", "1.0.0", List.of(fields));
    }

    @Test
    void current_gross_resolves_the_gross_row_crossing_the_current_column() {
        FieldSpec field = moneyField("currentGrossPay", tableRung("Gross", "Current"));

        FieldOutcome outcome =
                engine.extract(schema(field), List.of(PaystubFixturePage.page())).get(0);

        assertThat(outcome.method()).isEqualTo(ExtractionMethod.TABLE_CLUSTER);
        assertThat(outcome.displayedText()).isEqualTo("4,670.69");
        assertThat(outcome.normalized().number()).isEqualByComparingTo("4670.69");
        // Value evidence: the (4,3) cell's span, stamped with the VALUE CELL's element id.
        assertThat(outcome.valueEvidence()).hasSize(1);
        EvidenceRef value = outcome.valueEvidence().get(0);
        assertThat(value.spanId()).isEqualTo(42L);
        assertThat(value.layoutElementId()).isEqualTo(PaystubFixturePage.cellId(4, 3));
        assertThat(value.box().x()).isEqualByComparingTo("380.0");
        assertThat(value.box().y()).isEqualByComparingTo("262.1");
        // Label evidence: "Current" header cell first, then the "Gross" row-label cell.
        assertThat(outcome.labelEvidence()).hasSize(2);
        EvidenceRef header = outcome.labelEvidence().get(0);
        assertThat(header.spanId()).isEqualTo(22L);
        assertThat(header.layoutElementId()).isEqualTo(PaystubFixturePage.cellId(0, 3));
        assertThat(header.box().x()).isEqualByComparingTo("380.0");
        EvidenceRef rowLabel = outcome.labelEvidence().get(1);
        assertThat(rowLabel.spanId()).isEqualTo(39L);
        assertThat(rowLabel.layoutElementId()).isEqualTo(PaystubFixturePage.cellId(4, 0));
        assertThat(rowLabel.box().x()).isEqualByComparingTo("72.0");
    }

    @Test
    void ytd_gross_resolves_the_gross_row_crossing_the_ytd_column() {
        FieldSpec field = moneyField("ytdGrossPay", tableRung("Gross", "YTD"));

        FieldOutcome outcome =
                engine.extract(schema(field), List.of(PaystubFixturePage.page())).get(0);

        assertThat(outcome.method()).isEqualTo(ExtractionMethod.TABLE_CLUSTER);
        assertThat(outcome.displayedText()).isEqualTo("4,670.69");
        EvidenceRef value = outcome.valueEvidence().get(0);
        assertThat(value.spanId()).isEqualTo(43L);
        assertThat(value.layoutElementId()).isEqualTo(PaystubFixturePage.cellId(4, 4));
        assertThat(value.box().x()).isEqualByComparingTo("490.0");
        assertThat(outcome.labelEvidence().get(0).layoutElementId())
                .isEqualTo(PaystubFixturePage.cellId(0, 4));
        assertThat(outcome.labelEvidence().get(1).layoutElementId())
                .isEqualTo(PaystubFixturePage.cellId(4, 0));
    }

    @Test
    void a_page_with_no_tables_fails_the_rung() {
        FieldSpec field = moneyField("currentGrossPay", tableRung("Gross", "Current"));

        FieldOutcome outcome =
                engine.extract(schema(field), List.of(PaystubFixturePage.pageWithoutTables()))
                        .get(0);

        assertThat(outcome).isEqualTo(FieldOutcome.missing(field));
    }

    @Test
    void a_header_absent_from_the_topmost_row_fails_the_rung() {
        FieldSpec field = moneyField("deductions", tableRung("Gross", "Deductions"));

        FieldOutcome outcome =
                engine.extract(schema(field), List.of(PaystubFixturePage.page())).get(0);

        assertThat(outcome).isEqualTo(FieldOutcome.missing(field));
    }

    @Test
    void a_row_label_absent_from_column_zero_fails_the_rung() {
        FieldSpec field = moneyField("total", tableRung("Total", "Current"));

        FieldOutcome outcome =
                engine.extract(schema(field), List.of(PaystubFixturePage.page())).get(0);

        assertThat(outcome).isEqualTo(FieldOutcome.missing(field));
    }

    @Test
    void tables_are_tried_in_page_order_until_one_resolves() {
        // A decoy grid with the wrong headers sits before the earnings grid.
        SpanRef decoySpan =
                new SpanRef(
                        900L,
                        "Deductions",
                        new Box(
                                new BigDecimal("72.0"),
                                new BigDecimal("400.0"),
                                new BigDecimal("50.0"),
                                new BigDecimal("10.2")),
                        BigDecimal.ONE);
        LayoutNode decoyCell =
                new LayoutNode(
                        UUID.nameUUIDFromBytes("decoy-cell".getBytes()),
                        LayoutElementType.TABLE_CELL,
                        decoySpan.box(),
                        0,
                        0,
                        List.of(decoySpan),
                        List.of());
        LayoutNode decoyRow =
                new LayoutNode(
                        UUID.nameUUIDFromBytes("decoy-row".getBytes()),
                        LayoutElementType.TABLE_ROW,
                        decoyCell.box(),
                        0,
                        null,
                        List.of(),
                        List.of(decoyCell));
        LayoutNode decoyTable =
                new LayoutNode(
                        UUID.nameUUIDFromBytes("decoy-table".getBytes()),
                        LayoutElementType.TABLE,
                        decoyRow.box(),
                        null,
                        null,
                        List.of(),
                        List.of(decoyRow));
        PageContent full = PaystubFixturePage.page();
        PageContent page =
                new PageContent(
                        full.pageId(),
                        full.packagePageIndex(),
                        full.spans(),
                        List.of(decoyTable, full.tables().get(0)));
        FieldSpec field = moneyField("currentGrossPay", tableRung("Gross", "Current"));

        FieldOutcome outcome = engine.extract(schema(field), List.of(page)).get(0);

        assertThat(outcome.method()).isEqualTo(ExtractionMethod.TABLE_CLUSTER);
        assertThat(outcome.valueEvidence().get(0).layoutElementId())
                .isEqualTo(PaystubFixturePage.cellId(4, 3));
    }
}
