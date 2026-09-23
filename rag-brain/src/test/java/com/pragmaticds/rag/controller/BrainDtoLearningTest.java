package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrainDtoLearningTest {

    @Test
    void fromCopiesLearningEnabledTrue() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        brain.setLearningEnabled(true);

        BrainAdminController.BrainDto dto = BrainAdminController.BrainDto.from(brain);

        assertTrue(dto.learningEnabled());
    }

    @Test
    void fromDefaultsLearningEnabledFalse() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");

        BrainAdminController.BrainDto dto = BrainAdminController.BrainDto.from(brain);

        assertFalse(dto.learningEnabled());
    }
}
