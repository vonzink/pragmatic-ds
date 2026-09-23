package com.pragmaticds.rag.provider;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiModelProviderTest {

    /** Bare provider exercising only the default requireContent guard. */
    private final AiModelProvider provider = new AiModelProvider() {
        @Override public AiResponse generate(AiRequest request) { return null; }
        @Override public String getProviderName() { return "test-provider"; }
        @Override public String getModelName() { return "test-model"; }
    };

    @Test
    void requireContentReturnsTextWhenPresent() {
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("hello"))));
        assertEquals("hello", provider.requireContent(response));
    }

    @Test
    void requireContentThrowsOnNullResponse() {
        assertThrows(IllegalStateException.class, () -> provider.requireContent(null));
    }

    @Test
    void requireContentThrowsWhenNoChoices() {
        ChatResponse empty = new ChatResponse(List.of());
        assertThrows(IllegalStateException.class, () -> provider.requireContent(empty));
    }
}
