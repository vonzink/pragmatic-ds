package com.pragmaticds.rag.lab.run.repository;

import com.pragmaticds.rag.lab.run.domain.LabInstancePointerEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Append-only pointer history, newest first. */
public interface LabInstancePointerEventRepository
        extends JpaRepository<LabInstancePointerEvent, UUID> {

    List<LabInstancePointerEvent> findByBrainIdAndInstanceSlugOrderByPointerVersionDesc(
            UUID brainId, String instanceSlug);
}
