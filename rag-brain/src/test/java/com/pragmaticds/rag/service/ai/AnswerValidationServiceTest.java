package com.pragmaticds.rag.service.ai;

import com.pragmaticds.rag.dto.CitationDto;
import com.pragmaticds.rag.pack.TestPacks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static com.pragmaticds.rag.TestBrains.DEFAULT_ID;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compliance gate tests — these protect the public website safety rules.
 * If one of these fails after a change, the change is wrong, not the test.
 */
class AnswerValidationServiceTest {

    private final AnswerValidationService validator = new AnswerValidationService(TestPacks.registry());

    private static final List<CitationDto> CITATIONS = List.of(
            new CitationDto("Fannie Mae Selling Guide", "selling-guide.pdf",
                    "B3-3.1-01", "12", "2026-01-01"));

    private ModelAnswer answerWith(String text) {
        return new ModelAnswer(text, CITATIONS, 0.9, false, "disclaimer");
    }

    @Test
    void acceptsCompliantAnswer() {
        var result = validator.validate(answerWith(
                "Overtime income may generally be used if it has a two-year history, "
                + "subject to full loan review."), true, DEFAULT_ID);
        assertTrue(result.valid());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Good news — you qualify for this program.",
            "You are approved based on these guidelines.",
            "Approval is guaranteed if your DTI is below 45%.",
            "The underwriter must accept this documentation.",
            "Don't worry, this will close on time."
    })
    void rejectsProhibitedPhrases(String text) {
        var result = validator.validate(answerWith(text), true, DEFAULT_ID);
        assertFalse(result.valid(), "Should reject: " + text);
    }

    @Test
    void rejectsEligibleOutsideQuote() {
        var result = validator.validate(answerWith(
                "You are eligible for FHA financing."), true, DEFAULT_ID);
        assertFalse(result.valid());
    }

    @Test
    void allowsEligibleInsideDirectQuote() {
        var result = validator.validate(answerWith(
                "The guideline states: \"You are eligible if the property is your "
                + "primary residence.\" A loan officer should verify your scenario."), true, DEFAULT_ID);
        assertTrue(result.valid());
    }

    @Test
    void rejectsMissingCitationsWhenEvidenceWasSufficient() {
        var answer = new ModelAnswer("PMI is mortgage insurance.", List.of(), 0.9, false, "d");
        assertFalse(validator.validate(answer, true, DEFAULT_ID).valid());
    }

    @Test
    void rejectsEmptyAnswer() {
        var answer = new ModelAnswer("  ", CITATIONS, 0.9, false, "d");
        assertFalse(validator.validate(answer, true, DEFAULT_ID).valid());
    }

    @ParameterizedTest
    @MethodSource("allPackPhrases")
    void rejectsEveryPackProhibitedPhrase(String phrase) {
        var result = validator.validate(answerWith("Note that " + phrase + " in this case."), true, DEFAULT_ID);
        assertFalse(result.valid(), "pack phrase must be rejected: " + phrase);
    }

    static Stream<String> allPackPhrases() {
        return TestPacks.sample().guardrails().prohibitedPhrases().stream();
    }
}
