package com.pragmaticds.docengine.extraction.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The bank_statement schema definition THAT SHIPS — read from the migrations in {@code
 * app/src/main/resources/db/migration}, never transcribed, for the same reason {@link
 * V7PaystubSeed} reads V7: a hand-typed copy is a copy that drifts, and a test asserting on
 * drifted data proves nothing about what actually runs.
 *
 * <p>Unlike {@code V7PaystubSeed} this is not pinned to one migration file. It collects every
 * {@code (NULL, 'BANK_STATEMENT', '<version>', '<json>'::jsonb)} tuple across all migrations and
 * returns the HIGHEST version — which is exactly what {@link ExtractionSchemaLoader} picks among
 * the active rows, because every superseded row is retired by the same migration that supersedes
 * it. A test bound to a fixed V-number would keep guarding a RETIRED version the day a correction
 * lands, and a check that cannot fail is not protection.
 */
public final class ShippedBankStatementSeed {

    private ShippedBankStatementSeed() {}

    private static final String TYPE_MARKER = "'BANK_STATEMENT', '";

    /** The version of the newest shipped bank_statement seed, e.g. {@code "1.2.0"}. */
    public static final String VERSION;

    /** That seed's jsonb definition literal, SQL {@code ''} un-escaped. */
    public static final String DEFINITION;

    static {
        Seed newest =
                migrations().stream()
                        .flatMap(ShippedBankStatementSeed::seedsIn)
                        .max(Comparator.comparing(seed -> versionKey(seed.version)))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "no migration seeds a BANK_STATEMENT extraction"
                                                        + " schema"));
        VERSION = newest.version;
        DEFINITION = newest.definition;
    }

    private record Seed(String version, String definition) {}

    private static Stream<Seed> seedsIn(Path migration) {
        String sql;
        try {
            sql = Files.readString(migration);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + migration, e);
        }
        Stream.Builder<Seed> seeds = Stream.builder();
        for (int at = sql.indexOf(TYPE_MARKER); at >= 0; at = sql.indexOf(TYPE_MARKER, at + 1)) {
            int versionStart = at + TYPE_MARKER.length();
            int versionEnd = sql.indexOf('\'', versionStart);
            int open = sql.indexOf('{', versionEnd);
            int close = sql.indexOf("'::jsonb", versionEnd);
            if (versionEnd < 0 || open < 0 || close < 0 || open > close) {
                continue; // a mention that is not a seeding tuple (a comment, an UPDATE)
            }
            // Inside a SQL string literal a single quote is doubled; the JSON itself is verbatim
            // (backslashes are not SQL escapes under standard_conforming_strings).
            seeds.add(
                    new Seed(
                            sql.substring(versionStart, versionEnd),
                            sql.substring(open, close).replace("''", "'")));
        }
        return seeds.build();
    }

    /** Zero-padded so a plain string compare orders 1.10.0 after 1.2.0. */
    private static String versionKey(String version) {
        StringBuilder key = new StringBuilder();
        for (String segment : version.split("\\.")) {
            key.append(String.format("%09d.", Integer.parseInt(segment.trim())));
        }
        return key.toString();
    }

    private static List<Path> migrations() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("app/src/main/resources/db/migration");
            if (Files.isDirectory(candidate)) {
                try (Stream<Path> files = Files.list(candidate)) {
                    return files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                            .sorted()
                            .toList();
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot list " + candidate, e);
                }
            }
        }
        throw new IllegalStateException(
                "db/migration not found above " + System.getProperty("user.dir"));
    }
}
