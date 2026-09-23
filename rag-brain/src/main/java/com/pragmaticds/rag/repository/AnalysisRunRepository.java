package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {
}
