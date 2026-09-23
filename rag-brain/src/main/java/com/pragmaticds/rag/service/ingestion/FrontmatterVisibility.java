package com.pragmaticds.rag.service.ingestion;

import com.pragmaticds.rag.domain.SourceVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code visibility:} key from a markdown document's leading YAML
 * frontmatter block. Corpus documents self-declare their visibility this way
 * (e.g. {@code visibility: PUBLIC}); the ingestion pipeline consults it only
 * when the caller supplied no explicit visibility, so a sync no longer
 * silently downgrades PUBLIC corpus docs to INTERNAL.
 *
 * <p>Returns null — never a default — when the file is not markdown, has no
 * closed frontmatter fence, or declares no recognizable visibility; callers
 * keep their own conservative fallback (INTERNAL).
 */
final class FrontmatterVisibility {

    private static final Logger log = LoggerFactory.getLogger(FrontmatterVisibility.class);

    /** A top-level {@code visibility:} line; value may be bare or quoted. */
    private static final Pattern VISIBILITY_LINE =
            Pattern.compile("^visibility:\\s*[\"']?([A-Za-z]+)[\"']?\\s*$");

    private FrontmatterVisibility() {
    }

    static SourceVisibility parse(String fileName, byte[] fileBytes) {
        for (String line : Frontmatter.lines(fileName, fileBytes)) {
            Matcher m = VISIBILITY_LINE.matcher(line);
            if (m.matches()) {
                try {
                    return SourceVisibility.valueOf(m.group(1).toUpperCase(Locale.US));
                } catch (IllegalArgumentException e) {
                    log.warn("Ignoring unrecognized frontmatter visibility '{}' in {}",
                            m.group(1), fileName);
                    return null;
                }
            }
        }
        return null;
    }
}
