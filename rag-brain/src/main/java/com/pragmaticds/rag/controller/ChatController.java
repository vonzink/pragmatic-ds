package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.ChatRequest;
import com.pragmaticds.rag.dto.ChatResponse;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.chat.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal folder-brains chat endpoint: single-turn corpus-RAG question
 * answering over a folder's suite-supplied context (folder/loan summary, doc
 * list, latest-run findings — a few KB, never doc bytes) plus this brain's
 * indexed guideline corpus. Gated by {@code AnalyzeApiKeyFilter} on the SAME
 * {@code X-Analyze-Api-Key} the analyze path uses (401/503 — see that filter);
 * the suite is the only caller. Deliberately outside {@code RateLimitFilter}'s
 * buckets, same posture as {@code /analyze}: an S2S surface behind a static
 * key, not a public/rate-limited one.
 *
 * <p>Stateless: no conversation is persisted here (the suite owns the
 * ephemeral transcript and replays it on every call). Unlike
 * {@code AskService}'s public pipeline, this surface does not apply
 * {@code SpendGuardService}'s daily-spend circuit breaker — follow-up if
 * folder chat ever needs the same cap AskService enforces; today it is
 * bounded by being reachable only via the S2S key.
 */
@RestController
public class ChatController {

    private final ChatService chatService;
    private final BrainResolver brainResolver;

    public ChatController(ChatService chatService, BrainResolver brainResolver) {
        this.chatService = chatService;
        this.brainResolver = brainResolver;
    }

    @PostMapping("/api/ai/{brain}/chat")
    public ResponseEntity<ChatResponse> chat(@PathVariable("brain") String brain,
                                             @Valid @RequestBody ChatRequest request) {
        UUID brainId = brainResolver.resolve(brain).getId();
        return ResponseEntity.ok(chatService.answer(request, brainId));
    }
}
