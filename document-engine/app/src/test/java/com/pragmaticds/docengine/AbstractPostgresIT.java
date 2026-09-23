package com.pragmaticds.docengine;

import com.pragmaticds.docengine.platform.tenancy.DevTenantFilter;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for integration tests: one pgvector container for the whole JVM (fast), Flyway
 * schema, and two seeded organizations so every IT can prove tenant isolation, not just function.
 *
 * <p>The container is started once in a static initializer and deliberately NOT stopped per class —
 * Testcontainers' Ryuk reaps it at JVM exit. Per-class containers would re-migrate for every IT.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    protected static final PostgreSQLContainer<?> POSTGRES;

    /** The org every request runs as in Phase 1 (see DevTenantFilter). */
    protected static final UUID ORG_DEV = DevTenantFilter.DEV_ORG;

    /** A second org that must never see ORG_DEV's data. */
    protected static final UUID ORG_OTHER =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    static {
        POSTGRES = new PostgreSQLContainer<>(PGVECTOR);
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Centralised so no IT can forget it: the storage default (./data/blobs)
        // resolves inside the repo, and blobs written there end up in git — which
        // happened once with synthetic test PDFs and must never happen again.
        registry.add(
                "docengine.storage.local-root",
                () -> System.getProperty("user.dir") + "/build/test-blobs");
        // A fixed secret so signed-download tokens verify in-test (prod sets its own). Without it
        // SignedUrlService fails closed and the signed-URL endpoints could not be exercised.
        registry.add("docengine.signed-url.secret", () -> "integration-test-signing-secret");
        // Disable the scheduled retention sweep in tests: RetentionPurgeIT drives it synchronously,
        // and a background sweep must never race other ITs' data. "-" is Spring's disabled cron.
        registry.add("docengine.retention.purge-cron", () -> "-");
    }

    @Autowired protected DataSource dataSource;

    @BeforeEach
    void seedTenants() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update(
                """
                INSERT INTO tenant (id, name, status) VALUES (?, 'Dev Org', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                ORG_DEV);
        jdbc.update(
                """
                INSERT INTO tenant (id, name, status) VALUES (?, 'Other Org', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                ORG_OTHER);
    }

    /**
     * The FK-ordered wipe every throughput IT needs before it measures: the shared container means
     * any earlier IT class can leave rows behind for these orgs (most often {@code ORG_DEV}, the
     * fixed org {@code DevTenantFilter} runs every request as), and a stray row skews a rate
     * measurement or defeats a "nothing measured yet" assumption. Children before parents —
     * {@code text_span}/{@code layout_element}/{@code parser_output}/{@code logical_document_page}
     * all FK to {@code page} without {@code ON DELETE CASCADE} (V4/V6), and
     * {@code layout_element_span} FKs to BOTH {@code layout_element} and {@code text_span}, so it
     * goes first of those. {@code engine_result} must go before {@code processing_job}
     * ({@code engine_result_job_fk}).
     *
     * <p>{@code validation_finding} and {@code logical_document} (V8/V6) also FK to
     * {@code document_package} (the former directly, both indirectly via
     * {@code logical_document_page}) without cascade, and go before it too — found running the
     * full suite for real the first time this helper existed: {@code extracted_field} and
     * {@code field_evidence} do cascade off {@code logical_document} (V7), so they need no
     * explicit delete here.
     *
     * <p>{@code boundary_proposal} (V26) FKs to {@code document_package} without cascade too; the
     * boundary-extraction ITs leave proposals behind for {@code ORG_DEV}, and until this line
     * existed the throughput ITs failed on every CI run since V26 landed (#89) — never locally,
     * where the failing class was run on its own.
     */
    protected void wipeOrgData(UUID... orgIds) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String placeholders = String.join(", ", java.util.Collections.nCopies(orgIds.length, "?"));
        String inClause = "org_id IN (" + placeholders + ")";
        // boundary_proposal (AI document splitting) FKs to document_package without cascade and is
        // left behind by the BoundaryExtraction*ITs; whether a throughput IT then shares their
        // database depends on how Gradle partitions test classes across forks, which is why this
        // only surfaced when new IT classes were added (PR #92).
        jdbc.update("DELETE FROM boundary_proposal WHERE " + inClause, (Object[]) orgIds);
        jdbc.update("DELETE FROM engine_result WHERE " + inClause, (Object[]) orgIds);
        jdbc.update("DELETE FROM processing_stage WHERE " + inClause, (Object[]) orgIds);
        jdbc.update("DELETE FROM processing_job WHERE " + inClause, (Object[]) orgIds);
        // layout_element_span FKs to BOTH layout_element and text_span, so it goes first.
        jdbc.update("DELETE FROM layout_element_span WHERE " + inClause, (Object[]) orgIds);
        jdbc.update("DELETE FROM text_span WHERE " + inClause, (Object[]) orgIds);
        jdbc.update("DELETE FROM layout_element WHERE " + inClause, (Object[]) orgIds);
        jdbc.update("DELETE FROM parser_output WHERE " + inClause, (Object[]) orgIds);
        jdbc.update("DELETE FROM logical_document_page WHERE " + inClause, (Object[]) orgIds);
        // validation_finding FKs to logical_document (nullable, no cascade) and to
        // document_package directly — must go before both.
        jdbc.update("DELETE FROM validation_finding WHERE " + inClause, (Object[]) orgIds);
        jdbc.update("DELETE FROM logical_document WHERE " + inClause, (Object[]) orgIds);
        // boundary_proposal FKs only to document_package (job_id and page_id are deliberately
        // unconstrained, V26), so it just has to go before the package.
        jdbc.update("DELETE FROM boundary_proposal WHERE " + inClause, (Object[]) orgIds);
        jdbc.update("DELETE FROM page WHERE " + inClause, (Object[]) orgIds);
        jdbc.update("DELETE FROM source_file WHERE " + inClause, (Object[]) orgIds);
        jdbc.update("DELETE FROM document_package WHERE " + inClause, (Object[]) orgIds);
    }
}
