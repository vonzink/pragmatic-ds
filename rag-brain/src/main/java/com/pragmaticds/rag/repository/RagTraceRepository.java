package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.RagTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RagTraceRepository extends JpaRepository<RagTrace, UUID> {
}
