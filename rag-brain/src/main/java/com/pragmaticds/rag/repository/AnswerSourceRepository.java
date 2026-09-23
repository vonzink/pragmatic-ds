package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.AnswerSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnswerSourceRepository extends JpaRepository<AnswerSource, UUID> {

    List<AnswerSource> findByMessageId(UUID messageId);
}
