package com.pragmaticds.rag.provider;

import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiRequestMediaTest {

    @Test
    void guidelineAnswerHasNoMedia() {
        AiRequest req = AiRequest.forGuidelineAnswer("hi");
        assertNotNull(req.media());
        assertTrue(req.media().isEmpty());
    }

    @Test
    void analysisFactoryCarriesMedia() {
        Media m = Media.builder()
                .mimeType(Media.Format.DOC_PDF)
                .data(new ByteArrayResource(new byte[]{1, 2, 3}))
                .build();
        AiRequest req = AiRequest.forAnalysis("prompt", List.of(m), 4000, null, null);
        assertEquals(1, req.media().size());
        assertEquals(AiRequest.Purpose.ANALYZE, req.purpose());
        assertEquals(4000, req.maxTokens());
    }

    @Test
    void withModelPreservesMedia() {
        Media m = Media.builder().mimeType(Media.Format.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{9})).build();
        AiRequest req = AiRequest.forAnalysis("p", List.of(m), 1000, null, null).withModel("claude-x");
        assertEquals(1, req.media().size());
        assertEquals("claude-x", req.model());
    }
}
