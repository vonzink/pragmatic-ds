package com.pragmaticds.docengine.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Every table in the schema is tenant-isolated — enforced structurally, so a future migration
 * cannot add a table and forget RLS.
 *
 * <p>This test intentionally has no per-table list to update. It asserts over {@code pg_tables},
 * which means a Phase 2 or Phase 5 migration that creates a table without FORCE RLS and a policy
 * fails CI the moment it lands, not when a tenant leak is discovered.
 */
@Testcontainers
class RlsCoverageIT {

    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR);

    /**
     * Tables allowed to skip FORCE RLS.
     *
     * <ul>
     *   <li>{@code flyway_schema_history} — bookkeeping, holds no tenant data.
     *   <li>{@code api_key} — V22 drops FORCE (keeping ENABLE + the isolation policy) so the OWNER,
     *       and the {@code api_key_authenticate} SECURITY DEFINER function it owns, can resolve a key
     *       by hash BEFORE any tenant is bound (the auth bootstrap: org is learned from the key). The
     *       app connects as the non-owner {@code docengine_app}, still fully governed by
     *       {@code api_key_isolation}, so app-layer isolation is unchanged. Proven by
     *       {@link #api_key_is_enable_but_not_force_rls_for_the_auth_bootstrap()}.
     * </ul>
     */
    private static final List<String> EXEMPT = List.of("flyway_schema_history", "api_key");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private List<String> query(String sql) throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(resultSet.getString(1));
            }
            return rows;
        }
    }

    @Test
    void phase1_tables_exist() throws Exception {
        assertThat(query("SELECT tablename FROM pg_tables WHERE schemaname = 'public'"))
                .contains(
                        "document_package",
                        "source_file",
                        "retention_policy",
                        "processing_job",
                        "processing_stage");
    }

    @Test
    void phase4_classification_tables_exist() throws Exception {
        assertThat(query("SELECT tablename FROM pg_tables WHERE schemaname = 'public'"))
                .contains(
                        "document_type",
                        "classification_rule_pack",
                        "classification_result",
                        "logical_document",
                        "logical_document_page");
    }

    @Test
    void v6_seeds_the_builtin_types_and_packs() throws Exception {
        // V6's eight built-in document types (docs/DATA_MODEL.md 5) plus V11 §2's
        // TAX_RETURN, org_id NULL = global. TAX_RETURN is the first type seeded into
        // an ALREADY-FORCEd document_type, so its presence here doubles as proof that
        // V11's NO FORCE dance ran — while relforcerowsecurity below proves it restored.
        assertThat(query("SELECT code FROM document_type WHERE org_id IS NULL ORDER BY code"))
                .containsExactly(
                        // V34 §1: Spec 6g.
                        "APPRAISAL",
                        "BANK_STATEMENT",
                        // V33 §1: the title-work trio, Spec 6f.
                        "CLOSING_DISCLOSURE",
                        // V51 §1: the triage-only types, here and below.
                        "CLOSING_PACKAGE",
                        // V34 §1.
                        "DISASTER_CERT",
                        "DRIVERS_LICENSE",
                        "EFILE_AUTHORIZATION",
                        "FAX_COVER_SHEET",
                        // V28 §1: Spec 6's income-verification forms, seeded with their
                        // split_description sentences riding in the INSERT (V27's census
                        // below rejects a NULL on any seeded type).
                        "FORM_1099_G",
                        // V30 §1: Spec 6c's miscellaneous-income form.
                        "FORM_1099_MISC",
                        // V29 §1: Spec 6b's self-employment pair.
                        "FORM_1099_NEC",
                        "FORM_1099_R",
                        // V34 §1: the copy-of-return request (not the 4506-C).
                        "FORM_4506",
                        // V46 §1: issue #60's premium tax credit form.
                        "FORM_8962",
                        // V30 §1: SSA's benefit statement.
                        "FORM_SSA_1099",
                        "HOI_DECLARATION",
                        "LOAN_DISCLOSURE_PACKAGE",
                        "MORTGAGE_STATEMENT",
                        "PAYSTUB",
                        // V50 §1: the county real-estate tax statement.
                        "PROPERTY_TAX_STATEMENT",
                        "PURCHASE_CONTRACT",
                        // V13 §4. ORDER BY code puts it between PURCHASE_CONTRACT and
                        // TAX_RETURN, and it is the second type ever seeded into an
                        // already-FORCEd document_type — its own NO FORCE dance.
                        // V46 §1: issue #60's numbered schedules. ORDER BY code puts the
                        // digits before the letters.
                        "SCHEDULE_1",
                        "SCHEDULE_2",
                        // V32 §1: Spec 6e.
                        "SCHEDULE_B",
                        "SCHEDULE_C",
                        // V44 §1: ORDER BY code drops SCHEDULE_D between C and E, and
                        // SCHEDULE_F between E and the K-1 family.
                        "SCHEDULE_D",
                        "SCHEDULE_E",
                        "SCHEDULE_F",
                        // V31 §1: the K-1 family, Spec 6d.
                        "SCHEDULE_K1_1041",
                        "SCHEDULE_K1_1065",
                        "SCHEDULE_K1_1120S",
                        // V32 §1: SSA's award letter.
                        "SSA_AWARD_LETTER",
                        // V46 §1: issue #60's generic state return.
                        "STATE_TAX_RETURN",
                        "TAX_PREPARER_LETTER",
                        "TAX_RETURN",
                        // V33 §1.
                        "TITLE_COMMITMENT",
                        "UNKNOWN",
                        // V34 §1.
                        "URLA",
                        "VOE",
                        "W2",
                        // V33 §1.
                        "WIRING_INSTRUCTIONS");
        // Every built-in TYPE that has an active pack, versioned, valid JSON with anchors.
        // W2 is on 1.1.0 since V10 retired 1.0.0 (the pack that could label a Form 1040 a
        // W-2), so the version is asserted per type rather than pinned globally.
        // V11 §3 adds five: DRIVERS_LICENSE + MORTGAGE_STATEMENT (Spec 3 T8) and
        // HOI_DECLARATION + PURCHASE_CONTRACT + TAX_RETURN (T9), all under ONE NO FORCE
        // dance — their presence here is the other half of that dance's proof, and with
        // them every seeded document type except UNKNOWN can finally classify.
        assertThat(query(
                        """
                        SELECT document_type_code || '@' || version
                          FROM classification_rule_pack
                        WHERE org_id IS NULL AND is_active
                          AND jsonb_array_length(definition->'anchors') >= 4
                        ORDER BY document_type_code
                        """))
                .containsExactly(
                        // V34 §2.
                        "APPRAISAL@1.0.0",
                        // V38: the real-bank-dialect supersession; 1.0.0 retired.
                        "BANK_STATEMENT@1.1.0",
                        // V33 §2, one dance for all three.
                        "CLOSING_DISCLOSURE@1.0.0",
                        // V51 §2, one dance for all five, here and below.
                        "CLOSING_PACKAGE@1.0.0",
                        // V34 §2.
                        "DISASTER_CERT@1.0.0",
                        "DRIVERS_LICENSE@1.0.0",
                        "EFILE_AUTHORIZATION@1.0.0",
                        "FAX_COVER_SHEET@1.0.0",
                        // V28 §2, one dance for all three.
                        "FORM_1099_G@1.0.0",
                        // V30 §2.
                        "FORM_1099_MISC@1.0.0",
                        // V29 §2.
                        "FORM_1099_NEC@1.0.0",
                        "FORM_1099_R@1.0.0",
                        // V34 §2.
                        "FORM_4506@1.0.0",
                        // V46 §2.
                        "FORM_8962@1.0.0",
                        "FORM_SSA_1099@1.0.0",
                        "HOI_DECLARATION@1.0.0",
                        "LOAN_DISCLOSURE_PACKAGE@1.0.0",
                        "MORTGAGE_STATEMENT@1.0.0",
                        // V36 §1: the payroll-bureau supersession; 1.1.0 retired.
                        "PAYSTUB@1.2.0",
                        // V50 §2.
                        "PROPERTY_TAX_STATEMENT@1.0.0",
                        "PURCHASE_CONTRACT@1.0.0",
                        // V46 §2, one dance for all four (and the TAX_RETURN supersession).
                        "SCHEDULE_1@1.0.0",
                        "SCHEDULE_2@1.0.0",
                        // V32 §2.
                        "SCHEDULE_B@1.0.0",
                        "SCHEDULE_C@1.0.0",
                        // V44 §2, one dance for both.
                        "SCHEDULE_D@1.0.0",
                        "SCHEDULE_E@1.0.0",
                        "SCHEDULE_F@1.0.0",
                        // V31 §2, one dance for all three.
                        "SCHEDULE_K1_1041@1.0.0",
                        "SCHEDULE_K1_1065@1.0.0",
                        "SCHEDULE_K1_1120S@1.0.0",
                        "SSA_AWARD_LETTER@1.0.0",
                        // V46 §2.
                        "STATE_TAX_RETURN@1.0.0",
                        "TAX_PREPARER_LETTER@1.0.0",
                        // V46 §2: the schedule titles leave the pack; 1.1.0 retired.
                        "TAX_RETURN@1.2.0",
                        "TITLE_COMMITMENT@1.0.0",
                        // V34 §2.
                        "URLA@1.0.0",
                        "VOE@1.0.0",
                        "W2@1.1.0",
                        "WIRING_INSTRUCTIONS@1.0.0");
        // Retired, not deleted: classification_result.rule_pack_version still has to resolve
        // for every result already decided by 1.0.0.
        assertThat(query(
                        """
                        SELECT document_type_code || '@' || version
                          FROM classification_rule_pack
                        WHERE org_id IS NULL AND NOT is_active
                        """))
                .containsExactlyInAnyOrder("W2@1.0.0", "TAX_RETURN@1.0.0",
                        // V46 §2: retired by the issue-60 narrowing — Schedules 1 and 2 have
                        // packs of their own, so their titles could no longer qualify this one.
                        "TAX_RETURN@1.1.0",
                        // V34 §2: retired by the Oracle-vocabulary supersession.
                        "PAYSTUB@1.0.0",
                        // V36 §1: retired by the payroll-bureau supersession.
                        "PAYSTUB@1.1.0",
                        // V38: retired by the real-bank-dialect supersession — it could not
                        // score a real Chase statement above its own threshold.
                        "BANK_STATEMENT@1.0.0");
    }

    @Test
    void tenants_can_read_but_never_delete_or_hijack_global_builtins() throws Exception {
        // Phase 4 review: a single shared USING clause meant any tenant session could
        // DELETE a global pack (DELETE only consults USING) or UPDATE it into their
        // own org (hijack). Globals must be readable by all, writable by none.
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO tenant (id, name, status) VALUES"
                            + " ('33333333-3333-3333-3333-333333333333', 'Org RLS', 'ACTIVE')"
                            + " ON CONFLICT (id) DO NOTHING");
            statement.execute("SET ROLE docengine_app");
            statement.execute("SET app.current_org = '33333333-3333-3333-3333-333333333333'");

            try (ResultSet visible =
                    statement.executeQuery(
                            "SELECT count(*) FROM classification_rule_pack WHERE org_id IS NULL")) {
                visible.next();
                // Eleven global pack rows: V6's three built-ins, V10's W2@1.1.0, V11 §3's
                // five (DRIVERS_LICENSE, MORTGAGE_STATEMENT, HOI_DECLARATION,
                // PURCHASE_CONTRACT, TAX_RETURN) and V13 §3's two (TAX_RETURN@1.1.0 and
                // SCHEDULE_E@1.0.0).
                // Retired rows stay readable — is_active is the LOADER's filter, not RLS's.
                // V36 +1 (PAYSTUB@1.2.0, the payroll-bureau supersession) = 32; V38 +1
                // (BANK_STATEMENT@1.1.0, the real-bank-dialect supersession) = 33; V44 §2
                // +2 (SCHEDULE_D@1.0.0, SCHEDULE_F@1.0.0) = 35; V46 §2 +5 (SCHEDULE_1,
                // SCHEDULE_2, FORM_8962, STATE_TAX_RETURN at 1.0.0 and TAX_RETURN@1.2.0,
                // retiring 1.1.0) = 40; V50 §2 +1 (PROPERTY_TAX_STATEMENT@1.0.0) = 41;
                // V51 §2 +5 (the triage-only packs at 1.0.0) = 46.
                assertThat(visible.getInt(1)).as("globals readable").isEqualTo(46);
            }
            int deleted =
                    statement.executeUpdate(
                            "DELETE FROM classification_rule_pack WHERE org_id IS NULL");
            assertThat(deleted).as("DELETE of global packs must affect zero rows").isZero();
            int hijacked =
                    statement.executeUpdate(
                            "UPDATE classification_rule_pack SET org_id ="
                                    + " '33333333-3333-3333-3333-333333333333' WHERE org_id IS NULL");
            assertThat(hijacked).as("UPDATE-hijack of global packs must affect zero rows").isZero();
            int typesDeleted =
                    statement.executeUpdate("DELETE FROM document_type WHERE org_id IS NULL");
            assertThat(typesDeleted).as("DELETE of global types must affect zero rows").isZero();
        }
    }

    @Test
    void at_most_one_current_classification_result_per_subject() throws Exception {
        // Phase 4 review: nothing enforced single is_current; a duplicate permanently
        // broke SPLITTING and the documents endpoint. The partial unique index is the
        // database-level guarantee the supersession code path rides on.
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO tenant (id, name, status) VALUES"
                            + " ('44444444-4444-4444-4444-444444444444', 'Org CR', 'ACTIVE')"
                            + " ON CONFLICT (id) DO NOTHING");
            String insert =
                    "INSERT INTO classification_result (org_id, subject_type, subject_id,"
                        + " document_type_code, confidence, method, evidence) VALUES"
                        + " ('44444444-4444-4444-4444-444444444444', 'PAGE',"
                        + " '55555555-5555-5555-5555-555555555555', 'PAYSTUB', 0.9,"
                        + " 'RULE_ANCHOR', '{}')";
            statement.execute(insert);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.execute(insert))
                    .hasMessageContaining("classification_result_one_current");
        }
    }

    @Test
    void phase5_extraction_tables_exist() throws Exception {
        assertThat(query("SELECT tablename FROM pg_tables WHERE schemaname = 'public'"))
                .contains("extraction_schema", "extracted_field", "field_evidence");
    }

    @Test
    void v7_seeds_the_builtin_paystub_schema() throws Exception {
        // paystub@1.0.0 with exactly ten fields (docs/DATA_MODEL.md 6). Scoped to PAYSTUB
        // because V11 §4 seeds further global built-ins, some of which also carry ten
        // fields — the census of ALL of them is the second assertion below.
        //
        // NOT filtered on is_active since V35: that migration supersedes this seed with
        // paystub@1.1.0 (the Oracle LABEL_BELOW rungs) and retires the row. What V7 is
        // responsible for is that the row EXISTS with its ten fields, which a
        // supersession never changes — retired, never deleted, because every
        // extracted_field produced under it still points here through schema_id.
        assertThat(query(
                        """
                        SELECT document_type_code FROM extraction_schema
                        WHERE org_id IS NULL
                          AND document_type_code = 'PAYSTUB'
                          AND version = '1.0.0'
                          AND jsonb_array_length(definition->'fields') = 10
                        """))
                .containsExactly("PAYSTUB");
        // The full global built-in census, now keyed by TYPE@VERSION rather than by type alone:
        // V12 supersedes W2 with 1.1.0, and a census naming only types cannot tell a supersession
        // from a no-op. (The rule-pack census above has been version-keyed since V10; this brings
        // the schema census up to the same standard.)
        assertThat(query(
                        """
                        SELECT document_type_code || '@' || version FROM extraction_schema
                        WHERE org_id IS NULL AND is_active
                        """))
                .containsExactlyInAnyOrder(
                        // V36 supersedes V35's row: same ten fields, plus the seven
                        // rungs that read the payroll-bureau layout's OCR-fused
                        // captions. Appended to each ladder, so the five layouts that
                        // already extracted keep winning on their own first rung. V42
                        // supersedes 1.2.0 with the bureau's native-text captions; V48 §1
                        // supersedes 1.3.0 in turn: the gross pair read off the totals
                        // strip's whole caption row and off a `Total Earnings` line, federal
                        // tax off the `FIT` caption — measured on two real stubs that print
                        // no `Gross` row at all.
                        // V53 supersedes V48's row: paystub@1.5.0 adds the earnings lines as a
                        // ROW group and the three closing totals, AI-only rungs.
                        "PAYSTUB@1.5.0",
                        // V47 supersedes V43's row: w2@1.3.0 reads the employee's name across
                        // the split "first name and initial" | "Last name" cells a filed W-2
                        // actually fills, and the payroll provider's lone "Employee's name".
                        "W2@1.3.0",
                        // V18 supersedes the generator-dialect statement schema with the
                        // real-bank vocabulary (summary wordings, range-construction period
                        // labels) while keeping the generator rungs as first alternatives;
                        // V19 supersedes THAT, retiring the withdrawals rung that bound a
                        // category subtotal rather than a total; V25 supersedes 1.2.0 in turn,
                        // adding the instanceKey declaration (identical field specs — the new
                        // row is derived from its predecessor by jsonb concatenation, not
                        // retyped) so the splitter can separate consecutive statements;
                        // V39 supersedes 1.3.0, appending the three rungs that read an
                        // ONLINE ACTIVITY PRINT-OUT — a genre with no period and no opening
                        // balance, on which 1.3.0 captured nothing whatsoever. V40 §2
                        // supersedes 1.4.0 in turn: measured on the real print-out, the
                        // balance is a TILE — the amount over its caption — which V39's
                        // LINE_RIGHT rung could not see, so 1.5.0 appends LABEL_ABOVE.
                        // V41 §1 supersedes 1.5.0 in turn, appending the print-out's two
                        // month-to-date tiles as fields of their own — never as the period
                        // totals, which they are not. V54 §2 supersedes 1.6.0 in turn: the
                        // three real layouts of the first gold session (known-bank names,
                        // dashed/masked account numbers, abbreviated-month and ANB periods,
                        // joint holders joined across two lines, withdrawals derived from
                        // the balance identity).
                        "BANK_STATEMENT@1.7.0",
                        "DRIVERS_LICENSE@1.0.0",
                        // V28 §3: Spec 6's income-verification schemas, births that
                        // retire nothing.
                        "FORM_1099_R@1.0.0",
                        "FORM_1099_G@1.0.0",
                        "VOE@1.0.0",
                        // V29 §3: Spec 6b's self-employment pair. V48 §1 supersedes
                        // SCHEDULE_C 1.0.0: the business name anchored with its box letter
                        // and read in Title Case, the masthead year under the OMB caption —
                        // measured on two real filled returns.
                        "SCHEDULE_C@1.1.0",
                        "FORM_1099_NEC@1.0.0",
                        // V30 §3: Spec 6c.
                        "FORM_1099_MISC@1.0.0",
                        "FORM_SSA_1099@1.0.0",
                        // V31 §3: the K-1 family.
                        "SCHEDULE_K1_1065@1.0.0",
                        "SCHEDULE_K1_1120S@1.0.0",
                        "SCHEDULE_K1_1041@1.0.0",
                        // V32 §3: Spec 6e.
                        "SCHEDULE_B@1.0.0",
                        "SSA_AWARD_LETTER@1.0.0",
                        // V33 §3: the title-work trio.
                        "CLOSING_DISCLOSURE@1.0.0",
                        "TITLE_COMMITMENT@1.0.0",
                        "WIRING_INSTRUCTIONS@1.0.0",
                        // V34 §3: Spec 6g.
                        "URLA@1.0.0",
                        "APPRAISAL@1.0.0",
                        "FORM_4506@1.0.0",
                        "DISASTER_CERT@1.0.0",
                        "MORTGAGE_STATEMENT@1.0.0",
                        "HOI_DECLARATION@1.0.0",
                        "PURCHASE_CONTRACT@1.0.0",
                        // V45 supersedes V12 §2's row: on a REAL 1040 the money column prints
                        // WHOLE DOLLARS with an empty cents box, the identity block is a box
                        // grid captioned "Your first name and middle initial" (the form prints
                        // no "Your name:" anywhere), and the money captions are full sentences.
                        // 1.1.0 read three of its ten fields. V47 supersedes in turn: 1.3.0
                        // reads the taxpayer's and spouse's names across the split cells.
                        // V49 supersedes in turn: 1.4.0's money pattern admits the BARE
                        // comma-grouped whole dollars preparer software prints (no cents,
                        // no trailing period) and refuses a line number printed as `24.`.
                        "TAX_RETURN@1.4.0",
                        // V14 supersedes V13's first repeating-group schema with a relative-line
                        // fallback while preserving the original extractor as the first rung.
                        "SCHEDULE_E@1.0.1",
                        // V44 §3: the first schema either form has ever had, so neither
                        // retires anything and the retired census below is untouched.
                        "SCHEDULE_D@1.0.0",
                        "SCHEDULE_F@1.0.0",
                        // V46 §3: issue #60's four births — headline fields only; nothing
                        // retired.
                        "SCHEDULE_1@1.0.0",
                        "SCHEDULE_2@1.0.0",
                        "FORM_8962@1.0.0",
                        "STATE_TAX_RETURN@1.0.0",
                        // V50 §3: the county real-estate tax statement — a birth; nothing
                        // retired.
                        "PROPERTY_TAX_STATEMENT@1.0.0");
        // Retired, not deleted — the same rule V10 established for rule packs: every
        // extracted_field already written by w2@1.0.0 still points at that row through
        // schema_id, so the row must survive its own retirement.
        assertThat(query(
                        """
                        SELECT document_type_code || '@' || version FROM extraction_schema
                        WHERE org_id IS NULL AND NOT is_active
                        """))
                .containsExactlyInAnyOrder(
                        "W2@1.0.0",
                        // V43 retires V12 §2's row: w2@1.2.0 adds ADP's own captions as
                        // LABEL_BELOW alternates beside the IRS literals.
                        "W2@1.1.0",
                        // V47 retires V43's row in turn: w2@1.3.0 joins the split name cells.
                        "W2@1.2.0",
                        "TAX_RETURN@1.0.0",
                        // V45 retires V12 §2's row in turn: tax_return@1.2.0 reads the real
                        // form's geometry instead of the fixture drawn from the same belief.
                        "TAX_RETURN@1.1.0",
                        // V47 retires V45's row in turn: tax_return@1.3.0 joins the split
                        // name cells the real forms fill.
                        "TAX_RETURN@1.2.0",
                        // V49 retires V47's row in turn: tax_return@1.4.0 reads the bare
                        // whole-dollar column preparer software prints.
                        "TAX_RETURN@1.3.0",
                        "SCHEDULE_E@1.0.0",
                        "BANK_STATEMENT@1.0.0", "BANK_STATEMENT@1.1.0",
                        "BANK_STATEMENT@1.2.0",
                        // V39: retired by the online-print-layout supersession.
                        "BANK_STATEMENT@1.3.0",
                        // V40 §2: retired by the tile-rung supersession.
                        "BANK_STATEMENT@1.4.0",
                        // V41 §1: retired by the month-to-date-fields supersession.
                        "BANK_STATEMENT@1.5.0",
                        // V54 §2: retired by the three-real-layouts supersession.
                        "BANK_STATEMENT@1.6.0",
                        // V35 retires V7's own seed: paystub@1.1.0 adds the Oracle
                        // LABEL_BELOW rungs to every ladder it supersedes.
                        "PAYSTUB@1.0.0",
                        // V36 retires V35's row in turn: paystub@1.2.0 adds the
                        // payroll-bureau dialect's OCR-fused rungs.
                        "PAYSTUB@1.1.0",
                        // V42 retires V36's row in turn: paystub@1.3.0 reads the same bureau
                        // layout from NATIVE text, where the captions keep their spaces.
                        "PAYSTUB@1.2.0",
                        // V48 retires V42's row in turn: paystub@1.4.0 reads the gross pair
                        // off the totals strip's caption row and a `Total Earnings` line.
                        "PAYSTUB@1.3.0",
                        // V53 retires V48's row in turn: paystub@1.5.0 persists the earnings
                        // lines (a ROW group) and the three closing totals, AI-only rungs.
                        "PAYSTUB@1.4.0",
                        // V48 retires V29 §3's row: schedule_c@1.1.0 anchors the business
                        // name with its box letter and reads the masthead year under OMB.
                        "SCHEDULE_C@1.0.0");
    }

    @Test
    void v14_appends_only_the_relative_line_fallback() throws Exception {
        assertThat(query(
                        """
                        SELECT version || ':' || is_active
                          FROM extraction_schema
                         WHERE org_id IS NULL
                           AND document_type_code = 'SCHEDULE_E'
                         ORDER BY version
                        """))
                .as("V14 retires 1.0.0 and activates its immutable successor")
                .containsExactly("1.0.0:false", "1.0.1:true");

        assertThat(query(
                        """
                        SELECT version || ':' || jsonb_array_length(field->'extractors')
                          FROM extraction_schema
                          CROSS JOIN LATERAL jsonb_array_elements(definition->'fields') AS item(field)
                         WHERE org_id IS NULL
                           AND document_type_code = 'SCHEDULE_E'
                           AND version IN ('1.0.0', '1.0.1')
                           AND field->>'name' = 'incomeOrLoss'
                         ORDER BY version
                        """))
                .as("only the successor gains one incomeOrLoss fallback rung")
                .containsExactly("1.0.0:1", "1.0.1:2");

        assertThat(query(
                        """
                        WITH income_fields AS (
                            SELECT version, field
                              FROM extraction_schema
                              CROSS JOIN LATERAL
                                   jsonb_array_elements(definition->'fields') AS item(field)
                             WHERE org_id IS NULL
                               AND document_type_code = 'SCHEDULE_E'
                               AND version IN ('1.0.0', '1.0.1')
                               AND field->>'name' = 'incomeOrLoss'
                        )
                        SELECT (successor.field->'extractors'->0 =
                                source.field->'extractors'->0)::text
                          FROM income_fields source
                          JOIN income_fields successor ON successor.version = '1.0.1'
                         WHERE source.version = '1.0.0'
                        """))
                .as("the original offset-zero rung remains byte-identical and first")
                .containsExactly("true");

        assertThat(query(
                        """
                        WITH income_fields AS (
                            SELECT version, field
                              FROM extraction_schema
                              CROSS JOIN LATERAL
                                   jsonb_array_elements(definition->'fields') AS item(field)
                             WHERE org_id IS NULL
                               AND document_type_code = 'SCHEDULE_E'
                               AND version IN ('1.0.0', '1.0.1')
                               AND field->>'name' = 'incomeOrLoss'
                        )
                        SELECT ((successor.field->'extractors'->1 #- '{value,lineOffset}') =
                                source.field->'extractors'->0)::text
                          FROM income_fields source
                          JOIN income_fields successor ON successor.version = '1.0.1'
                         WHERE source.version = '1.0.0'
                        """))
                .as("the appended rung differs only by value.lineOffset")
                .containsExactly("true");

        assertThat(query(
                        """
                        SELECT field->'extractors'->1->'value'->>'lineOffset'
                          FROM extraction_schema
                          CROSS JOIN LATERAL jsonb_array_elements(definition->'fields') AS item(field)
                         WHERE org_id IS NULL
                           AND document_type_code = 'SCHEDULE_E'
                           AND version = '1.0.1'
                           AND field->>'name' = 'incomeOrLoss'
                        """))
                .as("the appended fallback selects exactly two lines below the anchor")
                .containsExactly("2");

        assertThat(query(
                        """
                        WITH other_fields AS (
                            SELECT version, jsonb_agg(field ORDER BY ordinal) AS fields
                              FROM extraction_schema
                              CROSS JOIN LATERAL
                                   jsonb_array_elements(definition->'fields')
                                   WITH ORDINALITY AS item(field, ordinal)
                             WHERE org_id IS NULL
                               AND document_type_code = 'SCHEDULE_E'
                               AND version IN ('1.0.0', '1.0.1')
                               AND field->>'name' <> 'incomeOrLoss'
                             GROUP BY version
                        )
                        SELECT (successor.fields = source.fields)::text
                          FROM other_fields source
                          JOIN other_fields successor ON successor.version = '1.0.1'
                         WHERE source.version = '1.0.0'
                        """))
                .as("every non-incomeOrLoss field stays JSONB-identical")
                .containsExactly("true");
    }

    @Test
    void tenants_can_read_but_never_delete_or_hijack_global_extraction_schemas() throws Exception {
        // Same per-command policy rule as classification_rule_pack: globals readable by every
        // org, writable by none — DELETE only consults USING, UPDATE-hijack needs WITH CHECK.
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO tenant (id, name, status) VALUES"
                            + " ('66666666-6666-6666-6666-666666666666', 'Org XS', 'ACTIVE')"
                            + " ON CONFLICT (id) DO NOTHING");
            statement.execute("SET ROLE docengine_app");
            statement.execute("SET app.current_org = '66666666-6666-6666-6666-666666666666'");

            try (ResultSet visible =
                    statement.executeQuery(
                            "SELECT count(*) FROM extraction_schema WHERE org_id IS NULL")) {
                visible.next();
                // V7 paystub + seven V11 §4 schemas + V12 §2's two successors + V13 §5's
                // SCHEDULE_E + V14's immutable successor + V18's real-dialect BANK_STATEMENT
                // successor + V19's withdrawals-total correction + V25's instanceKey
                // declaration = 15 ROWS. Retired rows stay READABLE — is_active is the LOADER's
                // filter, not RLS's. V35's paystub@1.1.0 made 35; V36's paystub@1.2.0 makes 36;
                // V39's bank_statement@1.4.0 — the online-print-out rungs — makes 37;
                // V40's bank_statement@1.5.0 — the tile rung — makes 38; V41's
                // bank_statement@1.6.0 — the month-to-date fields — makes 39; V42's
                // paystub@1.3.0 — the native-text bureau captions — makes 40; V43's
                // w2@1.2.0 — the ADP caption alternates — makes 41; V44's schedule_d@1.0.0
                // and schedule_f@1.0.0 — two births, nothing retired — make 43; V45's
                // tax_return@1.2.0 — the real 1040's geometry, retiring 1.1.0 — makes 44;
                // V46's four births (schedule_1, schedule_2, form_8962, state_tax_return,
                // all 1.0.0) make 48; V47's w2@1.3.0 and tax_return@1.3.0 — the name read
                // across its split cells, retiring both 1.2.0 rows — make 50; V48's
                // paystub@1.4.0 and schedule_c@1.1.0 — the gross pair off the totals
                // strip / `Total Earnings` line and the business name under its box
                // letter, retiring 1.3.0 and 1.0.0 — make 52; V49's tax_return@1.4.0 —
                // the bare whole-dollar money column, retiring 1.3.0 — makes 53; V50's
                // property_tax_statement@1.0.0 — a birth, nothing retired — makes 54; V53's
                // paystub@1.5.0 — the earnings lines and closing totals, retiring 1.4.0 —
                // makes 55; V54's bank_statement@1.7.0 — the three real layouts, retiring
                // 1.6.0 — makes 56.
                assertThat(visible.getInt(1)).as("global schemas readable").isEqualTo(56);
            }
            int deleted =
                    statement.executeUpdate("DELETE FROM extraction_schema WHERE org_id IS NULL");
            assertThat(deleted).as("DELETE of global schemas must affect zero rows").isZero();
            int hijacked =
                    statement.executeUpdate(
                            "UPDATE extraction_schema SET org_id ="
                                    + " '66666666-6666-6666-6666-666666666666' WHERE org_id IS NULL");
            assertThat(hijacked).as("UPDATE-hijack of global schemas must affect zero rows").isZero();
        }
    }

    @Test
    void at_most_one_current_extracted_field_per_document_and_name() throws Exception {
        // The supersession code path rides on this database guarantee, exactly like
        // classification_result_one_current.
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO tenant (id, name, status) VALUES"
                            + " ('77777777-7777-7777-7777-777777777777', 'Org EF', 'ACTIVE')"
                            + " ON CONFLICT (id) DO NOTHING");
            statement.execute(
                    "INSERT INTO document_package (id, org_id, name) VALUES"
                            + " ('88888888-8888-8888-8888-888888888888',"
                            + " '77777777-7777-7777-7777-777777777777', 'one-current-pkg')"
                            + " ON CONFLICT (id) DO NOTHING");
            statement.execute(
                    "INSERT INTO logical_document (id, org_id, package_id, ordinal,"
                            + " document_type_code) VALUES"
                            + " ('99999999-9999-9999-9999-999999999999',"
                            + " '77777777-7777-7777-7777-777777777777',"
                            + " '88888888-8888-8888-8888-888888888888', 0, 'PAYSTUB')"
                            + " ON CONFLICT (id) DO NOTHING");
            String insert =
                    "INSERT INTO extracted_field (org_id, logical_document_id, schema_id,"
                        + " field_name, data_type, extraction_method, extractor_version,"
                        + " confidence) VALUES ('77777777-7777-7777-7777-777777777777',"
                        + " '99999999-9999-9999-9999-999999999999',"
                        + " (SELECT id FROM extraction_schema WHERE org_id IS NULL"
                        + "    AND document_type_code = 'PAYSTUB' AND version = '1.0.0'),"
                        + " 'netPay', 'MONEY', 'NONE', 'engine/1.0.0', 0)";
            statement.execute(insert);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.execute(insert))
                    .hasMessageContaining("extracted_field_one_current");
        }
    }

    @Test
    void the_rebuilt_current_row_index_keys_on_group_key_too() throws Exception {
        // Spec 5a D1: the partial unique index becomes
        //   (org_id, logical_document_id, field_name, coalesce(group_key, ''))
        // so a field may legitimately repeat under DISTINCT printed keys (Schedule E's three
        // property columns) while an ungrouped field keeps EXACTLY today's rule — a NULL key
        // collapses to '' and still collides with another NULL. That equivalence is the
        // migration's central claim, and this is where it is proven at the database.
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO tenant (id, name, status) VALUES"
                            + " ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Org GK', 'ACTIVE')"
                            + " ON CONFLICT (id) DO NOTHING");
            statement.execute(
                    "INSERT INTO document_package (id, org_id, name) VALUES"
                            + " ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',"
                            + " 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'group-key-pkg')"
                            + " ON CONFLICT (id) DO NOTHING");
            statement.execute(
                    "INSERT INTO logical_document (id, org_id, package_id, ordinal,"
                            + " document_type_code) VALUES"
                            + " ('cccccccc-cccc-cccc-cccc-cccccccccccc',"
                            + " 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',"
                            + " 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 0, 'PAYSTUB')"
                            + " ON CONFLICT (id) DO NOTHING");

            // Three property columns, one field name — the whole point of the spec.
            statement.execute(insertRentsReceived("'A'"));
            statement.execute(insertRentsReceived("'B'"));
            statement.execute(insertRentsReceived("'C'"));
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> statement.execute(insertRentsReceived("'A'")))
                    .as("the same key twice is still one value per name per document")
                    .hasMessageContaining("extracted_field_one_current");

            // And the ungrouped rule is untouched: NULL collapses to '' and collides.
            statement.execute(insertRentsReceived("NULL"));
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> statement.execute(insertRentsReceived("NULL")))
                    .as("coalesce(group_key,'') preserves today's rule for ungrouped rows")
                    .hasMessageContaining("extracted_field_one_current");
        }
    }

    /** One extracted_field INSERT for the group-key test, with the given group_key literal. */
    private static String insertRentsReceived(String groupKeyLiteral) {
        return "INSERT INTO extracted_field (org_id, logical_document_id, schema_id,"
                + " field_name, data_type, extraction_method, extractor_version,"
                + " confidence, group_key) VALUES"
                + " ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',"
                + " 'cccccccc-cccc-cccc-cccc-cccccccccccc',"
                + " (SELECT id FROM extraction_schema WHERE org_id IS NULL"
                + "    AND document_type_code = 'PAYSTUB' AND version = '1.0.0'),"
                + " 'rentsReceived', 'MONEY', 'NONE', 'engine/1.0.0', 0, "
                + groupKeyLiteral
                + ")";
    }

    @Test
    void phase7_review_and_audit_tables_exist() throws Exception {
        // V8 creates all four Layer-3/4 tables (docs/DATA_MODEL.md 7). validation_finding and
        // ai_interpretation are TABLE-ONLY this phase (Spec 4/5 add rules, not migrations); the
        // structural trio below still covers them for FORCE RLS + policy + org_id.
        assertThat(query("SELECT tablename FROM pg_tables WHERE schemaname = 'public'"))
                .contains(
                        "validation_finding",
                        "ai_interpretation",
                        "review_decision",
                        "audit_event");
    }

    @Test
    void review_decision_has_no_is_current_column() throws Exception {
        // Append-only with NO supersession flag (docs/DATA_MODEL.md 7): a correction is a new row,
        // the effective value is derived at read time — never an in-place is_current flip.
        assertThat(query(
                        """
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'review_decision'
                        ORDER BY column_name
                        """))
                .doesNotContain("is_current")
                .contains("previous_value", "new_value", "decided_by", "decided_at");
    }

    @Test
    void v9_allows_the_page_verdict_decision_values() throws Exception {
        // After V9: an OVERRIDE_VERDICT action on a PAGE subject satisfies review_decision's two
        // CHECK constraints. Inserted over the superuser test connection (which bypasses RLS, so
        // the CHECK constraints — not tenant isolation — are what this exercises), mirroring the
        // classification_result one-current test above.
        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO tenant (id, name, status) VALUES"
                            + " ('a9a9a9a9-a9a9-a9a9-a9a9-a9a9a9a9a9a9', 'Org V9', 'ACTIVE')"
                            + " ON CONFLICT (id) DO NOTHING");
            int inserted =
                    statement.executeUpdate(
                            "INSERT INTO review_decision (org_id, subject_type, subject_id, action,"
                                + " decided_by) VALUES"
                                + " ('a9a9a9a9-a9a9-a9a9-a9a9-a9a9a9a9a9a9', 'PAGE',"
                                + " gen_random_uuid(), 'OVERRIDE_VERDICT', gen_random_uuid())");
            assertThat(inserted).isEqualTo(1);
        }
    }

    @Test
    void every_table_forces_row_level_security() throws Exception {
        List<String> unprotected =
                query(
                        """
                        SELECT c.relname
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public'
                          AND c.relkind = 'r'
                          AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
                        ORDER BY c.relname
                        """);
        unprotected.removeAll(EXEMPT);

        assertThat(unprotected)
                .withFailMessage(
                        "Tables without FORCE ROW LEVEL SECURITY: %s — every tenant table must be"
                                + " isolated; see V1__extensions_and_tenancy.sql",
                        unprotected)
                .isEmpty();
    }

    @Test
    void every_rls_table_has_a_policy() throws Exception {
        List<String> missingPolicy =
                query(
                        """
                        SELECT c.relname
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public'
                          AND c.relkind = 'r'
                          AND c.relrowsecurity
                          AND NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)
                        ORDER BY c.relname
                        """);

        assertThat(missingPolicy)
                .withFailMessage(
                        "RLS enabled but no policy (deny-all lockout): %s", missingPolicy)
                .isEmpty();
    }

    @Test
    void every_tenant_table_carries_org_id() throws Exception {
        // tenant is keyed by its own id; everything else must carry org_id.
        List<String> withoutOrg =
                query(
                        """
                        SELECT t.tablename
                        FROM pg_tables t
                        WHERE t.schemaname = 'public'
                          AND t.tablename NOT IN ('flyway_schema_history', 'tenant')
                          AND NOT EXISTS (
                              SELECT 1 FROM information_schema.columns c
                              WHERE c.table_schema = 'public'
                                AND c.table_name = t.tablename
                                AND c.column_name = 'org_id'
                          )
                        ORDER BY t.tablename
                        """);

        assertThat(withoutOrg)
                .withFailMessage("Tables missing org_id: %s", withoutOrg)
                .isEmpty();
    }

    // ── V22: the api-key service-auth bootstrap ──────────────────────────────

    /**
     * api_key keeps ENABLE + its isolation policy but drops FORCE (V22). ENABLE-without-FORCE is the
     * exact shape the auth bootstrap needs: the app role stays isolated, the owner is freed to
     * resolve a key by hash before a tenant is known. This is why api_key is in {@link #EXEMPT}.
     */
    @Test
    void api_key_is_enable_but_not_force_rls_for_the_auth_bootstrap() throws Exception {
        assertThat(query(
                        """
                        SELECT relrowsecurity || ',' || relforcerowsecurity
                          FROM pg_class c
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public' AND c.relname = 'api_key'
                        """))
                .as("api_key: RLS ENABLED, FORCE dropped")
                .containsExactly("true,false");

        // The isolation policy still exists — app-layer tenant isolation on api_key is unchanged.
        assertThat(query(
                        "SELECT polname FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid"
                                + " WHERE c.relname = 'api_key'"))
                .containsExactly("api_key_isolation");
    }

    /**
     * The crux: {@code api_key_authenticate} bypasses RLS (resolves a key on an unstamped
     * connection), while the same non-owner role reading api_key directly sees NOTHING. If the
     * function did not bypass RLS the whole service-auth path could never learn a key's org, and if
     * the direct read were NOT blocked the NO FORCE would have leaked tenant isolation.
     */
    @Test
    void the_authenticate_function_bypasses_rls_but_a_direct_read_does_not() throws Exception {
        UUID org = UUID.fromString("b1b1b1b1-b1b1-b1b1-b1b1-b1b1b1b1b1b1");
        String hash = "deadbeef".repeat(8); // 64 hex chars — shape of an HMAC-SHA256 hex digest

        try (Connection connection =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            // Seed as the owner (bypasses RLS): a tenant and one key for it.
            statement.execute(
                    "INSERT INTO tenant (id, name, status) VALUES ('"
                            + org
                            + "', 'Org APIKEY', 'ACTIVE') ON CONFLICT (id) DO NOTHING");
            statement.execute(
                    "INSERT INTO api_key (org_id, name, key_hash, scopes) VALUES ('"
                            + org
                            + "', 'suite', '"
                            + hash
                            + "', ARRAY['PROCESSOR'])");

            // Become the non-owner app role with NO tenant bound (current_org() is NULL).
            statement.execute("SET ROLE docengine_app");

            // A direct read, RLS-governed, sees nothing — proving NO FORCE did NOT open the table.
            try (ResultSet direct =
                    statement.executeQuery(
                            "SELECT count(*) FROM api_key WHERE key_hash = '" + hash + "'")) {
                direct.next();
                assertThat(direct.getInt(1))
                        .as("direct read as docengine_app with no tenant sees no keys")
                        .isZero();
            }

            // The SECURITY DEFINER function resolves the very same row — the bootstrap.
            try (ResultSet viaFn =
                    statement.executeQuery(
                            "SELECT org_id::text, scopes[1] FROM api_key_authenticate('"
                                    + hash
                                    + "')")) {
                assertThat(viaFn.next()).as("function resolves the key on an unstamped connection").isTrue();
                assertThat(viaFn.getString(1)).isEqualTo(org.toString());
                assertThat(viaFn.getString(2)).isEqualTo("PROCESSOR");
                assertThat(viaFn.next()).as("exactly one row for an exact hash").isFalse();
            }

            // A hash nobody holds resolves to nothing — no enumeration.
            try (ResultSet miss =
                    statement.executeQuery(
                            "SELECT count(*) FROM api_key_authenticate('" + "0".repeat(64) + "')")) {
                miss.next();
                assertThat(miss.getInt(1)).as("unknown hash resolves to no row").isZero();
            }
        }
    }
}
