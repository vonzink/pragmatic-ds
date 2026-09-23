package com.pragmaticds.rag.service.ingestion;

/**
 * Intermediate result from the chunker, before embedding and persistence.
 *
 * @param index       0-based position of this chunk within the document
 * @param content     the chunk text
 * @param tokenCount  token count measured with the cl100k_base tokenizer
 * @param heading     nearest preceding heading, if detected (for citations)
 * @param type        PARENT section context or CHILD retrieval chunk
 * @param parentIndex chunk_index of the parent section chunk for CHILD rows
 * @param path        human-readable heading path
 * @param level       hierarchy level, 0 for parent sections and 1 for retrieval children
 */
public record TextChunk(int index, String content, int tokenCount, String heading,
                        ChunkType type, Integer parentIndex, String path, Integer level) {

    public enum ChunkType {
        PARENT,
        CHILD
    }

    public TextChunk(int index, String content, int tokenCount, String heading) {
        this(index, content, tokenCount, heading, ChunkType.CHILD, null, heading, 1);
    }
}
