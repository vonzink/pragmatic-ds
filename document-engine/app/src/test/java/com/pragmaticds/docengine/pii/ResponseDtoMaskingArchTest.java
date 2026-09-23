package com.pragmaticds.docengine.pii;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

import com.pragmaticds.docengine.platform.pii.MaskableValue;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Structural guard for the masking boundary. Every response-DTO component that carries a field VALUE
 * must be a {@link MaskableValue}, never a raw {@code String}/{@code Object}. A raw value field could
 * emit a sensitive value straight to the wire; a {@code MaskableValue} can only reach the wire
 * through {@code MaskingSerializer}. This rule fails the build the day someone adds a value-bearing
 * field to a response DTO and forgets to wrap it — the leak is caught at compile-of-tests, not in
 * production.
 */
class ResponseDtoMaskingArchTest {

    private static final JavaClasses RESPONSE_DTOS =
            new ClassFileImporter()
                    // Guard PRODUCTION response DTOs only — a test fixture record may reuse a
                    // value-bearing component name (e.g. FieldCorrectionIT's Field.machineValue).
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.pragmaticds.docengine.extraction.web", "com.pragmaticds.docengine.review");

    /**
     * Spec 5a note, recorded so the next reader does not "fix" an omission that is a decision.
     * {@code FieldView.groupKey}, {@code ExportFieldView.groupKey} and
     * {@code HistoryEntry.groupKey} are plain {@code String}s and no rule in this class matches
     * them, because every rule here is a NAME whitelist over VALUE-bearing components. A
     * repeating-group key is the letter or ordinal printed on the form — a coordinate, never
     * borrower data — and masking it would hide the label that makes an occurrence findable,
     * which is the entire reason the key exists.
     *
     * <p>The live risk is the adjacent one: a future component that carries an occurrence's VALUE
     * under a name outside {@code rawValue|displayedText|value|normalizedValue|text}, or inside a
     * nested record whose simple name does not end in {@code View}, would bypass this rule
     * silently. Widen the predicate in the same commit that introduces such a component.
     */
    @Test
    void extraction_response_value_fields_are_maskable_not_raw() {
        ArchRule rule =
                fields()
                        .that()
                        .areDeclaredInClassesThat()
                        .resideInAPackage("com.pragmaticds.docengine.extraction.web")
                        .and()
                        .areDeclaredInClassesThat()
                        .haveSimpleNameEndingWith("View")
                        .and()
                        .haveNameMatching("rawValue|displayedText|value|normalizedValue|text")
                        .should()
                        .haveRawType(MaskableValue.class)
                        .because(
                                "a value-bearing response field must serialise through MaskingSerializer,"
                                        + " so a future DTO cannot emit an unmasked sensitive value by"
                                        + " construction");
        rule.check(RESPONSE_DTOS);
    }

    @Test
    void review_history_previous_and_new_values_are_maskable_not_raw() {
        ArchRule rule =
                fields()
                        .that()
                        .areDeclaredInClassesThat()
                        .haveSimpleName("HistoryEntry")
                        .and()
                        .haveNameMatching("previousValue|newValue")
                        .should()
                        .haveRawType(MaskableValue.class)
                        .because(
                                "the review-history read path may surface a corrected sensitive value"
                                        + " (V8 flags previous_value/new_value as possible PII)");
        rule.check(RESPONSE_DTOS);
    }

    /**
     * The correction/review WRITE responses (FieldCorrectionService.Result/DecisionView,
     * ReviewDecisionService.DecisionView) are returned directly on {@code PATCH /v1/fields/{id}} and
     * the document review/classification endpoints. Their value-bearing components must be
     * {@link MaskableValue} too, so this whole class of {@code com.pragmaticds.docengine.review} response
     * DTO is guarded by construction — not just fixed once. Scoped to the exact {@code review}
     * package so the JPA entity in {@code review.domain} (which legitimately holds raw jsonb) is not
     * caught.
     */
    @Test
    void review_response_value_fields_are_maskable_not_raw() {
        ArchRule rule =
                fields()
                        .that()
                        .areDeclaredInClassesThat()
                        .resideInAPackage("com.pragmaticds.docengine.review")
                        .and()
                        .haveNameMatching(
                                "value|previousValue|newValue|machineValue|effectiveValue"
                                        + "|displayedText|rawValue")
                        .should()
                        .haveRawType(MaskableValue.class)
                        .because(
                                "a value-bearing review response field must serialise through"
                                        + " MaskingSerializer, so a correction/review WRITE response"
                                        + " cannot emit an unmasked sensitive value by construction");
        rule.check(RESPONSE_DTOS);
    }
}
