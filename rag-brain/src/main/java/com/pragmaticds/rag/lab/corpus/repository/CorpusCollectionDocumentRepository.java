package com.pragmaticds.rag.lab.corpus.repository;

import com.pragmaticds.rag.lab.corpus.domain.CorpusCollectionDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CorpusCollectionDocumentRepository
        extends JpaRepository<CorpusCollectionDocument, CorpusCollectionDocument.Id> {
    List<CorpusCollectionDocument> findAllByIdCollectionIdOrderByIdDocumentIdAsc(UUID collectionId);
    void deleteAllByIdCollectionId(UUID collectionId);
}
