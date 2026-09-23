package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.AuditLog;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.dto.AuditPageDto;
import com.pragmaticds.rag.repository.AuditLogRepository;
import com.pragmaticds.rag.service.BrainResolver;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditControllerTest {

    private static final UUID MORTGAGE_ID = UUID.fromString("00000000-0000-0000-0000-00000000cafe");

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final BrainResolver resolver = mock(BrainResolver.class);
    private final AdminAuditController controller = new AdminAuditController(repository, resolver);

    private AuditLog entry(String question) {
        AuditLog log = new AuditLog();
        log.setUserQuestion(question);
        return log;
    }

    private void resolvesTo(String slug, UUID id) {
        when(resolver.resolve(slug)).thenReturn(new Brain(id, slug == null ? "generic" : slug, "Brain"));
    }

    @Test
    void listMapsPageAndClampsSize() {
        resolvesTo(null, TestBrains.DEFAULT_ID);
        when(repository.search(eq(TestBrains.DEFAULT_ID), eq(false), eq(PageRequest.of(0, 100))))
                .thenReturn(new PageImpl<>(List.of(entry("What is PMI?")),
                        PageRequest.of(0, 20), 1));

        AuditPageDto page = controller.list(0, 500, false, null, null);

        assertEquals(1, page.items().size());
        assertEquals("What is PMI?", page.items().get(0).question());
        assertEquals(1, page.total());
        verify(repository).search(eq(TestBrains.DEFAULT_ID), eq(false), eq(PageRequest.of(0, 100)));  // size clamped to 100
    }

    @Test
    void blankQueryBecomesNull() {
        resolvesTo(null, TestBrains.DEFAULT_ID);
        when(repository.search(eq(TestBrains.DEFAULT_ID), eq(true), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of()));
        controller.list(0, 20, true, "   ", null);
        verify(repository).search(eq(TestBrains.DEFAULT_ID), eq(true), eq(PageRequest.of(0, 20)));
    }

    // The dashboard's Overview and Audit screens send ?brain=<slug> like every
    // other admin endpoint; the list must resolve it and scope the page to that
    // brain, for both the plain and the substring variant.
    @Test
    void listScopesToTheResolvedBrain() {
        resolvesTo("mortgage", MORTGAGE_ID);
        when(repository.search(eq(MORTGAGE_ID), eq(false), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(entry("What is PMI?"))));
        when(repository.search(eq(MORTGAGE_ID), eq(false), eq("pmi"), eq(PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of(entry("What is PMI?"))));

        assertEquals(1, controller.list(0, 20, false, null, "mortgage").items().size());
        assertEquals(1, controller.list(0, 20, false, "pmi", "mortgage").items().size());

        verify(repository).search(eq(MORTGAGE_ID), eq(false), eq(PageRequest.of(0, 20)));
        verify(repository).search(eq(MORTGAGE_ID), eq(false), eq("pmi"), eq(PageRequest.of(0, 20)));
    }

    @Test
    void detailThrowsOnUnknownId() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> controller.detail(id));
    }
}
