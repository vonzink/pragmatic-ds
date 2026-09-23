package com.pragmaticds.docengine.ingestion.web;

import com.pragmaticds.docengine.ingestion.domain.DocumentPackage;
import com.pragmaticds.docengine.ingestion.domain.SourceFile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET view of a package and its files, in ordinal order. A deliberate projection — entities never
 * serialize directly, so adding a column later is a decision here rather than a silent API change.
 */
public record PackageView(
        UUID id,
        UUID loanId,
        String name,
        int pageCount,
        String reviewStatus,
        Instant createdAt,
        List<FileView> files) {

    public record FileView(
            UUID id,
            int ordinal,
            String originalFilename,
            String contentType,
            String declaredContentType,
            long sizeBytes,
            String sha256,
            Integer pageCount,
            String malwareScanStatus) {

        static FileView of(SourceFile file) {
            return new FileView(
                    file.getId(),
                    file.getOrdinal(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getDeclaredContentType(),
                    file.getSizeBytes(),
                    file.getSha256(),
                    file.getPageCount(),
                    file.getMalwareScanStatus().name());
        }
    }

    public static PackageView of(DocumentPackage pkg, List<SourceFile> files) {
        return new PackageView(
                pkg.getId(),
                pkg.getLoanId(),
                pkg.getName(),
                pkg.getPageCount(),
                pkg.getReviewStatus().name(),
                pkg.getCreatedAt(),
                files.stream().map(FileView::of).toList());
    }
}
