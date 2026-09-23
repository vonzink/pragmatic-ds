package com.pragmaticds.rag.service.retrieval;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The editable retrieval-vocabulary document format: one {@code term => expansion}
 * per line; {@code #} comments and blank lines ignored; terms are lowercased.
 * Custom content fully replaces the pack-default synonym set.
 *
 * <p>{@link #validate} is strict (line-numbered errors) for the save path;
 * {@link #parse} is lenient (skips malformed lines) so persisted data can never
 * break live retrieval.
 */
public final class VocabularyText {

    private static final String SEP = "=>";

    private VocabularyText() {}

    /** Lenient parse for runtime use. Skips blank/comment/malformed lines. */
    public static Map<String, String> parse(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        if (text == null) {
            return map;
        }
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int sep = line.indexOf(SEP);
            if (sep < 0) {
                continue;
            }
            String term = line.substring(0, sep).strip().toLowerCase(Locale.US);
            String expansion = line.substring(sep + SEP.length()).strip();
            if (!term.isEmpty() && !expansion.isEmpty()) {
                map.put(term, expansion);
            }
        }
        return map;
    }

    /** Serialize to editable text, one {@code term => expansion} per line, sorted by term. */
    public static String serialize(Map<String, String> synonyms) {
        return synonyms.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + " " + SEP + " " + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    /** Strict validation for the save path. Throws with 1-based line numbers. */
    public static void validate(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Vocabulary must not be empty");
        }
        String[] lines = text.split("\n", -1);
        int entries = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int sep = line.indexOf(SEP);
            if (sep < 0) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": expected 'term => expansion'");
            }
            String term = line.substring(0, sep).strip();
            String expansion = line.substring(sep + SEP.length()).strip();
            if (term.isEmpty()) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": missing term before '=>'");
            }
            if (expansion.isEmpty()) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": missing expansion after '=>'");
            }
            if (!term.equals(term.toLowerCase(Locale.US))) {
                throw new IllegalArgumentException("Line " + (i + 1) + ": term must be lowercase: " + term);
            }
            entries++;
        }
        if (entries == 0) {
            throw new IllegalArgumentException("Vocabulary must contain at least one entry");
        }
    }
}
