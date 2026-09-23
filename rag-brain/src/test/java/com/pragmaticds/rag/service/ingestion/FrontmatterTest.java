package com.pragmaticds.rag.service.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link Frontmatter#strip} removes the leading YAML block from the extracted
 * text of a markdown document so it never becomes a chunk. Without this every
 * frontmatter-bearing corpus file shipped a "--- document_id: … ---" chunk that
 * was embedded, searchable, and (for identical blocks) reported as duplicate text.
 */
class FrontmatterTest {

    @Test
    void stripsClosedFrontmatterBlockFromMarkdown() {
        String text = """
                ---
                document_id: console_faq
                visibility: public
                ---

                # Console FAQ

                How do I log in?""";

        assertEquals("# Console FAQ\n\nHow do I log in?", Frontmatter.strip("console-faq.md", text));
    }

    @Test
    void acceptsYamlDocumentEndMarkerAsClosingFence() {
        String text = "---\nvisibility: public\n...\nBody text";

        assertEquals("Body text", Frontmatter.strip("guide.markdown", text));
    }

    @Test
    void leavesUnclosedFenceAloneBecauseItIsAHorizontalRule() {
        String text = "---\n\nNot frontmatter, just a rule then text.";

        assertSame(text, Frontmatter.strip("notes.md", text));
    }

    @Test
    void leavesTextWithoutFrontmatterUntouched() {
        String text = "# Heading\n\nBody.";

        assertSame(text, Frontmatter.strip("notes.md", text));
    }

    @Test
    void ignoresNonMarkdownFilesEvenWhenTheyStartWithAFence() {
        String text = "---\nkey: value\n---\nbody";

        assertSame(text, Frontmatter.strip("report.pdf", text));
        assertSame(text, Frontmatter.strip(null, text));
    }

    @Test
    void toleratesWindowsLineEndingsAndByteOrderMark() {
        String text = "﻿---\r\ndocument_id: x\r\n---\r\n\r\nBody.\r\n";

        assertEquals("Body.", Frontmatter.strip("x.md", text));
    }

    @Test
    void returnsEmptyWhenTheDocumentIsOnlyFrontmatter() {
        assertEquals("", Frontmatter.strip("empty.md", "---\nvisibility: public\n---\n"));
    }

    @Test
    void handlesNullText() {
        assertEquals(null, Frontmatter.strip("x.md", null));
    }
}
