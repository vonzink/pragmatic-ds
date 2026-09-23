package com.pragmaticds.rag.lab.corpus.repository;

import com.pragmaticds.rag.lab.corpus.domain.CorpusSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CorpusSnapshotRepository extends JpaRepository<CorpusSnapshot, UUID> {
    Optional<CorpusSnapshot> findByIdAndBrainId(UUID id, UUID brainId);
    Optional<CorpusSnapshot> findByBrainIdAndManifestSha256(UUID brainId, String manifestSha256);
}
