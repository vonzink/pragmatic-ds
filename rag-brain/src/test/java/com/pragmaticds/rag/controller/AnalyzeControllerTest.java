package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.dto.RefineRequest;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.analyze.AnalysisResult;
import com.pragmaticds.rag.service.analyze.AnalysisService;
import com.pragmaticds.rag.service.analyze.AnalyzerNotFoundException;
import com.pragmaticds.rag.service.analyze.FilteredDoc;
import com.pragmaticds.rag.service.analyze.SkipCategory;
import com.pragmaticds.rag.service.analyze.SkippedDoc;
import com.pragmaticds.rag.service.connect.ConnectorAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Exclude the request-infra @Components @WebMvcTest would otherwise instantiate — the
// auth/rate-limit filters, the correlation/CORS/connector web config. They pull in
// RagProperties, @Value props, and ConnectorAuthService that aren't in this slice, and
// none of them is exercised by these two controller tests (the API-key filter is
// unit-tested in Task 3). This leaves just the controller + Spring MVC + Jackson.
@WebMvcTest(controllers = AnalyzeController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {com.pragmaticds.rag.config.AnalyzeApiKeyFilter.class,
                           com.pragmaticds.rag.config.AdminApiKeyFilter.class,
                           com.pragmaticds.rag.config.RateLimitFilter.class,
                           com.pragmaticds.rag.config.RequestCorrelationFilter.class,
                           com.pragmaticds.rag.config.CorsConfig.class,
                           com.pragmaticds.rag.config.ConnectorSecurityConfig.class}))
class AnalyzeControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AnalysisService analysisService;
    @MockBean BrainResolver brainResolver;
    // ConnectorAuthInterceptor (@Component) is still scanned into the slice and needs
    // ConnectorAuthService — mock it so the context starts. Not exercised here.
    @MockBean ConnectorAuthService connectorAuthService;

    private void resolvesMortgage() {
        Brain b = mock(Brain.class);
        when(b.getId()).thenReturn(UUID.randomUUID());
        when(brainResolver.resolve("mortgage")).thenReturn(b);
    }

    @Test
    void returnsCanonicalResponse() throws Exception {
        resolvesMortgage();
        when(analysisService.analyze(any(), eq("income"), anyList(), any()))
                .thenReturn(new AnalysisResult(AnalysisResult.Status.SUCCESS,
                        "# Report", "{\"items\":[]}", List.of(),
                        "anthropic", "claude-x", 100, 50, 0.001, 3,
                        List.of(new SkippedDoc("d9", "old.pdf", SkipCategory.OVER_DOC_CAP, "over document cap")), null));

        MockMultipartFile ctx = new MockMultipartFile("context", "context", "application/json",
                ("{\"docs\":[{\"id\":\"d1\",\"fileName\":\"p.png\",\"contentType\":\"image/png\",\"sizeBytes\":3}],"
                        + "\"loan\":{\"loanAmount\":400000,\"monthlyIncome\":8000,\"borrowers\":[\"Jane\"]}}").getBytes());
        MockMultipartFile file = new MockMultipartFile("docs", "p.png", "image/png", new byte[]{1, 2, 3});

        mvc.perform(multipart("/api/ai/mortgage/analyze/income").file(ctx).file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.reportMarkdown").value("# Report"))
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.pageCount").value(3))
                .andExpect(jsonPath("$.skippedDocs[0].id").value("d9"))
                .andExpect(jsonPath("$.skippedDocs[0].fileName").value("old.pdf"))
                .andExpect(jsonPath("$.skippedDocs[0].category").value("OVER_DOC_CAP"))
                .andExpect(jsonPath("$.skippedDocs[0].reason").value("over document cap"))
                .andExpect(jsonPath("$.inputTokens").value(100));
    }

    @Test
    void filteredDocsAppearInTheResponse() throws Exception {
        resolvesMortgage();
        when(analysisService.analyze(any(), eq("income"), anyList(), any()))
                .thenReturn(new AnalysisResult(AnalysisResult.Status.SUCCESS,
                        "# Report", "{}", List.of(),
                        "anthropic", "claude-x", 100, 50, 0.001, 9,
                        List.of(), null,
                        List.of(new FilteredDoc("d1", "2024_1040.pdf", 62, 9, List.of("1040")))));

        MockMultipartFile ctx = new MockMultipartFile("context", "context", "application/json",
                "{\"docs\":[]}".getBytes());

        mvc.perform(multipart("/api/ai/mortgage/analyze/income").file(ctx))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filtered[0].id").value("d1"))
                .andExpect(jsonPath("$.filtered[0].fileName").value("2024_1040.pdf"))
                .andExpect(jsonPath("$.filtered[0].pagesTotal").value(62))
                .andExpect(jsonPath("$.filtered[0].pagesKept").value(9))
                .andExpect(jsonPath("$.filtered[0].matchedForms[0]").value("1040"));
    }

    @Test
    void unknownAnalyzerIs404() throws Exception {
        resolvesMortgage();
        when(analysisService.analyze(any(), eq("nope"), anyList(), any()))
                .thenThrow(new AnalyzerNotFoundException("nope"));

        MockMultipartFile ctx = new MockMultipartFile("context", "context", "application/json",
                "{\"docs\":[]}".getBytes());

        mvc.perform(multipart("/api/ai/mortgage/analyze/nope").file(ctx))
                .andExpect(status().isNotFound());
    }

    @Test
    void refineDelegatesToServiceAndMapsResponse() throws Exception {
        resolvesMortgage();
        when(analysisService.refine(any(), eq("income"), any(RefineRequest.class)))
                .thenReturn(new AnalysisResult(AnalysisResult.Status.SUCCESS, "# Updated", "{}",
                        List.of(), "anthropic", "claude-x", 80, 40, 0.01, 0, List.of(), null));

        String body = "{\"context\":null,\"priorFindings\":null,"
                + "\"priorReportMarkdown\":\"# Prior\",\"transcript\":[]}";

        mvc.perform(post("/api/ai/mortgage/analyze/income/refine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.reportMarkdown").value("# Updated"));

        verify(analysisService).refine(any(), eq("income"), any(RefineRequest.class));
    }
}
