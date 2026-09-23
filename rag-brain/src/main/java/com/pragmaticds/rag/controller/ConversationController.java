package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.Conversation;
import com.pragmaticds.rag.domain.Message;
import com.pragmaticds.rag.repository.ConversationRepository;
import com.pragmaticds.rag.repository.MessageRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Conversation history for the website chat widget. The caller must present
 * the same session id the conversation was created with — visitors can only
 * read their own chats.
 */
@RestController
@RequestMapping("/api/ai/conversations")
public class ConversationController {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationController(ConversationRepository conversationRepository,
                                  MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationView> get(@PathVariable UUID conversationId,
                                                @RequestHeader("X-Session-Id") String sessionId) {
        Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null
                || !Objects.equals(conversation.getUserSessionId(), sessionId)) {
            // 404 for both cases — do not reveal that the conversation exists.
            return ResponseEntity.notFound().build();
        }

        List<MessageView> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(m -> new MessageView(m.getRole(), m.getContent(), m.getCreatedAt()))
                .toList();

        return ResponseEntity.ok(new ConversationView(
                conversation.getId(), conversation.getCreatedAt(), messages));
    }

    public record ConversationView(UUID id, OffsetDateTime createdAt, List<MessageView> messages) {
    }

    public record MessageView(String role, String content, OffsetDateTime createdAt) {
    }
}
