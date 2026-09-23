package com.pragmaticds.rag.lab.repository;

import com.pragmaticds.rag.lab.domain.LabDocumentRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Brain/instance-scoped access to value-free engine-package registrations.
 *
 * <p>Every read path used to authorize a document or envelope view goes through the
 * brain/instance-scoped finder.
 *
 * <p>{@link #findFirstByEnginePackageIdOrderByRegisteredAtAsc(UUID)} returns the earliest
 * registration for a package. It is deliberately not a plain {@code findByEnginePackageId}: V38
 * dropped the global unique on {@code engine_package_id} so several instances in a brain can
 * select one immutable parse, which would make a single-result finder throw as soon as that
 * happens. {@link com.pragmaticds.rag.lab.repository.LabEnginePackageBindingRepository} is the
 * authority on which brain owns a package.
 */
public interface LabDocumentRegistrationRepository
        extends JpaRepository<LabDocumentRegistration, UUID> {

    Optional<LabDocumentRegistration> findByEnginePackageIdAndBrainIdAndInstanceSlug(
            UUID enginePackageId, UUID brainId, String instanceSlug);

    Optional<LabDocumentRegistration> findFirstByEnginePackageIdOrderByRegisteredAtAsc(
            UUID enginePackageId);

    /**
     * The one selection an instance may hold for a package revision.
     *
     * <p>Matches {@code uq_lab_registration_selection}, so re-selecting the same revision resolves
     * to the registration that already exists instead of colliding on insert.
     */
    Optional<LabDocumentRegistration>
            findByBrainIdAndInstanceSlugAndEnginePackageIdAndSelectedRevision(
                    UUID brainId, String instanceSlug, UUID enginePackageId, Integer selectedRevision);

    List<LabDocumentRegistration> findByBrainIdAndInstanceSlugOrderByRegisteredAtDesc(
            UUID brainId, String instanceSlug);
}
