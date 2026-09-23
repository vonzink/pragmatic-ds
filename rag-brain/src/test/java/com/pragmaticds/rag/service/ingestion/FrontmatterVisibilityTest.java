package com.pragmaticds.rag.service.ingestion;

import com.pragmaticds.rag.domain.SourceVisibility;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FrontmatterVisibilityTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesPublicFromFrontmatter() {
        String md = """
                ---
                visibility: PUBLIC
                ---
                # Doc
                """;
        assertEquals(SourceVisibility.PUBLIC, FrontmatterVisibility.parse("doc.md", bytes(md)));
    }

    @Test
    void parsesInternalAndSecure() {
        assertEquals(SourceVisibility.INTERNAL,
                FrontmatterVisibility.parse("a.md", bytes("---\nvisibility: INTERNAL\n---\n")));
        assertEquals(SourceVisibility.SECURE,
                FrontmatterVisibility.parse("a.md", bytes("---\nvisibility: SECURE\n---\n")));
    }

    @Test
    void visibilityKeyAmidOtherFrontmatterKeysIsFound() {
        // Real corpus shape: several keys, tags list, visibility mid-block.
        String md = """
                ---
                document_id: suite_faq_dashboard_acme_hubs
                doc_type: faq
                status: captured
                visibility: PUBLIC
                tags: [suite, dashboard]
                ---
                # Acme Dashboard Tool Hubs
                """;
        assertEquals(SourceVisibility.PUBLIC, FrontmatterVisibility.parse("hubs.md", bytes(md)));
    }

    @Test
    void lowercaseAndQuotedValuesAreAccepted() {
        assertEquals(SourceVisibility.PUBLIC,
                FrontmatterVisibility.parse("a.md", bytes("---\nvisibility: public\n---\n")));
        assertEquals(SourceVisibility.PUBLIC,
                FrontmatterVisibility.parse("a.md", bytes("---\nvisibility: \"PUBLIC\"\n---\n")));
        assertEquals(SourceVisibility.PUBLIC,
                FrontmatterVisibility.parse("a.md", bytes("---\nvisibility: 'PUBLIC'\n---\n")));
    }

    @Test
    void markdownExtensionVariantsAreParsed() {
        byte[] md = bytes("---\nvisibility: PUBLIC\n---\n");
        assertEquals(SourceVisibility.PUBLIC, FrontmatterVisibility.parse("doc.markdown", md));
        assertEquals(SourceVisibility.PUBLIC, FrontmatterVisibility.parse("Doc.MD", md));
        assertEquals(SourceVisibility.PUBLIC, FrontmatterVisibility.parse("income/doc.md", md));
    }

    @Test
    void nonMarkdownFileReturnsNull() {
        byte[] md = bytes("---\nvisibility: PUBLIC\n---\n");
        assertNull(FrontmatterVisibility.parse("doc.pdf", md));
        assertNull(FrontmatterVisibility.parse("doc.txt", md));
        assertNull(FrontmatterVisibility.parse("doc", md));
    }

    @Test
    void documentWithoutFrontmatterReturnsNull() {
        assertNull(FrontmatterVisibility.parse("doc.md", bytes("# Just a heading\nvisibility: PUBLIC\n")));
        assertNull(FrontmatterVisibility.parse("doc.md", bytes("")));
    }

    @Test
    void frontmatterWithoutVisibilityKeyReturnsNull() {
        assertNull(FrontmatterVisibility.parse("doc.md", bytes("---\ndoc_type: faq\n---\nbody\n")));
    }

    @Test
    void unrecognizedVisibilityValueReturnsNull() {
        assertNull(FrontmatterVisibility.parse("doc.md", bytes("---\nvisibility: EVERYONE\n---\n")));
    }

    @Test
    void unclosedFrontmatterFenceReturnsNull() {
        // A lone leading --- with no closing fence is not frontmatter (it is a
        // horizontal rule); nothing after it may be trusted as metadata.
        assertNull(FrontmatterVisibility.parse("doc.md", bytes("---\nvisibility: PUBLIC\nno closing fence\n")));
    }

    @Test
    void visibilityInBodyAfterClosedFrontmatterIsIgnored() {
        String md = """
                ---
                doc_type: faq
                ---
                Body text mentioning
                visibility: PUBLIC
                """;
        assertNull(FrontmatterVisibility.parse("doc.md", bytes(md)));
    }

    @Test
    void windowsLineEndingsAndBomAreHandled() {
        byte[] crlf = bytes("---\r\nvisibility: PUBLIC\r\n---\r\nbody\r\n");
        assertEquals(SourceVisibility.PUBLIC, FrontmatterVisibility.parse("doc.md", crlf));
        byte[] bom = bytes("﻿---\nvisibility: PUBLIC\n---\n");
        assertEquals(SourceVisibility.PUBLIC, FrontmatterVisibility.parse("doc.md", bom));
    }

    @Test
    void nullInputsReturnNull() {
        assertNull(FrontmatterVisibility.parse(null, bytes("---\nvisibility: PUBLIC\n---\n")));
        assertNull(FrontmatterVisibility.parse("doc.md", null));
    }
}
