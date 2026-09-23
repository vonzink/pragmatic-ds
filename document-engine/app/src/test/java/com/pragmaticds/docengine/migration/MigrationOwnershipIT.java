package com.pragmaticds.docengine.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The documented deployment runs Flyway as a NON-superuser schema owner (README, risk R2).
 * FORCE ROW LEVEL SECURITY applies to owners too — so any migration that enables FORCE RLS
 * and THEN inserts seed rows can never apply in that topology, even though it sails through
 * superuser test containers (superusers bypass RLS entirely).
 *
 * <p>Phase 4 review finding: V6 did exactly that and was unapplyable in production shape.
 * This test IS the prod topology: a plain login role owning a fresh database, running the
 * full migration chain.
 */
@Testcontainers
class MigrationOwnershipIT {

    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR);

    @Test
    void the_full_migration_chain_applies_as_a_nonsuperuser_owner() throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE ROLE mig_owner LOGIN PASSWORD 'mig' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            statement.execute("CREATE DATABASE migtest OWNER mig_owner");
            // Roles are cluster-level, admin-provisioned like extensions: a NOCREATEROLE
            // owner cannot create docengine_app, and V1's guard block no-ops when the
            // admin already has.
            statement.execute("CREATE ROLE docengine_app NOLOGIN");
        }
        // Extensions are ADMIN-provisioned, exactly like RDS: pgvector's `vector` is not a
        // trusted extension, so no plain owner can CREATE it. V1's IF NOT EXISTS then no-ops.
        String adminUrl = POSTGRES.getJdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/migtest$1");
        try (Connection admin =
                        DriverManager.getConnection(
                                adminUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
        }
        String url = POSTGRES.getJdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/migtest$1");

        var result =
                Flyway.configure()
                        .dataSource(url, "mig_owner", "mig")
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();

        assertThat(result.success).isTrue();
        try (Connection connection = DriverManager.getConnection(url, "mig_owner", "mig");
                Statement statement = connection.createStatement()) {
            try (var seeds =
                    statement.executeQuery(
                            "SELECT count(*) FROM classification_rule_pack WHERE org_id IS NULL")) {
                seeds.next();
                // V6's three built-ins plus V10's W2@1.1.0, which retires (but does not delete)
                // V6's W2@1.0.0. V10 is the other half of this test's point — it seeds a table
                // on which V6 ALREADY forced RLS, so it has to drop FORCE for the duration. If
                // that dance were wrong the migrate() above would have failed outright for this
                // NOSUPERUSER owner; V11 §3 rides the same dance.
                // Global pack rows: V6 seeds 3 · V10 +1 (W2 1.1.0; 1.0.0 stays as a retired
                // row) · V11 T8 +2 (DRIVERS_LICENSE, MORTGAGE_STATEMENT) · V11 T9 +3
                // (HOI_DECLARATION, PURCHASE_CONTRACT, TAX_RETURN) · V13 §3 +1
                // (TAX_RETURN 1.1.0; 1.0.0 stays as a retired row, because
                // classification_result.rule_pack_version still has to resolve for every
                // result it decided) +1 (SCHEDULE_E 1.0.0, a birth that retires nothing)
                // = 11 · V28 §2 +3 (FORM_1099_R, FORM_1099_G, VOE — Spec 6's
                // income-verification forms) · V29 §2 +2 (SCHEDULE_C, FORM_1099_NEC —
                // Spec 6b's self-employment pair) · V30 §2 +2 (FORM_1099_MISC,
                // FORM_SSA_1099 — Spec 6c) · V31 §2 +3 (the K-1 family, Spec 6d)
                // = 21 · V32 §2 +2 (SCHEDULE_B, SSA_AWARD_LETTER — Spec 6e)
                // = 23 · V33 §2 +3 (the title-work trio, Spec 6f) = 26 · V34 §2
                // +4 (URLA, APPRAISAL, FORM_4506, DISASTER_CERT) +1 (PAYSTUB 1.1.0,
                // the Oracle-vocabulary supersession; 1.0.0 stays as a retired row,
                // the V10 shape) = 31 · V36 §1 +1 (PAYSTUB 1.2.0, the payroll-bureau
                // supersession; 1.1.0 retires in turn) = 32 · V38 +1 (BANK_STATEMENT
                // 1.1.0, the real-bank-dialect supersession; 1.0.0 retires in turn) = 33 ·
                // V44 §2 +2 (SCHEDULE_D, SCHEDULE_F — the two 1040 schedules the engine
                // could not see at all, both births that retire nothing) = 35 · V46 §2 +5
                // (SCHEDULE_1, SCHEDULE_2, FORM_8962, STATE_TAX_RETURN — issue #60's four
                // births — and TAX_RETURN 1.2.0, the schedule titles leaving the pack;
                // 1.1.0 retires in turn) = 40 · V50 §2 +1 (PROPERTY_TAX_STATEMENT — a
                // county real-estate tax statement, a birth that retires nothing) = 41 ·
                // V51 §2 +5 (FAX_COVER_SHEET, EFILE_AUTHORIZATION, TAX_PREPARER_LETTER,
                // LOAN_DISCLOSURE_PACKAGE, CLOSING_PACKAGE — the triage-only births,
                // retiring nothing) = 46.
                // Both V13 packs ride T7's ONE dance on this table.
                assertThat(seeds.getInt(1)).isEqualTo(46);
            }
            // Spec 3 (V11 §2): TAX_RETURN joins V6's eight built-in types, seeded under the
            // same NO FORCE dance — this time on document_type, whose late-seed path had
            // never been exercised before V11.
            try (var types =
                    statement.executeQuery(
                            "SELECT count(*) FROM document_type WHERE org_id IS NULL")) {
                types.next();
                // V6's eight built-ins · V11 §2 TAX_RETURN · V13 §4 SCHEDULE_E ·
                // V28 §1 FORM_1099_R + FORM_1099_G + VOE · V29 §1 SCHEDULE_C +
                // FORM_1099_NEC · V30 §1 FORM_1099_MISC + FORM_SSA_1099 ·
                // V31 §1 the K-1 family · V32 §1 SCHEDULE_B + SSA_AWARD_LETTER ·
                // V33 §1 the title-work trio · V34 §1 URLA + APPRAISAL +
                // FORM_4506 + DISASTER_CERT = 29 · V44 §1 SCHEDULE_D + SCHEDULE_F = 31 ·
                // V46 §1 SCHEDULE_1 + SCHEDULE_2 + FORM_8962 + STATE_TAX_RETURN = 35 ·
                // V50 §1 PROPERTY_TAX_STATEMENT = 36 ·
                // V51 §1 the five triage-only types (no schema, by design) = 41.
                assertThat(types.getInt(1)).isEqualTo(41);
            }
            try (var taxReturn =
                    statement.executeQuery(
                            "SELECT display_name, category FROM document_type"
                                    + " WHERE org_id IS NULL AND code = 'TAX_RETURN'")) {
                assertThat(taxReturn.next()).as("TAX_RETURN row seeded by V11 §2").isTrue();
                assertThat(taxReturn.getString("display_name"))
                        .isEqualTo("Individual Income Tax Return");
                assertThat(taxReturn.getString("category")).isEqualTo("INCOME");
            }
            // Phase E1 (V27): every seeded built-in carries an authored boundary taxonomy
            // sentence — a NULL here means the migration's late-seed UPDATE missed a code, which
            // silently degrades that type's boundary extraction to its display name.
            try (var unauthored =
                    statement.executeQuery(
                            "SELECT count(*) FROM document_type"
                                    + " WHERE org_id IS NULL AND split_description IS NULL")) {
                unauthored.next();
                assertThat(unauthored.getInt(1))
                        .as("global document types without split_description")
                        .isZero();
            }
        }
    }

    @Test
    void the_v7_extraction_seed_applies_for_a_nonsuperuser_owner() throws Exception {
        // Phase 5 addition, self-contained (own role + database) so it never depends on the
        // other test's ordering: V7 seeds the global paystub schema BEFORE forcing RLS, so the
        // chain must apply as a plain owner and leave exactly one global extraction_schema row.
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE ROLE mig_owner5 LOGIN PASSWORD 'mig' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            statement.execute("CREATE DATABASE migtest5 OWNER mig_owner5");
            // docengine_app may already exist if the other test ran first — admin-provisioned
            // roles are cluster-level, so guard the same way V1 does.
            statement.execute(
                    """
                    DO $$ BEGIN
                        CREATE ROLE docengine_app NOLOGIN;
                    EXCEPTION WHEN duplicate_object THEN NULL;
                    END $$
                    """);
        }
        String url = POSTGRES.getJdbcUrl().replaceAll("/[^/?]+(\\?|$)", "/migtest5$1");
        try (Connection admin =
                        DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
        }

        var result =
                Flyway.configure()
                        .dataSource(url, "mig_owner5", "mig")
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();

        assertThat(result.success).isTrue();
        try (Connection connection = DriverManager.getConnection(url, "mig_owner5", "mig");
                Statement statement = connection.createStatement()) {
            try (var seeds =
                    statement.executeQuery(
                            "SELECT count(*) FROM extraction_schema WHERE org_id IS NULL")) {
                seeds.next();
                // Global schema ROWS, not distinct types: V7 paystub · V11 §4 seven (W2,
                // BANK_STATEMENT, DRIVERS_LICENSE, MORTGAGE_STATEMENT, HOI_DECLARATION,
                // PURCHASE_CONTRACT, TAX_RETURN) = 8 · V12 §2 +2 (W2 1.1.0 and TAX_RETURN
                // 1.1.0) = 10 · V13 §5 +1 (SCHEDULE_E 1.0.0) = 11 · V14 +1
                // (SCHEDULE_E 1.0.1) = 12 · V18 +1 (BANK_STATEMENT 1.1.0, the real-dialect
                // successor) = 13 · V19 +1 (BANK_STATEMENT 1.2.0, retiring the withdrawals rung
                // that bound a category subtotal) = 14 · V25 +1 (BANK_STATEMENT 1.3.0, declaring
                // the instance key that lets the splitter separate consecutive statements) = 15.
                // All six superseded rows stay RETIRED, because every extracted_field they
                // produced still points at them through schema_id — a supersession adds a row, it
                // never replaces one. V35 +1 (PAYSTUB 1.1.0, retiring V7's 1.0.0: the Oracle
                // Cloud HCM header band is caption-ABOVE-value, so it needs LABEL_BELOW rungs
                // that 1.0.0's beside-the-caption vocabulary could never reach) = 35 ·
                // V36 +1 (PAYSTUB 1.2.0, the payroll-bureau dialect's OCR-fused rungs;
                // 1.1.0 retires in turn) = 36 · V39 +1 (BANK_STATEMENT 1.4.0, the
                // online-print-out rungs; 1.3.0 retires in turn) = 37 · V40 §2 +1
                // (BANK_STATEMENT 1.5.0, the LABEL_ABOVE rung for the balance a print-out
                // states beneath its caption; 1.4.0 retires in turn) = 38 · V41 §1 +1
                // (BANK_STATEMENT 1.6.0, the month-to-date tiles as their own two
                // fields; 1.5.0 retires in turn) = 39 · V42 +1 (PAYSTUB 1.3.0, the bureau
                // layout's NATIVE-text spacing — V36's OCR-fused captions with their spaces
                // put back; 1.2.0 retires in turn) = 40 · V43 +1 (W2 1.2.0, ADP's own captions
                // as LABEL_BELOW alternates beside the IRS literals; 1.1.0 retires in turn)
                // = 41 · V44 §3 +2 (SCHEDULE_D 1.0.0 and SCHEDULE_F 1.0.0, both births —
                // the first schemas either form has ever had) = 43 · V45 +1 (TAX_RETURN 1.2.0,
                // the real 1040's whole-dollar money column, box-grid identity captions and
                // full-sentence money labels; 1.1.0 retires in turn) = 44 · V46 §3 +4
                // (SCHEDULE_1, SCHEDULE_2, FORM_8962, STATE_TAX_RETURN 1.0.0 — issue #60's
                // four births, headline fields only, nothing retired) = 48 · V47 §1 +2 (W2
                // 1.3.0 and TAX_RETURN 1.3.0 — the name read across its split first-name /
                // last-name cells, measured on real forms; 1.2.0 retires in turn for both)
                // = 50 · V48 §1 +2 (PAYSTUB 1.4.0 — the gross pair off the totals strip and
                // the `Total Earnings` line, federal tax off `FIT`; 1.3.0 retires — and
                // SCHEDULE_C 1.1.0 — the business name under its box letter in Title Case,
                // the masthead year under the OMB caption; 1.0.0 retires) = 52 · V49 §1 +1
                // (TAX_RETURN 1.4.0 — the money pattern admits the bare comma-grouped whole
                // dollars preparer software prints and refuses a `24.` line number; 1.3.0
                // retires in turn) = 53 · V50 §3 +1 (PROPERTY_TAX_STATEMENT 1.0.0 — a birth,
                // headline fields only, nothing retired) = 54 · V53 §1 +1 (PAYSTUB 1.5.0 — the
                // earnings lines as a ROW group and the three closing totals, AI-only rungs;
                // 1.4.0 retires in turn) = 55 · V54 §2 +1 (BANK_STATEMENT 1.7.0 — the three real
                // layouts of the first gold session: known-bank names, dashed and masked account
                // numbers, abbreviated-month and ANB periods, joint holders joined across two
                // lines, withdrawals derived from the balance identity; 1.6.0 retires in turn)
                // = 56.
                assertThat(seeds.getInt(1)).isEqualTo(56);
            }
            // Specs 3, 4 and 5a (V11 §1, V12 §1, V13 §2): the extraction_method CHECK is
            // widened for the two detector-backed rungs, then the box-grid rung, then the
            // row-group rung; V40 §1 widens it again for the tile rung, LABEL_BELOW's vertical
            // mirror; V54 §1 widens it again for DERIVED, the arithmetic rung with no evidence.
            // Probing pg_get_constraintdef keeps this test free of the FK
            // scaffolding (tenant, package, document, schema) a real extracted_field INSERT
            // would need.
            try (var constraint =
                    statement.executeQuery(
                            "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                    + " WHERE conname = 'extracted_field_method_check'")) {
                assertThat(constraint.next())
                        .as(
                                "extracted_field_method_check exists (V7, widened by V11, V12,"
                                        + " V13, V40 and V54)")
                        .isTrue();
                assertThat(constraint.getString(1))
                        .contains("CHECKBOX_STATE")
                        .contains("SIGNATURE_PRESENCE")
                        .contains("LABEL_BELOW")
                        .contains("ROW_CELL")
                        // V23's AI arm must SURVIVE every later widening: a DROP/ADD that
                        // repeats an older list narrows the constraint, and the first AI
                        // row after it fails its insert (V40's first cut did exactly that).
                        .contains("AI")
                        .contains("LABEL_ABOVE")
                        .contains("'DERIVED'");
            }
        }
    }
}
