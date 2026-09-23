package com.pragmaticds.rag.service.ingestion;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.pragmaticds.rag.config.RagProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Recursive, paragraph-aware chunker.
 *
 * Strategy (per rag.md):
 * - split on paragraph boundaries first, never mid-sentence when avoidable
 * - target ~1000 tokens per chunk, hard max 1200
 * - carry ~150 tokens of overlap between consecutive chunks so guideline
 *   rules that span a boundary are retrievable from either side
 * - track the nearest heading so each chunk can cite its section
 */
@Service
public class ChunkingService {

    // Matches markdown headings and ALL-CAPS / numbered section lines
    // commonly produced by Tika from guideline PDFs.
    private static final Pattern HEADING = Pattern.compile(
            "^(#{1,6}\\s+.+|[A-Z][A-Z0-9 ,/&-]{8,}|\\d+(\\.\\d+)*\\s+[A-Z].{3,80})$");

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    private final int targetTokens;
    private final int maxTokens;
    private final int overlapTokens;

    public ChunkingService(RagProperties properties) {
        this.targetTokens = properties.chunking().targetTokens();
        this.maxTokens = properties.chunking().maxTokens();
        this.overlapTokens = properties.chunking().overlapTokens();
    }

    public List<TextChunk> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentHeading = null;
        String chunkHeading = null;
        // Whether `current` holds any non-heading body yet. Guards the section-
        // boundary flush so a run of consecutive headings does not emit a chunk
        // that is only a heading line with no content.
        boolean currentHasBody = false;
        int index = 0;

        for (String paragraph : toBlocks(text)) {
            String para = paragraph.strip();
            if (para.isEmpty()) {
                continue;
            }
            boolean isHeading = HEADING.matcher(para).matches();
            if (isHeading) {
                currentHeading = para.replaceFirst("^#{1,6}\\s+", "").strip();
                // Section boundary: start a new retrieval chunk at each heading so
                // each section embeds around its own topic instead of being blended
                // with neighbouring sections into one ~targetTokens block (which
                // dilutes the embedding and collapses retrieval confidence to 0).
                // No overlap is carried across a semantic boundary — that would
                // just re-mix the sections we are separating.
                if (currentHasBody) {
                    chunks.add(new TextChunk(index++, current.toString().strip(),
                            countTokens(current.toString()), chunkHeading));
                    current = new StringBuilder();
                    currentHasBody = false;
                }
                // No body has committed to the previous heading yet, so the newest
                // heading is the one that labels the upcoming body. Covers a title
                // (or any heading) immediately followed by another heading.
                chunkHeading = currentHeading;
            }

            int paraTokens = countTokens(para);
            int currentTokens = countTokens(current.toString());

            // Oversized single paragraph (often a table): split by sentences.
            if (paraTokens > maxTokens) {
                if (!current.isEmpty()) {
                    chunks.add(new TextChunk(index++, current.toString().strip(),
                            currentTokens, chunkHeading));
                    current = new StringBuilder(overlapTail(current.toString()));
                }
                for (String piece : splitOversized(para)) {
                    chunks.add(new TextChunk(index++, piece, countTokens(piece), currentHeading));
                }
                chunkHeading = currentHeading;
                currentHasBody = current.length() > 0;
                continue;
            }

            if (currentTokens + paraTokens > targetTokens && !current.isEmpty()) {
                chunks.add(new TextChunk(index++, current.toString().strip(),
                        currentTokens, chunkHeading));
                current = new StringBuilder(overlapTail(current.toString()));
                chunkHeading = currentHeading;
                currentHasBody = current.length() > 0;
            }

            if (current.isEmpty()) {
                chunkHeading = currentHeading;
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(para);
            if (!isHeading) {
                currentHasBody = true;
            }
        }

        if (!current.toString().isBlank()) {
            String content = current.toString().strip();
            chunks.add(new TextChunk(index, content, countTokens(content), chunkHeading));
        }
        return chunks;
    }

    /**
     * Returns parent section chunks plus child retrieval chunks. Parent chunks
     * are stored without embeddings and provide broader context for answer
     * assembly after a child chunk is retrieved.
     */
    public List<TextChunk> chunkHierarchical(String text) {
        List<TextChunk> children = chunk(text);
        if (children.isEmpty()) {
            return List.of();
        }

        List<TextChunk> out = new ArrayList<>();
        int i = 0;
        while (i < children.size()) {
            String heading = children.get(i).heading();
            String path = heading == null || heading.isBlank() ? "Document" : heading;
            int start = i;
            while (i < children.size() && java.util.Objects.equals(children.get(i).heading(), heading)) {
                i++;
            }
            List<TextChunk> group = children.subList(start, i);
            String parentContent = group.stream()
                    .map(TextChunk::content)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
            int parentIndex = out.size();
            out.add(new TextChunk(parentIndex, parentContent, countTokens(parentContent), heading,
                    TextChunk.ChunkType.PARENT, null, path, 0));
            for (TextChunk child : group) {
                out.add(new TextChunk(out.size(), child.content(), child.tokenCount(), child.heading(),
                        TextChunk.ChunkType.CHILD, parentIndex, path, 1));
            }
        }
        return out;
    }

    /**
     * Splits the document into blocks on blank lines, then peels any heading LINE
     * out of the block it leads so every heading stands alone. Well-formed markdown
     * already blank-line-separates headings, but FAQ and Tika/PDF text often glues a
     * heading to its body ("## How do I X?\nDo Y."). Without this, that heading+body
     * is one block that fails the whole-block heading test, so section splitting
     * never fires and the sections blend into one diluted chunk. Peeling the heading
     * line into its own block lets the section-boundary flush in {@link #chunk}
     * separate each section (e.g. each FAQ Q&amp;A) as its own retrieval chunk.
     */
    private List<String> toBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        for (String raw : text.split("\n\n")) {
            StringBuilder body = new StringBuilder();
            for (String line : raw.split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && HEADING.matcher(stripped).matches()) {
                    if (body.length() > 0) {
                        blocks.add(body.toString());
                        body.setLength(0);
                    }
                    blocks.add(stripped);
                } else {
                    if (body.length() > 0) {
                        body.append("\n");
                    }
                    body.append(line);
                }
            }
            if (body.length() > 0) {
                blocks.add(body.toString());
            }
        }
        return blocks;
    }

    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /** Last ~overlapTokens worth of text, cut at a sentence boundary when possible. */
    private String overlapTail(String text) {
        var tokens = encoding.encode(text);
        if (tokens.size() <= overlapTokens) {
            return text;
        }
        var tailTokens = new com.knuddels.jtokkit.api.IntArrayList(overlapTokens);
        for (int i = tokens.size() - overlapTokens; i < tokens.size(); i++) {
            tailTokens.add(tokens.get(i));
        }
        String tail = encoding.decode(tailTokens);
        int sentenceStart = tail.indexOf(". ");
        return sentenceStart >= 0 ? tail.substring(sentenceStart + 2) : tail;
    }

    /** Sentence-level split for paragraphs that exceed the hard max (e.g. big tables). */
    private List<String> splitOversized(String paragraph) {
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (String sentence : paragraph.split("(?<=[.!?])\\s+|\n")) {
            if (countTokens(piece.toString()) + countTokens(sentence) > targetTokens
                    && !piece.isEmpty()) {
                pieces.add(piece.toString().strip());
                piece = new StringBuilder();
            }
            if (!piece.isEmpty()) {
                piece.append(' ');
            }
            piece.append(sentence);
        }
        if (!piece.toString().isBlank()) {
            pieces.add(piece.toString().strip());
        }
        return pieces;
    }
}
