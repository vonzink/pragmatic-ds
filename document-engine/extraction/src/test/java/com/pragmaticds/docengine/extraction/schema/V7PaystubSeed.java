package com.pragmaticds.docengine.extraction.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The paystub@1.0.0 built-in schema definition, READ FROM the shipped migration
 * {@code app/src/main/resources/db/migration/V7__extraction.sql} rather than transcribed.
 *
 * <p>This was a hand-typed copy carrying a "keep in lockstep" comment. A copy that nothing
 * compares is a copy that drifts, and a test asserting on drifted data proves nothing about what
 * actually ships — the Phase 2 lesson (fixture truth that shared the code's wrong assumption) in
 * another costume. Reading the migration makes "these tests parse the REAL shipped schema" true
 * by construction rather than by discipline.
 */
public final class V7PaystubSeed {

    private V7PaystubSeed() {}

    /** The jsonb definition literal of the seeded paystub schema, SQL {@code ''} un-escaped. */
    public static final String DEFINITION = readSeedDefinition();

    private static String readSeedDefinition() {
        String sql;
        try {
            sql = Files.readString(migrationPath());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read V7__extraction.sql", e);
        }
        int insert = sql.indexOf("INSERT INTO extraction_schema");
        if (insert < 0) {
            throw new IllegalStateException("V7 no longer seeds extraction_schema");
        }
        int close = sql.indexOf("'::jsonb", insert);
        int open = sql.indexOf('{', insert);
        if (close < 0 || open < 0 || open > close) {
            throw new IllegalStateException("V7 seed is no longer a '{...}'::jsonb literal");
        }
        // Inside a SQL string literal a single quote is doubled; the JSON itself is verbatim
        // (backslashes are not SQL escapes under standard_conforming_strings).
        return sql.substring(open, close).replace("''", "'");
    }

    private static Path migrationPath() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("app/src/main/resources/db/migration/V7__extraction.sql");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "V7__extraction.sql not found above " + System.getProperty("user.dir"));
    }
}
