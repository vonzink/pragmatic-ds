package com.pragmaticds.rag.service.ingestion;

import com.pragmaticds.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkingServiceTest {

    private ChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties(
                new RagProperties.Routing("anthropic", "openai"),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24, true, 0.0),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/test"),
                new RagProperties.Admin("test-key"),
                new RagProperties.Analyze(null),
                new RagProperties.RateLimit(10, 60, 120));
        chunkingService = new ChunkingService(properties);
    }

    @Test
    void emptyTextProducesNoChunks() {
        assertTrue(chunkingService.chunk("").isEmpty());
        assertTrue(chunkingService.chunk(null).isEmpty());
    }

    @Test
    void shortTextProducesSingleChunk() {
        List<TextChunk> chunks = chunkingService.chunk(
                "Gift funds may be used for down payment on a primary residence.");
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.getFirst().index());
        assertTrue(chunks.getFirst().tokenCount() > 0);
    }

    @Test
    void longTextRespectsMaxTokenLimit() {
        String paragraph = "Borrower income must be documented with W-2 forms, pay stubs, "
                + "and verification of employment covering the most recent two-year period. ";
        String text = (paragraph + "\n\n").repeat(200);

        List<TextChunk> chunks = chunkingService.chunk(text);

        assertTrue(chunks.size() > 1, "Long text should produce multiple chunks");
        for (TextChunk chunk : chunks) {
            assertTrue(chunk.tokenCount() <= 1200,
                    "Chunk " + chunk.index() + " exceeds max tokens: " + chunk.tokenCount());
        }
    }

    @Test
    void chunkIndexesAreSequential() {
        String text = ("Reserves are funds remaining after closing. ".repeat(60) + "\n\n").repeat(30);
        List<TextChunk> chunks = chunkingService.chunk(text);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index());
        }
    }

    @Test
    void consecutiveChunksShareOverlapText() {
        String text = ("Rental income from a departing residence may be considered when "
                + "the borrower has documented equity. ".repeat(40) + "\n\n").repeat(10);
        List<TextChunk> chunks = chunkingService.chunk(text);
        assertTrue(chunks.size() >= 2);

        // The start of chunk N+1 should repeat text from the end of chunk N.
        String tailOfFirst = chunks.get(0).content()
                .substring(Math.max(0, chunks.get(0).content().length() - 200));
        String startOfSecond = chunks.get(1).content().substring(0,
                Math.min(200, chunks.get(1).content().length()));
        assertTrue(sharesAnySentence(tailOfFirst, startOfSecond),
                "Expected overlap between consecutive chunks");
    }

    @Test
    void detectsMarkdownHeadingsAsSections() {
        String text = """
                ## B3-3.1-01 Overtime Income

                Overtime income may be used when it has a consistent two-year history.
                """;
        List<TextChunk> chunks = chunkingService.chunk(text);
        assertEquals(1, chunks.size());
        assertNotNull(chunks.getFirst().heading());
        assertTrue(chunks.getFirst().heading().contains("Overtime Income"));
    }

    @Test
    void splitsEachHeadingSectionIntoItsOwnChunk() {
        String text = """
                ## What is DTI?

                DTI is the ratio of monthly debt payments to gross monthly income.

                ## What is LTV?

                LTV is the loan amount relative to the property value.

                ## What is PITI?

                PITI is principal, interest, taxes, and insurance.
                """;

        List<TextChunk> chunks = chunkingService.chunk(text);

        assertEquals(3, chunks.size(), "each heading section should be its own chunk");
        assertTrue(chunks.get(0).content().contains("DTI"));
        assertTrue(chunks.get(1).content().contains("LTV"));
        assertTrue(chunks.get(2).content().contains("PITI"));
        // the heading line stays inside the chunk content, so it is embedded too
        assertTrue(chunks.get(1).content().contains("## What is LTV?"));
    }

    @Test
    void splitsAtSectionHeadingsSoEachSectionIsItsOwnChunk() {
        // A short guide whose sections together stay well under targetTokens (1000).
        // The old size-only chunker packed the whole thing into ONE blended chunk,
        // diluting each section's embedding so retrieval scored 0. Each ## section
        // must become its own retrieval chunk.
        String doc = """
                # Using the Console

                Intro to the staff console guide.

                ## Creating a loan

                New loans are created from the pipeline using the Create button. MARKER_CREATE.

                ## Pricing and lock

                Run pricing and lock a rate in the pricing tool. MARKER_PRICING.

                ## Mortgage terms glossary

                DTI means debt-to-income ratio. MARKER_GLOSSARY.
                """;

        List<TextChunk> chunks = chunkingService.chunk(doc);

        // One chunk per ## section (plus the title/intro) — not a single blend.
        assertTrue(chunks.size() >= 4,
                "Expected a chunk per section, got " + chunks.size());

        // Each section's unique marker lands in a DIFFERENT chunk: no chunk may
        // contain two section markers (that would mean sections were blended).
        List<String> markers = List.of("MARKER_CREATE", "MARKER_PRICING", "MARKER_GLOSSARY");
        for (TextChunk chunk : chunks) {
            long markersInChunk = markers.stream().filter(m -> chunk.content().contains(m)).count();
            assertTrue(markersInChunk <= 1,
                    "Section blending: chunk holds >1 section marker: " + chunk.content());
        }

        // The section heading travels with its content (for citation + embedding).
        TextChunk createChunk = chunks.stream()
                .filter(c -> c.content().contains("MARKER_CREATE")).findFirst().orElseThrow();
        assertTrue(createChunk.content().contains("Creating a loan"),
                "Section chunk should carry its heading text");
        assertEquals("Creating a loan", createChunk.heading());
    }

    @Test
    void splitsHeadingsEvenWhenGluedToBodyWithNoBlankLine() {
        // Real FAQ / Tika-PDF style: the answer follows the "## question" on the
        // very next line with no blank line, so heading+body land in ONE \n\n block.
        // A block-level heading check misses it and blends every Q&A into one chunk.
        String faq = "# Staff FAQ\n\n"
                + "## How do I create a new loan?\n"
                + "Use the Create button on the Pipeline. MARKER_CREATE.\n\n"
                + "## How do I import a MISMO file?\n"
                + "Use Import MISMO on the Pipeline. MARKER_MISMO.\n\n"
                + "## How do I search for a loan?\n"
                + "Use the Pipeline search box. MARKER_SEARCH.\n";

        List<TextChunk> chunks = chunkingService.chunk(faq);

        assertTrue(chunks.size() >= 3, "Each Q&A should be its own chunk, got " + chunks.size());
        List<String> markers = List.of("MARKER_CREATE", "MARKER_MISMO", "MARKER_SEARCH");
        for (TextChunk chunk : chunks) {
            long n = markers.stream().filter(m -> chunk.content().contains(m)).count();
            assertTrue(n <= 1, "Q&A blending: chunk holds >1 marker: " + chunk.content());
        }
        TextChunk create = chunks.stream()
                .filter(c -> c.content().contains("MARKER_CREATE")).findFirst().orElseThrow();
        assertTrue(create.content().contains("create a new loan"),
                "Question heading must travel with its answer");
        assertEquals("How do I create a new loan?", create.heading());
    }

    @Test
    void tokenCountingWorks() {
        assertEquals(0, chunkingService.countTokens(""));
        assertFalse(chunkingService.countTokens("What is PMI?") == 0);
    }

    private boolean sharesAnySentence(String a, String b) {
        for (String sentence : a.split("(?<=[.!?])\\s+")) {
            String s = sentence.strip();
            if (s.length() > 20 && b.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
