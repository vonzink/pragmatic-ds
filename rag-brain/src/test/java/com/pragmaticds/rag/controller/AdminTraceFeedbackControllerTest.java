package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.AdminFeedbackRequest;
import com.pragmaticds.rag.service.learning.FeedbackService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminTraceFeedbackControllerTest {

    private final FeedbackService service = mock(FeedbackService.class);
    private final AdminTraceFeedbackController controller = new AdminTraceFeedbackController(service);

    @Test
    void recordsAdminFeedbackWithActorHeader() {
        UUID traceId = UUID.randomUUID();
        ResponseEntity<Void> resp = controller.feedback(
                traceId, "admin@x", new AdminFeedbackRequest("DOWN", "outdated source"));

        assertEquals(204, resp.getStatusCode().value());
        verify(service).record(eq(traceId), eq("DOWN"), eq("ADMIN"),
                eq("outdated source"), isNull(), eq("admin@x"));
    }

    @Test
    void defaultsActorWhenHeaderMissing() {
        UUID traceId = UUID.randomUUID();
        controller.feedback(traceId, null, new AdminFeedbackRequest("UP", null));
        verify(service).record(eq(traceId), eq("UP"), eq("ADMIN"), isNull(), isNull(), eq("admin"));
    }

    @Test
    void badRatingPropagatesIllegalArgument() {
        UUID traceId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("Unknown rating: MEH"))
                .when(service).record(eq(traceId), eq("MEH"), eq("ADMIN"), isNull(), isNull(), eq("admin"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.feedback(traceId, null, new AdminFeedbackRequest("MEH", null)));
    }
}
