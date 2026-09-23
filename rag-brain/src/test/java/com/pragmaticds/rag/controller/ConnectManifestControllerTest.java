package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.repository.BrainRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectManifestControllerTest {

    @Test
    void manifestListsActiveBrainsAndNoSecrets() {
        Brain active = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic Brain");
        active.setActive(true);
        active.setLocalPath("/private/corpus");
        active.setS3Bucket("private-bucket");
        Brain inactive = new Brain(UUID.randomUUID(), "old", "Old Brain");
        inactive.setActive(false);
        BrainRepository brains = mock(BrainRepository.class);
        when(brains.findAll()).thenReturn(List.of(active, inactive));

        ConnectManifestController.ManifestDto manifest = new ConnectManifestController(brains).manifest();

        assertEquals("rag-brain-connect", manifest.protocol());
        assertEquals("/api/connect/v1", manifest.endpoints().get("federation"));
        assertEquals("/mcp/tools", manifest.endpoints().get("mcp"));
        assertEquals(List.of("generic"), manifest.brains().stream().map(b -> b.slug()).toList());
        assertTrue(manifest.capabilities().contains("ask:public"));
        String text = manifest.toString();
        assertFalse(text.contains("private-bucket"));
        assertFalse(text.contains("/private/corpus"));
        assertFalse(text.toLowerCase().contains("token"));
    }
}
