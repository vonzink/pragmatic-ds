package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabEnginePackageBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * The authority on which brain owns an engine package.
 *
 * <p>Ownership is a primary-key lookup here, which is why it stays a single-row answer even though
 * one package may now carry many registrations. Registration finders answer a different question:
 * whether a particular brain and instance have already selected the package.
 */
public interface LabEnginePackageBindingRepository
        extends JpaRepository<LabEnginePackageBinding, UUID> {
}
