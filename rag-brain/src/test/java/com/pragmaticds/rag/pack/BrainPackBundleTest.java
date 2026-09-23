package com.pragmaticds.rag.pack;

import com.pragmaticds.rag.service.ai.QuestionCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundle precomputes the same derived state the service constructors used to build. */
class BrainPackBundleTest {

    private final BrainPackBundle bundle = BrainPackBundle.of(TestPacks.sample());

    @Test
    void classifierPatternsPreserveCheckOrder() {
        // Insertion order from classifier.yaml: FRAUD first (priority), then ELIGIBILITY...
        // ...and PERSONAL_LOOKUP last, so every earlier category keeps precedence.
        List<QuestionCategory> order = List.copyOf(bundle.classifierPatterns().keySet());
        assertEquals(QuestionCategory.FRAUD, order.get(0));
        assertEquals(QuestionCategory.ELIGIBILITY, order.get(1));
        assertEquals(QuestionCategory.PERSONAL_LOOKUP, order.get(order.size() - 1));
        assertEquals(6, order.size());
    }

    @Test
    void classifierPatternsCompileEveryRule() {
        for (var entry : bundle.classifierPatterns().entrySet()) {
            assertTrue(entry.getValue().stream().allMatch(p -> p instanceof Pattern),
                    "every classifier pattern must be a compiled Pattern for " + entry.getKey());
            assertTrue(!entry.getValue().isEmpty(), "no empty pattern list for " + entry.getKey());
        }
    }

    @Test
    void programsAndAcronymsComeFromThePack() {
        assertEquals(TestPacks.sample().acronymExpansions(), bundle.acronyms());
        assertEquals(List.of("FHA", "VA", "USDA", "CONVENTIONAL"),
                bundle.programs().stream().map(CompiledProgram::name).toList());
    }

    @Test
    void packAccessorReturnsTheSamePack() {
        assertEquals(TestPacks.sample(), bundle.pack());
    }
}
