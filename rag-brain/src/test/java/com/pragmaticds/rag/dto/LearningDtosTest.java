package com.pragmaticds.rag.dto;

import com.pragmaticds.rag.domain.BrainSourceWeight;
import com.pragmaticds.rag.domain.BrainSourceWeightEvent;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LearningDtosTest {

    @Test
    void eventDtoCopiesAllFields() {
        UUID brainId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        BrainSourceWeightEvent e = new BrainSourceWeightEvent(
                brainId,
                docId,
                1.0,
                null,
                1.12,
                7,
                "PENDING",
                "cumulative shift over threshold",
                "learning-job");

        LearningDtos.SourceWeightEventDto dto = LearningDtos.SourceWeightEventDto.from(e);

        assertEquals(e.getId(), dto.id());
        assertEquals(brainId, dto.brainId());
        assertEquals(docId, dto.documentId());
        assertEquals(1.0, dto.oldWeight());
        assertNull(dto.newWeight());
        assertEquals(1.12, dto.proposedWeight());
        assertEquals(7, dto.evidenceCount());
        assertEquals("PENDING", dto.status());
        assertEquals("cumulative shift over threshold", dto.reason());
        assertEquals("learning-job", dto.actor());
        assertEquals(e.getCreatedAt(), dto.createdAt());
    }

    @Test
    void weightDtoCopiesAllFields() {
        UUID brainId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        BrainSourceWeight w = new BrainSourceWeight(brainId, docId, 1.15, 12, "learning-job");

        LearningDtos.SourceWeightDto dto = LearningDtos.SourceWeightDto.from(w);

        assertEquals(brainId, dto.brainId());
        assertEquals(docId, dto.documentId());
        assertEquals(1.15, dto.weight());
        assertEquals(12, dto.feedbackCount());
        assertEquals(w.getUpdatedAt(), dto.updatedAt());
        assertEquals("learning-job", dto.updatedBy());
    }
}
