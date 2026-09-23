package com.pragmaticds.rag.repository;

import com.pragmaticds.rag.domain.Brain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrainRepository extends JpaRepository<Brain, UUID> {

    @Query("select b from Brain b where b.isDefault = true")
    Optional<Brain> findDefaultBrain();

    Optional<Brain> findBySlug(String slug);
}
