package com.pragmaticds.rag.lab.corpus.repository;

import com.pragmaticds.rag.lab.corpus.domain.CorpusSnapshotDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CorpusSnapshotDocumentRepository
        extends JpaRepository<CorpusSnapshotDocument, CorpusSnapshotDocument.Id> {
    List<CorpusSnapshotDocument>
        findAllByIdSnapshotIdOrderByIdCollectionIdAscIdDocumentIdAsc(UUID snapshotId);
}
