package com.pragmaticds.docengine.extraction.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.pragmaticds.docengine.extraction.domain.ExtractionSchema;
import com.pragmaticds.docengine.extraction.repo.ExtractionSchemaRepository;
import com.pragmaticds.docengine.extraction.schema.ExtractionMethod;
import com.pragmaticds.docengine.extraction.schema.ExtractionSchemaLoader;
import com.pragmaticds.docengine.extraction.schema.SchemaDefinition;
import com.pragmaticds.docengine.extraction.schema.V7PaystubSeed;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The whole pure core end to end: the REAL V7 paystub@1.0.0 seed parsed by the real loader, run
 * by the real engine over the paystub_complete fixture page rebuilt word-for-word — all ten
 * fields, exactly the truth file's methods, displayed texts, and normalized values.
 */
@ExtendWith(MockitoExtension.class)
class PaystubFixtureEndToEndTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock private ExtractionSchemaRepository schemas;

    private final DefaultFieldExtractionEngine engine = new DefaultFieldExtractionEngine();

    @BeforeEach
    void setUp() {
        TenantContext.set(ORG);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void extracts_all_ten_paystub_fields_from_the_complete_fixture() {
        when(schemas.findActiveVisibleTo(ORG))
                .thenReturn(
                        List.of(
                                new ExtractionSchema(
                                        null, "PAYSTUB", "1.0.0", V7PaystubSeed.DEFINITION, true)));
        SchemaDefinition schema =
                new ExtractionSchemaLoader(schemas).activeSchemaFor("PAYSTUB").orElseThrow();

        List<FieldOutcome> outcomes =
                engine.extract(schema, List.of(PaystubFixturePage.page()));

        assertThat(outcomes).hasSize(10);
        assertThat(outcomes)
                .extracting(o -> o.field().name(), FieldOutcome::method, FieldOutcome::displayedText)
                .containsExactly(
                        tuple("borrowerName", ExtractionMethod.ANCHOR_LABEL, "Jordan Q. Fixture"),
                        tuple("employerName", ExtractionMethod.REGEX, "ACME WIDGETS LLC"),
                        tuple("payPeriodStart", ExtractionMethod.ANCHOR_LABEL, "01/01/2026"),
                        tuple("payPeriodEnd", ExtractionMethod.ANCHOR_LABEL, "01/15/2026"),
                        tuple("payDate", ExtractionMethod.ANCHOR_LABEL, "01/17/2026"),
                        tuple("payFrequency", ExtractionMethod.ANCHOR_LABEL, "Bi-Weekly"),
                        tuple("currentGrossPay", ExtractionMethod.TABLE_CLUSTER, "4,670.69"),
                        tuple("ytdGrossPay", ExtractionMethod.TABLE_CLUSTER, "4,670.69"),
                        tuple("netPay", ExtractionMethod.ANCHOR_LABEL, "$3,565.87"),
                        tuple("federalWithholding", ExtractionMethod.ANCHOR_LABEL, "612.44"));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.found()).isTrue());
        assertThat(outcomes)
                .allSatisfy(outcome -> assertThat(outcome.pageId()).isEqualTo(PaystubFixturePage.PAGE_ID));

        assertThat(outcomes.get(0).normalized().text()).isEqualTo("Jordan Q. Fixture");
        assertThat(outcomes.get(1).normalized().text()).isEqualTo("ACME WIDGETS LLC");
        assertThat(outcomes.get(2).normalized().date()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(outcomes.get(3).normalized().date()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(outcomes.get(4).normalized().date()).isEqualTo(LocalDate.of(2026, 1, 17));
        assertThat(outcomes.get(5).normalized().text()).isEqualTo("BIWEEKLY");
        assertThat(outcomes.get(6).normalized().number()).isEqualByComparingTo("4670.69");
        assertThat(outcomes.get(7).normalized().number()).isEqualByComparingTo("4670.69");
        assertThat(outcomes.get(8).normalized().number()).isEqualByComparingTo("3565.87");
        assertThat(outcomes.get(9).normalized().number()).isEqualByComparingTo("612.44");

        // Spot-check the traceability spine against the truth file's word boxes.
        FieldOutcome borrower = outcomes.get(0);
        assertThat(borrower.valueEvidence())
                .extracting(EvidenceRef::spanId)
                .containsExactly(5L, 6L, 7L);
        assertThat(borrower.valueEvidence().get(0).box().x()).isEqualByComparingTo("130.0");
        assertThat(borrower.labelEvidence()).extracting(EvidenceRef::spanId).containsExactly(4L);

        FieldOutcome currentGross = outcomes.get(6);
        assertThat(currentGross.valueEvidence().get(0).layoutElementId())
                .isEqualTo(PaystubFixturePage.cellId(4, 3));
        assertThat(currentGross.labelEvidence())
                .extracting(EvidenceRef::layoutElementId)
                .containsExactly(PaystubFixturePage.cellId(0, 3), PaystubFixturePage.cellId(4, 0));

        FieldOutcome ytdGross = outcomes.get(7);
        assertThat(ytdGross.valueEvidence().get(0).layoutElementId())
                .isEqualTo(PaystubFixturePage.cellId(4, 4));
        assertThat(ytdGross.anchorStrength()).isEqualTo(1.0);

        // The duplicate 612.44s: occurrence 0 must map to the FIRST box, x 178.8.
        FieldOutcome federal = outcomes.get(9);
        assertThat(federal.valueEvidence()).extracting(EvidenceRef::spanId).containsExactly(46L);
        assertThat(federal.valueEvidence().get(0).box().x()).isEqualByComparingTo("178.8");
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
