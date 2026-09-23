package com.pragmaticds.rag.service.ingestion;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FrontmatterDocIdTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesDocumentIdFromFrontmatter() {
        String md = """
                ---
                document_id: suite_page_pipeline_board
                doc_type: page_reference
                ---
                # Pipeline board
                """;
        assertEquals("suite_page_pipeline_board", FrontmatterDocId.parse("pipeline-board.md", bytes(md)));
    }

    @Test
    void returnsNullWhenFrontmatterDeclaresNoDocumentId() {
        String md = """
                ---
                doc_type: faq
                visibility: PUBLIC
                ---
                # Doc
                """;
        assertNull(FrontmatterDocId.parse("a.md", bytes(md)));
    }

    @Test
    void ignoresDocumentIdOutsideTheFrontmatterFence() {
        // A document_id in the body is prose, not metadata — trusting it would let
        // body text rewrite a document's identity.
        assertNull(FrontmatterDocId.parse("a.md", bytes("# Heading\ndocument_id: not_metadata\n")));
        assertNull(FrontmatterDocId.parse("a.md", bytes("---\ndocument_id: unterminated\nno closing fence\n")));
    }

    @Test
    void acceptsQuotedAndPunctuatedIds() {
        assertEquals("suite_faq_console", FrontmatterDocId.parse("a.md", bytes("---\ndocument_id: \"suite_faq_console\"\n---\n")));
        assertEquals("suite_faq_console", FrontmatterDocId.parse("a.md", bytes("---\ndocument_id: 'suite_faq_console'\n---\n")));
        assertEquals("fnma-sg.income:v2", FrontmatterDocId.parse("a.md", bytes("---\ndocument_id: fnma-sg.income:v2\n---\n")));
    }

    @Test
    void returnsNullForNonMarkdownOrMissingInput() {
        byte[] md = bytes("---\ndocument_id: suite_page_x\n---\n");
        assertNull(FrontmatterDocId.parse("doc.pdf", md));
        assertNull(FrontmatterDocId.parse(null, md));
        assertNull(FrontmatterDocId.parse("doc.md", null));
    }
}
