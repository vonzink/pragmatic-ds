package com.pragmaticds.docengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.orchestration.ProcessingStatus;
import com.pragmaticds.docengine.platform.tenancy.TenantConnectionStampingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The application boots, Flyway owns the schema, and the pipeline stages are declared.
 *
 * <p>Runs under the {@code test} profile like the rest of the IT suite. Phase 7a made the DEFAULT
 * (no-profile) mode production-shaped — it wires the JWT resource-server chain (which needs issuer
 * configuration) and enforces the non-owner datasource assertion — so a bare superuser test
 * container can only boot under {@code test}. The default/prod wiring is proven separately by
 * {@code JwtSecurityIT}. This test's intent (boots, Flyway applied, stages declared) is unchanged.
 */
@Testcontainers
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class ApplicationContextIT {

    private static final DockerImageName PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired DataSource dataSource;

    @Test
    void test_profile_bounds_the_application_connection_pool() {
        assertThat(dataSource).isInstanceOf(TenantConnectionStampingDataSource.class);

        DataSource target =
                ((TenantConnectionStampingDataSource) dataSource).getTargetDataSource();

        assertThat(target).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) target).getMaximumPoolSize()).isEqualTo(2);
    }

    @Test
    void context_loads_and_flyway_applied_v1() {
        Integer applied =
                new JdbcTemplate(dataSource)
                        .queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);

        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void processing_stages_are_declared_in_pipeline_order() {
        assertThat(ProcessingStatus.values())
                .startsWith(
                        ProcessingStatus.UPLOADED,
                        ProcessingStatus.VALIDATING,
                        ProcessingStatus.NORMALIZING,
                        ProcessingStatus.RENDERING,
                        ProcessingStatus.TEXT_EXTRACTION,
                        ProcessingStatus.OCR_PROCESSING)
                .containsSubsequence(
                        ProcessingStatus.EXTRACTING,
                        ProcessingStatus.FINALIZING,
                        ProcessingStatus.VALIDATING_DATA)
                .endsWith(
                        ProcessingStatus.HUMAN_REVIEW_REQUIRED,
                        ProcessingStatus.COMPLETED,
                        ProcessingStatus.FAILED);
    }

    @Test
    void text_extraction_is_a_stage_distinct_from_ocr() {
        // Native text extraction and OCR fail differently, and a mixed package must
        // record both outcomes independently (docs/ARCHITECTURE.md section 4).
        assertThat(ProcessingStatus.TEXT_EXTRACTION).isNotEqualTo(ProcessingStatus.OCR_PROCESSING);
    }
}
