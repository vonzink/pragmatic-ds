package com.pragmaticds.rag.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrainDocumentTest {

    @Test
    void newDocumentsDefaultToInternalVisibilityAndApprovedTrust() {
        BrainDocument document = new BrainDocument();

        assertEquals(SourceVisibility.INTERNAL, document.getVisibility());
        assertEquals(SourceTrustLevel.APPROVED, document.getTrustLevel());
    }
}
