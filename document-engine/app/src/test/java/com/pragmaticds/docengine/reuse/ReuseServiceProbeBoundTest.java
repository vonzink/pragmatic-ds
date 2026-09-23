package com.pragmaticds.docengine.reuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pragmaticds.docengine.ingestion.ReuseProbePort;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJobRepository;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import com.pragmaticds.docengine.results.domain.EngineResult;
import com.pragmaticds.docengine.results.repo.EngineResultRepository;
import com.pragmaticds.docengine.results.service.EngineResultQueryService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * The reuse probe's nested candidate check runs in its own REQUIRES_NEW transaction while the
 * caller's {@code UploadService.upload} transaction still holds a connection — deliberately, so a
 * rejecting candidate cannot mark the upload's transaction rollback-only. The cost is that every
 * upload inside the probe transiently wants TWO connections, so N concurrent uploads that find
 * candidates want 2N: the reuse optimisation can exhaust the pool that the uploads themselves need.
 *
 * <p>So the second borrow is bounded by permits, and an upload that cannot get one does not queue
 * for it — it parses fresh. Losing a reuse hit costs work; waiting on a connection costs the
 * upload.
 */
class ReuseServiceProbeBoundTest {

    private static final UUID ORG = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final String FINGERPRINT = "a".repeat(64);

    private static final List<ReuseProbePort.FileIdentity> FILES =
            List.of(new ReuseProbePort.FileIdentity(0, "b".repeat(64), 11L, "application/pdf"));

    @Test
    void anUploadThatCannotBorrowASecondConnectionParsesFreshInsteadOfWaiting() throws Exception {
        EngineResultRepository results = mock(EngineResultRepository.class);
        ProcessingJobRepository jobs = mock(ProcessingJobRepository.class);
        EngineResultQueryService currentResults = mock(EngineResultQueryService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        EngineResult candidate = mock(EngineResult.class);
        when(candidate.getPackageId()).thenReturn(UUID.randomUUID());
        when(results.findByOrgIdAndSourceSetSha256OrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of(candidate));

        CountDownLatch insideNestedTransaction = new CountDownLatch(1);
        CountDownLatch releaseNestedTransaction = new CountDownLatch(1);
        when(transactionManager.getTransaction(any()))
                .thenAnswer(
                        invocation -> {
                            insideNestedTransaction.countDown();
                            releaseNestedTransaction.await(10, TimeUnit.SECONDS);
                            return mock(TransactionStatus.class);
                        });

        ReuseService service =
                new ReuseService(
                        true,
                        1,
                        50,
                        mock(ReuseFingerprintService.class),
                        results,
                        jobs,
                        currentResults,
                        transactionManager);

        Thread holder =
                new Thread(
                        () -> {
                            TenantContext.set(ORG);
                            try {
                                service.findReusable(FILES, FINGERPRINT);
                            } finally {
                                TenantContext.clear();
                            }
                        });
        holder.start();
        assertThat(insideNestedTransaction.await(10, TimeUnit.SECONDS))
                .as("the first probe reached its nested transaction")
                .isTrue();

        TenantContext.set(ORG);
        try {
            Optional<ReuseProbePort.ReuseHit> whileTheOnlyPermitIsHeld =
                    service.findReusable(FILES, FINGERPRINT);

            assertThat(whileTheOnlyPermitIsHeld)
                    .as("no permit means no reuse — never a wait for a connection")
                    .isEmpty();
            // The decisive assertion, made while the first probe is STILL inside its nested
            // transaction: the second upload never even LOOKED for a candidate, so it cannot
            // have opened the nested transaction that borrows the second connection. Asserting
            // it after releasing the holder would only prove the holder had finished.
            verify(currentResults, never()).current(any());
        } finally {
            TenantContext.clear();
            releaseNestedTransaction.countDown();
            holder.join(10_000);
        }
    }

    @Test
    void thePermitIsReleasedSoTheNextUploadCanStillReuse() {
        EngineResultRepository results = mock(EngineResultRepository.class);
        when(results.findByOrgIdAndSourceSetSha256OrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());

        ReuseService service =
                new ReuseService(
                        true,
                        1,
                        50,
                        mock(ReuseFingerprintService.class),
                        results,
                        mock(ProcessingJobRepository.class),
                        mock(EngineResultQueryService.class),
                        mock(PlatformTransactionManager.class));

        TenantContext.set(ORG);
        try {
            for (int i = 0; i < 3; i++) {
                assertThat(service.findReusable(FILES, FINGERPRINT)).isEmpty();
            }
        } finally {
            TenantContext.clear();
        }
        // Three scans with one permit prove the permit came back each time; a leak would have
        // silently disabled reuse from the second upload onward.
        verify(results, org.mockito.Mockito.times(3))
                .findByOrgIdAndSourceSetSha256OrderByCreatedAtDesc(any(), any(), any());
    }
}
