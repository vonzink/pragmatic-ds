package com.pragmaticds.rag.service.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Locates the leading YAML frontmatter block of a markdown document and hands
 * back its interior lines. Shared by the typed accessors that read individual
 * keys ({@link FrontmatterVisibility}, {@link FrontmatterDocId}) so the fence
 * rules live in exactly one place.
 *
 * <p>Deliberately not a YAML parser: corpus frontmatter keys we act on are flat
 * scalars, and a hand-rolled scan cannot fail on an exotic document the way a
 * full parser can. Returns an empty list — never throws — when the file is not
 * markdown, is empty, or has no closed fence.
 */
final class Frontmatter {

    private Frontmatter() {
    }

    /**
     * @return the stripped lines between the opening and closing fence, or an
     *         empty list when there is no usable frontmatter block.
     */
    static List<String> lines(String fileName, byte[] fileBytes) {
        if (fileName == null || fileBytes == null || !isMarkdown(fileName)) {
            return List.of();
        }
        String[] all = splitLines(new String(fileBytes, StandardCharsets.UTF_8));
        int close = closingFence(all);
        if (close < 0) {
            return List.of();
        }
        List<String> block = new ArrayList<>(close - 1);
        for (int i = 1; i < close; i++) {
            block.add(all[i].strip());
        }
        return block;
    }

    /**
     * Removes the leading frontmatter block from a markdown document's extracted
     * text so the chunker only ever sees the body. Text extraction treats markdown
     * as plain text, so without this the fence and its keys became the document's
     * first chunk — embedded, searchable, and (when the block is identical across
     * files) reported as duplicate chunk text.
     *
     * @return the body after the closing fence, stripped; or {@code text} itself
     *         (same instance) when the file is not markdown or has no closed fence.
     */
    static String strip(String fileName, String text) {
        if (text == null || fileName == null || !isMarkdown(fileName)) {
            return text;
        }
        String[] all = splitLines(text);
        int close = closingFence(all);
        if (close < 0) {
            return text;
        }
        return String.join("\n", Arrays.copyOfRange(all, close + 1, all.length)).strip();
    }

    private static String[] splitLines(String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return text.split("\r?\n", -1);
    }

    /**
     * @return the index of the line that closes a leading frontmatter fence, or -1.
     *         The fence must close for the block to count as frontmatter — a lone
     *         leading --- is just a horizontal rule, and the "keys" after it are body.
     */
    private static int closingFence(String[] all) {
        if (all.length == 0 || !all[0].strip().equals("---")) {
            return -1;
        }
        for (int i = 1; i < all.length; i++) {
            String line = all[i].strip();
            if (line.equals("---") || line.equals("...")) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMarkdown(String fileName) {
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }
}
