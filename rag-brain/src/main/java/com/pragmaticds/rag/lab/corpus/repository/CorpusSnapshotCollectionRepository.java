package com.pragmaticds.rag.lab.corpus.repository;

import com.pragmaticds.rag.lab.corpus.domain.CorpusSnapshotCollection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CorpusSnapshotCollectionRepository
        extends JpaRepository<CorpusSnapshotCollection, CorpusSnapshotCollection.Id> {
    List<CorpusSnapshotCollection> findAllByIdSnapshotIdOrderByIdPositionAsc(UUID snapshotId);
}
