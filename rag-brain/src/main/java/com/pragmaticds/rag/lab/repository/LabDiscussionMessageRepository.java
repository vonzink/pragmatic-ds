package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabDiscussionMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** The two encrypted bodies of each exchange, in transcript order. */
public interface LabDiscussionMessageRepository extends JpaRepository<LabDiscussionMessage, UUID> {

    List<LabDiscussionMessage> findByExchangeIdOrderByOrdinalAsc(UUID exchangeId);

    List<LabDiscussionMessage> findByExchangeIdInOrderByOrdinalAsc(List<UUID> exchangeIds);

    /** FK-safe purge step: encrypted bodies go first, before their exchanges. */
    @Modifying
    @Query("delete from LabDiscussionMessage m where m.exchangeId in "
            + "(select e.id from LabDiscussionExchange e where e.runId = :runId)")
    int deleteByRunId(@Param("runId") UUID runId);
}
