package com.pragmaticds.docengine.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStageRepository;
import com.pragmaticds.docengine.orchestration.domain.StageStatus;
import com.pragmaticds.docengine.platform.error.DomainException;
import com.pragmaticds.docengine.platform.error.ErrorCode;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 5 review, confirmed-by-test: a stage whose work runs in a {@code @Transactional} domain
 * service JOINS the attempt's transaction. When that service throws, Spring marks the shared
 * transaction rollback-only — and the FAILED stage row that {@code StageRunner} writes afterwards
 * lives in that same transaction, so the audit record of the failure is destroyed at commit.
 *
 * <p>The consequence is an operator-visible lie: a job that failed with a known error code leaves
 * either no stage row at all or an unexplained rollback, and resume has nothing to resume from.
 * This is not extraction-specific — {@code PageClassifier} and {@code PackageSplitter} are
 * {@code @Transactional} too, so Phase 4 carried the same latent defect.
 */
@Import({SyncExecutorTestConfig.class, TransactionalStageFailureIT.ThrowingAdapterConfig.class})
@TestPropertySource(
        properties = {
            "docengine.processing.retry-backoff-ms=0",
            "docengine.processing.max-stage-attempts=1",
            // This IT's @TestConfiguration forks a fresh Spring context, and every context brings
            // its own pool against the one shared container. Cap it, as the classification ITs do.
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.main.allow-bean-definition-overriding=true"
        })
class TransactionalStageFailureIT extends AbstractPostgresIT {

    /** A domain service shaped exactly like FieldExtractionService: joins the caller's tx. */
    @Service
    static class TransactionalThrowingWork {
        @Transactional
        public void run() {
            throw new DomainException(ErrorCode.SCHEMA_NOT_FOUND, 500, Map.of("stage", "test"));
        }
    }

    @TestConfiguration
    static class ThrowingAdapterConfig {
        @Bean
        TransactionalThrowingWork transactionalThrowingWork() {
            return new TransactionalThrowingWork();
        }

        @Bean
        @Primary
        ParserPort throwingParserPort(TransactionalThrowingWork work) {
            // Mirrors WorkerParserAdapter: a DomainException from the domain service becomes a
            // failure outcome carrying its own code. The exception never escapes the port — yet
            // the transaction it passed through is already rollback-only.
            return request -> {
                if (request.stage() != ProcessingStatus.EXTRACTING) {
                    return new ParserPort.StageOutcome(true, "digest", null, Map.of());
                }
                try {
                    work.run();
                    return new ParserPort.StageOutcome(true, "digest", null, Map.of());
                } catch (DomainException e) {
                    return new ParserPort.StageOutcome(false, null, e.code(), Map.of("stage", "test"));
                }
            };
        }
    }

    @Autowired JobService jobService;
    @Autowired ProcessingStageRepository stages;

    private JdbcTemplate jdbc;

    @BeforeEach
    void bind() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    @Test
    void a_failure_thrown_inside_a_transactional_service_still_leaves_a_failed_stage_row() {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                ORG_DEV,
                "tx-poison-package");

        UUID jobId = jobService.createJob(packageId, "tx-poison").getId();

        List<ProcessingStage> extracting =
                stages.findByJobIdOrderByCreatedAtAsc(jobId).stream()
                        .filter(row -> row.getStage() == ProcessingStatus.EXTRACTING)
                        .toList();

        assertThat(extracting)
                .as("the FAILED row is the audit record of the failure — it must survive")
                .hasSize(1);
        assertThat(extracting.get(0).getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(extracting.get(0).getErrorCode()).isEqualTo(ErrorCode.SCHEMA_NOT_FOUND);
    }
}
