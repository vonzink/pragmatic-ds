package com.pragmaticds.rag.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Folder-brain chat request (POST /api/ai/{brain}/chat). {@code context} and
 * {@code transcript} are optional — the suite composes them from its own
 * loan/folder/run state and replays the transcript on every call (chat is
 * stateless engine-side; no conversation is persisted here). Caps are wider
 * than {@link AskRequest}'s (2000-char question): chat carries a compact
 * suite-supplied context block, not just a bare question.
 */
public record ChatRequest(
        @NotBlank @Size(max = 4000) String question,
        @Size(max = 16000) String context,
        @Valid @Size(max = 12) List<Turn> transcript) {

    public record Turn(@Size(max = 20) String role, @Size(max = 4000) String content) {}
}
