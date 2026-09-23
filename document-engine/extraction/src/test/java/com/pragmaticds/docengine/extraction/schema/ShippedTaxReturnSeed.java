package com.pragmaticds.docengine.extraction.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The tax_return schema definition THAT SHIPS — read from the migrations in {@code
 * app/src/main/resources/db/migration}, never transcribed, for the same reason {@link
 * ShippedBankStatementSeed} reads them: a hand-typed copy is a copy that drifts, and a test
 * asserting on drifted data proves nothing about what actually runs.
 *
 * <p>Not pinned to one migration file. It collects every {@code (NULL, 'TAX_RETURN', '<version>',
 * '<json>'::jsonb)} tuple across all migrations and returns the HIGHEST version — which is exactly
 * what {@link ExtractionSchemaLoader} picks among the active rows, because every superseded row is
 * retired by the same migration that supersedes it. A test bound to a fixed V-number would keep
 * guarding a RETIRED version the day a correction lands, and a check that cannot fail is not
 * protection.
 */
public final class ShippedTaxReturnSeed {

    private ShippedTaxReturnSeed() {}

    private static final String TYPE_MARKER = "'TAX_RETURN', '";

    /**
     * {@code TAX_RETURN} is a document_type CODE, so the marker above also hits two tuples that
     * are not extraction schemas at all: the {@code document_type} row seeded in V11 (whose next
     * column is the DISPLAY NAME "Individual Income Tax Return", not a version) and every {@code
     * classification_rule_pack} row, which carries its own {@code '1.1.0'} and its own jsonb.
     * Reading either one hands a test the wrong document under a plausible-looking version — the
     * rule pack's version even sorts against the schema's — so the owning table is checked, not
     * inferred. Deliberately anchored on {@code INSERT INTO}: an {@code UPDATE ... WHERE
     * document_type_code = 'TAX_RETURN'} (the retire half of every supersession) has no version
     * column to misread and must be skipped whatever table it names.
     */
    private static final String INSERT_MARKER = "INSERT INTO extraction_schema";

    /** A dotted numeric version, and nothing else — the second half of the same guard. */
    private static final java.util.regex.Pattern SEMVER =
            java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)*");

    /** The version of the newest shipped tax_return seed, e.g. {@code "1.2.0"}. */
    public static final String VERSION;

    /** That seed's jsonb definition literal, SQL {@code ''} un-escaped. */
    public static final String DEFINITION;

    static {
        Seed newest =
                migrations().stream()
                        .flatMap(ShippedTaxReturnSeed::seedsIn)
                        .max(Comparator.comparing(seed -> versionKey(seed.version)))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "no migration seeds a TAX_RETURN extraction"
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
            String version = sql.substring(versionStart, versionEnd);
            if (!SEMVER.matcher(version).matches() || !ownedByExtractionSchema(sql, at)) {
                continue; // a document_type row, a rule pack, or any other table's tuple
            }
            // Inside a SQL string literal a single quote is doubled; the JSON itself is verbatim
            // (backslashes are not SQL escapes under standard_conforming_strings).
            seeds.add(new Seed(version, sql.substring(open, close).replace("''", "'")));
        }
        return seeds.build();
    }

    /**
     * Whether the statement this tuple sits in is an {@code INSERT INTO extraction_schema}: the
     * nearest {@code INSERT INTO} above the tuple must be that one, and no statement terminator
     * may separate them. A bare "is {@code INSERT_MARKER} anywhere in this file" test would pass
     * for every migration that seeds a schema AND a rule pack — which is most of them.
     */
    private static boolean ownedByExtractionSchema(String sql, int tupleAt) {
        int insertAt = sql.lastIndexOf("INSERT INTO", tupleAt);
        if (insertAt < 0 || !sql.startsWith(INSERT_MARKER, insertAt)) {
            return false;
        }
        return sql.lastIndexOf(';', tupleAt) < insertAt;
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
