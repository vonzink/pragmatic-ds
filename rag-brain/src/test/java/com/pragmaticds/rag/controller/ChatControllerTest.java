package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.dto.ChatRequest;
import com.pragmaticds.rag.dto.ChatResponse;
import com.pragmaticds.rag.dto.CitationDto;
import com.pragmaticds.rag.exception.GlobalExceptionHandler;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc (mirrors {@code PublicAskControllerTest}): real request
 * binding + {@code @Valid} + {@code GlobalExceptionHandler}, mocked service
 * layer. AnalyzeApiKeyFilter itself is unit-tested separately
 * ({@code AnalyzeApiKeyFilterTest}) — it never runs in this slice.
 */
class ChatControllerTest {

    private static final UUID BRAIN_ID = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private final ChatService chatService = mock(ChatService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ChatController(chatService, brainResolver))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void happyPathReturnsAnswerAndCitations() throws Exception {
        when(brainResolver.resolve("mortgage")).thenReturn(new Brain(BRAIN_ID, "mortgage", "Mortgage"));
        when(chatService.answer(any(ChatRequest.class), eq(BRAIN_ID))).thenReturn(
                new ChatResponse("The minimum score is 620.",
                        List.of(new CitationDto("Selling Guide", "Doc.pdf", "3.2", "4", "2026-01-01"))));

        mvc.perform(post("/api/ai/mortgage/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "What is the minimum credit score?",
                                  "context": "Folder: Income",
                                  "transcript": []
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("The minimum score is 620."))
                .andExpect(jsonPath("$.citations[0].source_name").value("Selling Guide"));

        verify(chatService).answer(any(ChatRequest.class), eq(BRAIN_ID));
    }

    @Test
    void blankQuestionIs400AndNeverReachesTheService() throws Exception {
        mvc.perform(post("/api/ai/mortgage/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": ""
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    void questionOverCapIs400() throws Exception {
        String tooLong = "a".repeat(4001);

        mvc.perform(post("/api/ai/mortgage/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    void transcriptTurnContentOverCapIs400() throws Exception {
        // Proves the @Valid cascade into ChatRequest.Turn: the 4000-char content
        // cap on a NESTED transcript turn must reject, not just the top-level caps.
        String oversizeContent = "b".repeat(4001);

        mvc.perform(post("/api/ai/mortgage/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"A question\",\"transcript\":[{\"role\":\"user\",\"content\":\""
                                + oversizeContent + "\"}]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    void contextOverCapIs400() throws Exception {
        String oversizeContext = "c".repeat(16001);

        mvc.perform(post("/api/ai/mortgage/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"A question\",\"context\":\"" + oversizeContext + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    void transcriptOverTwelveTurnsIs400() throws Exception {
        StringBuilder turns = new StringBuilder();
        for (int i = 0; i < 13; i++) {
            if (i > 0) {
                turns.append(',');
            }
            turns.append("{\"role\":\"user\",\"content\":\"turn ").append(i).append("\"}");
        }

        mvc.perform(post("/api/ai/mortgage/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"A question\",\"transcript\":[" + turns + "]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    void unknownBrainIs400() throws Exception {
        when(brainResolver.resolve("nope")).thenThrow(new IllegalArgumentException("Unknown brain: nope"));

        mvc.perform(post("/api/ai/nope/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "What is the minimum credit score?"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown brain: nope"));

        verifyNoInteractions(chatService);
    }
}
