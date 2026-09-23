package com.pragmaticds.docengine.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The consumer contract's §5 coverage table must say exactly what the migrations seed.
 *
 * <h2>The failure this exists to catch, which actually happened</h2>
 *
 * <p>§5 is the one place a consumer learns which document types the engine can name, at which
 * rule-pack and schema version, with how many fields. It is hand-written. It once listed ten types as
 * "the entire list" while the engine seeded thirty-four, and marked {@code VOE}, {@code
 * SSA_AWARD_LETTER}, the K-1s and Schedules B/C/D/F "not available" long after each shipped. It was
 * rebuilt from the database on 2026-09-14 (#75) — and was stale again the next day, when V47–V49
 * moved {@code TAX_RETURN}, {@code W2}, {@code PAYSTUB} and {@code SCHEDULE_C} without a line of
 * {@code docs/consumer} changing. Reading it by hand is not a control. This test is the control.
 *
 * <h2>Which rows count</h2>
 *
 * <p>Global ({@code org_id IS NULL}), active rows only — the built-ins every org sees unless it
 * authors its own (guide rule 11). And only rows a migration seeded: other ITs insert global types
 * and packs ({@code LETTER}, {@code CONFUSABLE}) or promote a fixture schema to global ({@code
 * EngineResultSnapshotIT}), and those rows are test data. A type counts when its code is a literal in
 * some migration; a pack or schema when its {@code 'CODE', 'VERSION'} pair is.
 *
 * <p>Not {@code created_at <= max(flyway installed_on)}, which was the first attempt and is wrong:
 * measured, Flyway stamps a migration's {@code installed_on} a few milliseconds BEFORE that
 * migration's own rows are created, so the filter silently dropped every row the LAST migration
 * seeded ({@code tax_return@1.4.0}, V49) and passed everything older.
 *
 * <p>Runs whenever a migration changes (they live in {@code app/}) and whenever the contract does
 * ({@code ^docs/consumer/} is in CI's backend filter for this test).
 */
class ConsumerCoverageTableIT extends AbstractPostgresIT {

    private static final Path CONTRACT = Path.of("../docs/consumer/income-extraction-contract.md");

    /**
     * {@code | CATEGORY | `CODE` optional note | pack | schema | fields |}. A type seeded with NO
     * extraction schema (V51's triage-only types) writes {@code —} in both schema columns, which
     * compares equal to the database side's absent schema.
     */
    private static final Pattern ROW =
            Pattern.compile(
                    "^\\|\\s*([A-Z]+)\\s*\\|\\s*`([A-Z0-9_]+)`[^|]*\\|\\s*([0-9][^|]*?)\\s*\\|"
                            + "\\s*([0-9][^|]*?|—)\\s*\\|\\s*(\\d+|—)\\s*\\|\\s*$");

    /** The table's spelling of "no schema seeded". */
    private static final String NO_SCHEMA = "—";

    /** The headline count above the table. */
    private static final Pattern COUNT =
            Pattern.compile("\\*\\*(\\d+) document types plus `UNKNOWN`\\.\\*\\*");

    private static final String TYPES =
            "SELECT code, category FROM document_type"
                    + " WHERE org_id IS NULL AND is_active AND code <> 'UNKNOWN'";

    private static final String PACKS =
            "SELECT document_type_code AS code, version FROM classification_rule_pack"
                    + " WHERE org_id IS NULL AND is_active";

    private static final String SCHEMAS =
            "SELECT document_type_code AS code, version,"
                    + " jsonb_array_length(coalesce(definition -> 'fields', '[]'::jsonb)) AS fields"
                    + " FROM extraction_schema WHERE org_id IS NULL AND is_active";

    @Test
    void the_contracts_coverage_table_is_what_the_migrations_seed() {
        String contract = contract();
        Map<String, String> documented = documentedRows(section5(contract));
        Map<String, String> seeded = seededRows();

        List<String> drift = new ArrayList<>();
        TreeSet<String> codes = new TreeSet<>(documented.keySet());
        codes.addAll(seeded.keySet());
        for (String code : codes) {
            String inContract = documented.get(code);
            String inDatabase = seeded.get(code);
            if (inContract == null || !inContract.equals(inDatabase)) {
                drift.add(
                        code
                                + ": contract="
                                + (inContract == null ? "<no row>" : inContract)
                                + " database="
                                + (inDatabase == null ? "<not seeded>" : inDatabase));
            }
        }

        String fix =
                " Rows read CATEGORY|rule pack|schema|fields. Update the rows in"
                        + " docs/consumer/income-extraction-contract.md §5 to what the migrations seed"
                        + " (never a migration to match the table), add a dated revision note, then"
                        + " re-record contract-sha256 in consumer-integration-guide.md after re-reading"
                        + " the diff.";
        assertThat(drift).as("§5 coverage table disagrees with the migrated database." + fix).isEmpty();

        Matcher count = COUNT.matcher(section5(contract));
        assertThat(count.find()).as("§5 must state '**N document types plus `UNKNOWN`.**'").isTrue();
        assertThat(Integer.parseInt(count.group(1)))
                .as("§5's headline type count must match the seeded types." + fix)
                .isEqualTo(seeded.size());
    }

    private Map<String, String> seededRows() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String migrations = migrationSql();

        Map<String, String> categories = new TreeMap<>();
        jdbc.query(
                TYPES,
                rs -> {
                    String code = rs.getString("code");
                    if (migrations.contains("'" + code + "'")) {
                        categories.put(code, rs.getString("category"));
                    }
                });

        Map<String, TreeSet<String>> packs = new TreeMap<>();
        jdbc.query(
                PACKS,
                rs -> {
                    String code = rs.getString("code");
                    String version = rs.getString("version");
                    if (seededPair(migrations, code, version)) {
                        packs.computeIfAbsent(code, c -> new TreeSet<>()).add(version);
                    }
                });

        Map<String, TreeMap<String, String>> schemas = new TreeMap<>();
        jdbc.query(
                SCHEMAS,
                rs -> {
                    String code = rs.getString("code");
                    String version = rs.getString("version");
                    if (seededPair(migrations, code, version)) {
                        schemas.computeIfAbsent(code, c -> new TreeMap<>())
                                .put(version, rs.getString("fields"));
                    }
                });

        Map<String, String> rows = new TreeMap<>();
        categories.forEach(
                (code, category) -> {
                    TreeMap<String, String> schemaVersions = schemas.get(code);
                    rows.put(
                            code,
                            category
                                    + "|"
                                    + (packs.containsKey(code) ? String.join(",", packs.get(code)) : null)
                                    + "|"
                                    + (schemaVersions == null
                                            ? null
                                            : String.join(",", schemaVersions.keySet()))
                                    + "|"
                                    + (schemaVersions == null
                                            ? null
                                            : String.join(",", schemaVersions.values())));
                });
        assertThat(rows).as("the migrated database seeds no document types at all").isNotEmpty();
        return rows;
    }

    /**
     * A migration seeds a pack or schema row as {@code (…, 'CODE', 'VERSION', …)}. A row whose pair
     * appears in no migration was written by a test. The unique index on
     * {@code (coalesce(org_id), document_type_code, version)} guarantees a test cannot create a
     * second global row with a seeded pair, so the pair identifies a seeded row exactly.
     */
    private static boolean seededPair(String migrations, String code, String version) {
        return Pattern.compile("'" + Pattern.quote(code) + "'\\s*,\\s*'" + Pattern.quote(version) + "'")
                .matcher(migrations)
                .find();
    }

    /** Every Flyway migration on the classpath, concatenated. */
    private static String migrationSql() {
        try {
            StringBuilder sql = new StringBuilder();
            for (org.springframework.core.io.Resource migration :
                    new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                            .getResources("classpath*:db/migration/*.sql")) {
                sql.append(
                                new String(
                                        migration.getInputStream().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8))
                        .append('\n');
            }
            assertThat(sql).as("no Flyway migrations found on the classpath").isNotEmpty();
            return sql.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> documentedRows(String section) {
        Map<String, String> rows = new TreeMap<>();
        for (String line : section.split("\n")) {
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                String previous =
                        rows.put(
                                row.group(2),
                                row.group(1)
                                        + "|"
                                        + row.group(3)
                                        + "|"
                                        + schemaCell(row.group(4))
                                        + "|"
                                        + schemaCell(row.group(5)));
                assertThat(previous).as("§5 lists " + row.group(2) + " twice").isNull();
            }
        }
        assertThat(rows).as("no coverage rows parsed from §5 — did the table's shape change?").isNotEmpty();
        return rows;
    }

    /**
     * {@code —} reads as the database side's absent schema, which {@link #seededRows} renders as
     * the string {@code "null"} (string concatenation of a null); anything else is verbatim.
     */
    private static String schemaCell(String cell) {
        return NO_SCHEMA.equals(cell) ? "null" : cell;
    }

    /** From the §5 heading up to §5.1. */
    private static String section5(String contract) {
        int start = contract.indexOf("\n## 5. ");
        int end = contract.indexOf("\n### 5.1", start);
        assertThat(start).as("contract has a '## 5.' section").isNotNegative();
        assertThat(end).as("§5 is followed by '### 5.1'").isGreaterThan(start);
        return contract.substring(start, end);
    }

    /** Read from the repo, not the classpath: the artifact consumers read. */
    private static String contract() {
        try {
            Path path =
                    Files.exists(CONTRACT)
                            ? CONTRACT
                            : Path.of("docs/consumer/income-extraction-contract.md");
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
