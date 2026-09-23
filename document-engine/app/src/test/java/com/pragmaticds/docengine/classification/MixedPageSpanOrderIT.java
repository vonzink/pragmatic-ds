package com.pragmaticds.docengine.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import com.pragmaticds.docengine.parsing.repo.TextSpanRepository;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 4 review finding: MIXED pages carry TWO span sources whose ordinals each start
 * at 0. Ordering by ordinal alone interleaves NATIVE and OCR spans with ties broken by
 * heap order — the classifier's joined reading-order text became NONDETERMINISTIC, so
 * anchors could match or miss run to run. Classification must consume spans in the
 * deterministic (source, ordinal) order: the NATIVE block, then the OCR block — the
 * same convention layout persistence already pinned for exactly this ambiguity.
 */
class MixedPageSpanOrderIT extends AbstractPostgresIT {

    @Autowired TextSpanRepository spans;

    private JdbcTemplate jdbc;

    @BeforeEach
    void bind() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void mixed_page_spans_come_back_native_block_then_ocr_block() {
        UUID packageId = UUID.randomUUID();
        jdbc.update("INSERT INTO document_package (id, org_id, name) VALUES (?, ?, 'mixed-order')",
                packageId, ORG_DEV);
        UUID fileId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,
                    content_type, size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'm.pdf', 'application/pdf', 10, repeat('a', 64), 'k')
                """, fileId, ORG_DEV, packageId);
        UUID pageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO page (id, org_id, source_file_id, package_id, page_index,
                    package_page_index, width_pt, height_pt, rotation, text_layer)
                VALUES (?, ?, ?, ?, 0, 0, 612, 792, 0, 'MIXED')
                """, pageId, ORG_DEV, fileId, packageId);

        // OCR span inserted FIRST so an ordinal-only ORDER BY breaks the tie toward
        // the wrong interleave ("Balance Ending") under the pre-fix behavior.
        jdbc.update("""
                INSERT INTO text_span (org_id, page_id, ordinal, text, x, y, width, height,
                    source, ocr_engine, confidence)
                VALUES (?, ?, 0, 'Balance', 100, 100, 40, 12, 'OCR', 'RAPIDOCR', 0.95)
                """, ORG_DEV, pageId);
        jdbc.update("""
                INSERT INTO text_span (org_id, page_id, ordinal, text, x, y, width, height,
                    source, confidence)
                VALUES (?, ?, 0, 'Ending', 60, 100, 36, 12, 'NATIVE', 1.0)
                """, ORG_DEV, pageId);

        List<TextSpan> ordered = spans.findByPageIdOrderBySourceAscOrdinalAsc(pageId);

        assertThat(ordered).extracting(TextSpan::getText).containsExactly("Ending", "Balance");
    }
}
