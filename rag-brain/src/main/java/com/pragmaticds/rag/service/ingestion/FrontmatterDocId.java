package com.pragmaticds.rag.service.ingestion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code document_id:} key from a markdown document's leading YAML
 * frontmatter block. Corpus documents self-declare a stable identifier this way
 * (e.g. {@code document_id: suite_page_pipeline_board}); it is the id the corpus
 * retrieval cases assert against, so persisting it lets an eval join a retrieved
 * chunk back to the doc that was supposed to answer.
 *
 * <p>Returns null — never a default — when the file is not markdown, has no
 * closed frontmatter fence, or declares no {@code document_id}.
 *
 * <p>Public so {@link com.pragmaticds.rag.service.sync.SyncService}'s
 * metadata-only refresh can re-derive the same id straight from freshly
 * downloaded bytes without duplicating this parsing — and, just as
 * important, without routing through {@code DocumentIngestionService} at
 * all, so that path can never accidentally re-chunk or re-embed.
 */
public final class FrontmatterDocId {

    /** A top-level {@code document_id:} line; value may be bare or quoted. */
    private static final Pattern DOC_ID_LINE =
            Pattern.compile("^document_id:\\s*[\"']?([A-Za-z0-9_.:-]+)[\"']?\\s*$");

    private FrontmatterDocId() {
    }

    public static String parse(String fileName, byte[] fileBytes) {
        for (String line : Frontmatter.lines(fileName, fileBytes)) {
            Matcher m = DOC_ID_LINE.matcher(line);
            if (m.matches()) {
                return m.group(1);
            }
        }
        return null;
    }
}
