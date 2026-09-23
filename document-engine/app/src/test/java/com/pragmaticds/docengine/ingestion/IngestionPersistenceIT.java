package com.pragmaticds.docengine.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.ingestion.domain.DocumentPackage;
import com.pragmaticds.docengine.ingestion.domain.MalwareScanStatus;
import com.pragmaticds.docengine.ingestion.domain.ReviewStatus;
import com.pragmaticds.docengine.ingestion.domain.SourceFile;
import com.pragmaticds.docengine.ingestion.repo.DocumentPackageRepository;
import com.pragmaticds.docengine.ingestion.repo.SourceFileRepository;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the V2 mapping and the repository contract the upload path depends on: tenant stamping,
 * cross-tenant misses, ordinal ordering, and the cross-package duplicate probe. If any of this is
 * wrong, UploadService cannot be trusted no matter what its own tests say.
 */
class IngestionPersistenceIT extends AbstractPostgresIT {

    @Autowired DocumentPackageRepository packages;
    @Autowired SourceFileRepository sourceFiles;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(ORG_DEV);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static String sha(char filler) {
        return String.valueOf(filler).repeat(64);
    }

    private SourceFile newFile(UUID packageId, int ordinal, String sha256) {
        SourceFile file =
                new SourceFile(
                        packageId,
                        ordinal,
                        "file-" + ordinal + ".pdf",
                        "application/pdf",
                        "application/pdf",
                        1234L,
                        sha256,
                        3,
                        false,
                        MalwareScanStatus.SKIPPED);
        file.assignStorageKey(ORG_DEV + "/" + packageId + "/" + UUID.randomUUID() + "/original");
        return file;
    }

    @Test
    void a_saved_package_is_stamped_with_the_org_and_defaults_to_not_reviewed() {
        DocumentPackage saved = packages.save(new DocumentPackage(null, "March paystubs"));

        Optional<DocumentPackage> reloaded = packages.findByIdAndOrgId(saved.getId(), ORG_DEV);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getOrgId()).isEqualTo(ORG_DEV);
        assertThat(reloaded.get().getReviewStatus()).isEqualTo(ReviewStatus.NOT_REVIEWED);
        assertThat(reloaded.get().getName()).isEqualTo("March paystubs");
        assertThat(reloaded.get().getPageCount()).isZero();
    }

    @Test
    void a_package_is_invisible_from_another_org() {
        DocumentPackage saved = packages.save(new DocumentPackage(null, null));

        assertThat(packages.findByIdAndOrgId(saved.getId(), ORG_OTHER)).isEmpty();
    }

    @Test
    void a_source_file_round_trips_every_ingestion_column() {
        DocumentPackage pkg = packages.save(new DocumentPackage(null, null));
        SourceFile file =
                new SourceFile(
                        pkg.getId(),
                        0,
                        "w2.png",
                        "image/png",
                        "application/pdf",
                        987L,
                        sha('a'),
                        null,
                        false,
                        MalwareScanStatus.SKIPPED);
        file.assignStorageKey(ORG_DEV + "/" + pkg.getId() + "/x/original");
        SourceFile saved = sourceFiles.save(file);

        SourceFile reloaded = sourceFiles.findByIdAndOrgId(saved.getId(), ORG_DEV).orElseThrow();
        assertThat(reloaded.getPackageId()).isEqualTo(pkg.getId());
        assertThat(reloaded.getOrdinal()).isZero();
        assertThat(reloaded.getOriginalFilename()).isEqualTo("w2.png");
        assertThat(reloaded.getContentType()).isEqualTo("image/png");
        assertThat(reloaded.getDeclaredContentType()).isEqualTo("application/pdf");
        assertThat(reloaded.getSizeBytes()).isEqualTo(987L);
        assertThat(reloaded.getSha256()).isEqualTo(sha('a'));
        assertThat(reloaded.getStorageKeyOriginal()).isEqualTo(ORG_DEV + "/" + pkg.getId() + "/x/original");
        assertThat(reloaded.getStorageKeyNormalized()).isNull();
        assertThat(reloaded.getPageCount()).isNull();
        assertThat(reloaded.isEncrypted()).isFalse();
        assertThat(reloaded.getMalwareScanStatus()).isEqualTo(MalwareScanStatus.SKIPPED);
        assertThat(reloaded.getOrgId()).isEqualTo(ORG_DEV);
    }

    @Test
    void an_encrypted_but_readable_source_file_round_trips_the_flag() {
        // is_encrypted is provenance, not a verdict: an owner-password-only PDF is ACCEPTED and
        // parsed (it opens with the empty user password), and the column is how a reviewer learns
        // the lender shipped it encrypted. A file needing a real user password never gets a row.
        DocumentPackage pkg = packages.save(new DocumentPackage(null, null));
        SourceFile file =
                new SourceFile(
                        pkg.getId(),
                        0,
                        "owner-locked-statement.pdf",
                        "application/pdf",
                        "application/pdf",
                        4321L,
                        sha('f'),
                        2,
                        true,
                        MalwareScanStatus.SKIPPED);
        file.assignStorageKey(ORG_DEV + "/" + pkg.getId() + "/y/original");
        SourceFile saved = sourceFiles.save(file);

        SourceFile reloaded = sourceFiles.findByIdAndOrgId(saved.getId(), ORG_DEV).orElseThrow();
        assertThat(reloaded.isEncrypted()).isTrue();
        assertThat(reloaded.getPageCount()).isEqualTo(2);
    }

    @Test
    void a_source_file_is_invisible_from_another_org() {
        DocumentPackage pkg = packages.save(new DocumentPackage(null, null));
        SourceFile saved = sourceFiles.save(newFile(pkg.getId(), 0, sha('b')));

        assertThat(sourceFiles.findByIdAndOrgId(saved.getId(), ORG_OTHER)).isEmpty();
        assertThat(sourceFiles.findByIdAndOrgId(saved.getId(), ORG_DEV)).isPresent();
    }

    @Test
    void files_come_back_in_ordinal_order_not_insertion_order() {
        DocumentPackage pkg = packages.save(new DocumentPackage(null, null));
        sourceFiles.save(newFile(pkg.getId(), 2, sha('c')));
        sourceFiles.save(newFile(pkg.getId(), 0, sha('d')));
        sourceFiles.save(newFile(pkg.getId(), 1, sha('e')));

        List<SourceFile> ordered = sourceFiles.findByPackageIdOrderByOrdinal(pkg.getId());

        assertThat(ordered).extracting(SourceFile::getOrdinal).containsExactly(0, 1, 2);
    }

    @Test
    void the_duplicate_probe_finds_the_same_sha_in_another_package_but_never_in_this_one() {
        DocumentPackage first = packages.save(new DocumentPackage(null, null));
        DocumentPackage second = packages.save(new DocumentPackage(null, null));
        SourceFile original = sourceFiles.save(newFile(first.getId(), 0, sha('f')));

        Optional<SourceFile> fromOtherPackage =
                sourceFiles.findFirstByOrgIdAndSha256AndPackageIdNot(
                        ORG_DEV, sha('f'), second.getId());
        Optional<SourceFile> excludingItsOwnPackage =
                sourceFiles.findFirstByOrgIdAndSha256AndPackageIdNot(
                        ORG_DEV, sha('f'), first.getId());

        assertThat(fromOtherPackage).map(SourceFile::getId).contains(original.getId());
        assertThat(excludingItsOwnPackage).isEmpty();
    }
}
