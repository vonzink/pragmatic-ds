package com.pragmaticds.rag.service.sync;

import java.util.List;
import java.util.Map;

/**
 * What POST /api/ai/documents/refresh-metadata returns: per-file outcome of
 * re-parsing a corpus file's front-matter {@code document_id:} and comparing
 * it to the stored {@code external_doc_id}. Mirrors the shape/style of
 * {@link SyncReport} so the dashboard and CLI can consume both consistently.
 *
 * <p>This is metadata-only: {@code UPDATED} means exactly one column
 * ({@code external_doc_id}) was written on an existing row. Nothing here ever
 * creates, deletes, or re-embeds a chunk, and no other document column is
 * ever touched — see {@link SyncService#refreshMetadata}.
 */
public record MetadataRefreshReport(boolean dryRun,
                                    Map<String, Integer> summary,
                                    List<Result> results) {

    public enum Action { UPDATED, UNCHANGED, NO_DOCUMENT, NO_FRONTMATTER_ID }

    public record Result(String fileName, Action action, String reason,
                         String previousExternalDocId, String newExternalDocId) {}
}
