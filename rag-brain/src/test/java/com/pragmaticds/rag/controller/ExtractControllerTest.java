package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.connect.ConnectorAuthService;
import com.pragmaticds.rag.service.extract.ExtractionResult;
import com.pragmaticds.rag.service.extract.ExtractionService;
import com.pragmaticds.rag.service.extract.ExtractorNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Same exclude-filter posture as AnalyzeControllerTest: strip the request-infra
// @Components @WebMvcTest would otherwise instantiate (auth/rate-limit filters,
// correlation/CORS/connector web config) — none of them is exercised here (the
// widened API-key filter regex is unit-tested in AnalyzeApiKeyFilterTest).
@WebMvcTest(controllers = ExtractController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {com.pragmaticds.rag.config.AnalyzeApiKeyFilter.class,
                           com.pragmaticds.rag.config.AdminApiKeyFilter.class,
                           com.pragmaticds.rag.config.RateLimitFilter.class,
                           com.pragmaticds.rag.config.RequestCorrelationFilter.class,
                           com.pragmaticds.rag.config.CorsConfig.class,
                           com.pragmaticds.rag.config.ConnectorSecurityConfig.class}))
class ExtractControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ExtractionService extractionService;
    @MockBean BrainResolver brainResolver;
    // ConnectorAuthInterceptor (@Component) is still scanned into the slice and needs
    // ConnectorAuthService — mock it so the context starts. Not exercised here.
    @MockBean ConnectorAuthService connectorAuthService;

    private void resolvesMortgage() {
        Brain b = mock(Brain.class);
        when(b.getId()).thenReturn(UUID.randomUUID());
        when(brainResolver.resolve("mortgage")).thenReturn(b);
    }

    private MockMultipartFile docPart() {
        return new MockMultipartFile("doc", "sc.pdf", "application/pdf", new byte[]{1, 2, 3});
    }

    @Test
    void returnsCanonicalResponse() throws Exception {
        resolvesMortgage();
        when(extractionService.extract(any(), eq("income-schedule-c"), any()))
                .thenReturn(new ExtractionResult(ExtractionResult.Status.SUCCESS,
                        Map.of("netProfit", 100000.0, "taxYear", "2024"), List.of(),
                        "anthropic", "claude-x", 100, 50, null));

        mvc.perform(multipart("/api/ai/mortgage/extract/income-schedule-c").file(docPart()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.values.netProfit").value(100000.0))
                .andExpect(jsonPath("$.values.taxYear").value("2024"))
                .andExpect(jsonPath("$.warnings").isArray())
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.model").value("claude-x"))
                .andExpect(jsonPath("$.inputTokens").value(100))
                .andExpect(jsonPath("$.outputTokens").value(50));
    }

    @Test
    void errorStatusResultStillReturns200WithReason() throws Exception {
        resolvesMortgage();
        when(extractionService.extract(any(), eq("income-schedule-c"), any()))
                .thenReturn(new ExtractionResult(ExtractionResult.Status.ERROR,
                        Map.of(), List.of(), null, null, 0, 0, "unparseable model response"));

        mvc.perform(multipart("/api/ai/mortgage/extract/income-schedule-c").file(docPart()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.reason").value("unparseable model response"));
    }

    @Test
    void unknownExtractorIs404() throws Exception {
        resolvesMortgage();
        when(extractionService.extract(any(), eq("nope"), any()))
                .thenThrow(new ExtractorNotFoundException("nope"));

        mvc.perform(multipart("/api/ai/mortgage/extract/nope").file(docPart()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No extractor 'nope' defined for this brain"));
    }

    @Test
    void missingDocPartIs400() throws Exception {
        resolvesMortgage();

        mvc.perform(multipart("/api/ai/mortgage/extract/income-schedule-c"))
                .andExpect(status().isBadRequest());
    }
}
