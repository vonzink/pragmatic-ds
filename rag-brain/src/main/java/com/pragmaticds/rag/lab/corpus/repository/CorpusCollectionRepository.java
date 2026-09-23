package com.pragmaticds.rag.lab.corpus.repository;

import com.pragmaticds.rag.lab.corpus.domain.CorpusCollection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CorpusCollectionRepository extends JpaRepository<CorpusCollection, UUID> {
    Optional<CorpusCollection> findByIdAndBrainId(UUID id, UUID brainId);
    Optional<CorpusCollection> findByBrainIdAndSlug(UUID brainId, String slug);
    List<CorpusCollection> findAllByBrainIdOrderByDisplayNameAsc(UUID brainId);
    boolean existsByBrainIdAndSlug(UUID brainId, String slug);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select collection from CorpusCollection collection "
            + "where collection.id = :id and collection.brainId = :brainId")
    Optional<CorpusCollection> lockByIdAndBrainId(
            @Param("id") UUID id, @Param("brainId") UUID brainId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select collection from CorpusCollection collection "
            + "where collection.brainId = :brainId and collection.id in :ids "
            + "order by collection.id")
    List<CorpusCollection> lockAllByBrainIdAndIds(
            @Param("brainId") UUID brainId, @Param("ids") List<UUID> ids);
}
