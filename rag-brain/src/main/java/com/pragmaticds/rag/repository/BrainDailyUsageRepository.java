package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.BrainDailyUsage;
import com.pragmaticds.rag.domain.BrainDailyUsageId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrainDailyUsageRepository extends JpaRepository<BrainDailyUsage, BrainDailyUsageId> {

    Optional<BrainDailyUsage> findByBrainIdAndUsageDate(UUID brainId, LocalDate usageDate);

    /**
     * Atomically accumulates one paid call's usage into today's row, creating it
     * if absent. Used by SpendGuardService.recordSpend so concurrent requests
     * (including across app instances) never lose an increment to a race.
     *
     * {@code @Transactional} is required: SpendGuardService.recordSpend runs on
     * the deliberately non-transactional answer pipeline (AskService), so without
     * its own transaction this {@code @Modifying} executeUpdate throws
     * TransactionRequiredException, surfacing as a 500 on every ANSWER. The
     * short REQUIRED transaction pins a connection only for this one fast upsert,
     * never across the pipeline's embedding/LLM I/O.
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO brain_daily_usage
                (brain_id, usage_date, request_count, prompt_tokens, completion_tokens, cost_estimate_usd)
            VALUES (:brainId, :usageDate, 1, :promptTokens, :completionTokens, :costEstimateUsd)
            ON CONFLICT (brain_id, usage_date) DO UPDATE SET
                request_count = brain_daily_usage.request_count + 1,
                prompt_tokens = brain_daily_usage.prompt_tokens + EXCLUDED.prompt_tokens,
                completion_tokens = brain_daily_usage.completion_tokens + EXCLUDED.completion_tokens,
                cost_estimate_usd = brain_daily_usage.cost_estimate_usd + EXCLUDED.cost_estimate_usd
            """, nativeQuery = true)
    void upsertSpend(@Param("brainId") UUID brainId,
                      @Param("usageDate") LocalDate usageDate,
                      @Param("promptTokens") long promptTokens,
                      @Param("completionTokens") long completionTokens,
                      @Param("costEstimateUsd") BigDecimal costEstimateUsd);
}
