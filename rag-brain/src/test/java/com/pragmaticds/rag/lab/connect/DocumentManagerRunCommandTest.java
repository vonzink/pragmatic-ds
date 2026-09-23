package com.pragmaticds.rag.lab.connect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DocumentManagerRunCommandTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID BRAIN = UUID.randomUUID();
    private static final UUID PACKAGE = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();

    private static DocumentManagerRunCommand command(String subjectScope) {
        return new DocumentManagerRunCommand(CLIENT, BRAIN, "income", "tenant-1", "ext-1",
                PACKAGE, 3, List.of(SOURCE), null, subjectScope);
    }

    @Test
    void subjectScopeIsCarried() {
        assertEquals("scope-a", command("scope-a").subjectScope());
    }

    @Test
    void subjectScopeIsOptional() {
        assertNull(command(null).subjectScope());
    }

    @Test
    void subjectScopeDoesNotChangeTheExternalRequestDigest() {
        // A retry that attaches a scope must replay the original run group, not fork a second
        // one -- the same rule loanFacts already follows. externalRequestSha256 is computed by
        // DocumentManagerRunService (a static collaborator), not the record itself, so the two
        // commands' digests are compared through that collaborator.
        assertEquals(
                DocumentManagerRunService.externalRequestSha256(command(null)),
                DocumentManagerRunService.externalRequestSha256(command("scope-a")));
    }
}
