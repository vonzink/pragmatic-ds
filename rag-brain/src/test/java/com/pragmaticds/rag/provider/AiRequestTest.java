package com.pragmaticds.rag.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiRequestTest {

    @Test
    void guidelineAnswerFactoryIsAnswerPurposeWithNoOverride() {
        AiRequest request = AiRequest.forGuidelineAnswer("p");
        assertEquals(AiRequest.Purpose.ANSWER, request.purpose());
        assertNull(request.model());
        assertEquals(0.2, request.temperature());
        assertEquals(1500, request.maxTokens());
    }

    @Test
    void utilityFactoryIsUtilityPurpose() {
        AiRequest request = AiRequest.forUtility("p", 0.0, 800);
        assertEquals(AiRequest.Purpose.UTILITY, request.purpose());
        assertNull(request.model());
    }

    @Test
    void withModelKeepsEverythingElse() {
        AiRequest request = AiRequest.forUtility("p", 0.0, 800).withModel("m");
        assertEquals("m", request.model());
        assertEquals(AiRequest.Purpose.UTILITY, request.purpose());
        assertEquals("p", request.prompt());
    }
}
