package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.dto.IngestionQualityDto;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.ingestion.IngestionQualityService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionQualityControllerTest {

    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final IngestionQualityService qualityService = mock(IngestionQualityService.class);
    private final IngestionQualityController controller =
            new IngestionQualityController(brainResolver, qualityService);

    @Test
    void resolvesBrainAndReturnsIngestionQuality() {
        IngestionQualityDto response = new IngestionQualityDto(
                TestBrains.DEFAULT_ID,
                1,
                1,
                2,
                2,
                0,
                1,
                1,
                0,
                0,
                0,
                0,
                List.of(),
                List.of());
        when(brainResolver.resolve("generic")).thenReturn(new Brain(TestBrains.DEFAULT_ID, "generic", "Generic"));
        when(qualityService.evaluate(TestBrains.DEFAULT_ID)).thenReturn(response);

        assertSame(response, controller.quality("generic"));
        verify(qualityService).evaluate(TestBrains.DEFAULT_ID);
    }
}
