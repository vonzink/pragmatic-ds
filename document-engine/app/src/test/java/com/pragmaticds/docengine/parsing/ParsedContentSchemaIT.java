package com.pragmaticds.docengine.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.AbstractPostgresIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V4__parsed_content.sql structural checks (docs/DATA_MODEL.md section 4): the five parsed-content
 * tables exist with the load-bearing columns, uniqueness, and RLS. RlsCoverageIT already proves
 * every table forces RLS structurally; this IT pins the Phase 2 specifics — the constraints the
 * parsing services rely on — so a later migration cannot silently drop them.
 */
class ParsedContentSchemaIT extends AbstractPostgresIT {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """,
                String.class,
                table);
    }

    @Test
    void parsed_content_tables_exist() {
        List<String> tables =
                jdbc.queryForList(
                        "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        assertThat(tables)
                .contains(
                        "page",
                        "text_span",
                        "layout_element",
                        "layout_element_span",
                        "parser_output");
    }

    @Test
    void page_carries_geometry_verdict_and_ocr_columns() {
        assertThat(columnsOf("page"))
                .contains(
                        "id",
                        "org_id",
                        "source_file_id",
                        "package_id",
                        "page_index",
                        "package_page_index",
                        "width_pt",
                        "height_pt",
                        "rotation",
                        "detected_rotation",
                        "osd_confidence",
                        "text_layer",
                        "is_blank",
                        "blank_score",
                        "content_hash",
                        "render_storage_key",
                        "render_dpi",
                        "ocr_engine",
                        "ocr_fallback_reason",
                        "ocr_confidence_median",
                        "extraction_confidence",
                        "uncovered_regions",
                        "created_at",
                        "updated_at");
    }

    @Test
    void page_ordering_is_constrained_per_source_file_and_per_package() {
        List<String> uniques =
                jdbc.queryForList(
                        """
                        SELECT conname FROM pg_constraint
                        WHERE conrelid = 'page'::regclass AND contype = 'u'
                        """,
                        String.class);
        assertThat(uniques).contains("page_source_page_key", "page_package_page_key");
    }

    @Test
    void text_span_uses_bigint_identity_and_canonical_columns() {
        Map<String, Object> idColumn =
                jdbc.queryForMap(
                        """
                        SELECT data_type, is_identity FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'text_span'
                          AND column_name = 'id'
                        """);
        assertThat(idColumn.get("data_type")).isEqualTo("bigint");
        assertThat(idColumn.get("is_identity")).isEqualTo("YES");

        assertThat(columnsOf("text_span"))
                .contains(
                        "org_id",
                        "page_id",
                        "ordinal",
                        "text",
                        "x",
                        "y",
                        "width",
                        "height",
                        "source",
                        "ocr_engine",
                        "confidence",
                        "font_size",
                        "font_name",
                        "created_at");
    }

    @Test
    void parser_output_carries_payload_digest_and_provenance() {
        assertThat(columnsOf("parser_output"))
                .contains(
                        "id",
                        "org_id",
                        "source_file_id",
                        "page_id",
                        "stage",
                        "parser_name",
                        "parser_version",
                        "payload_storage_key",
                        "payload_sha256",
                        "created_at");
    }

    @Test
    void layout_tables_exist_schema_only_for_phase_3() {
        assertThat(columnsOf("layout_element"))
                .contains(
                        "id",
                        "org_id",
                        "page_id",
                        "parent_element_id",
                        "element_type",
                        "ordinal",
                        "x",
                        "y",
                        "width",
                        "height",
                        "confidence",
                        "detector",
                        "detector_version",
                        "attributes");
        assertThat(columnsOf("layout_element_span"))
                .contains("layout_element_id", "text_span_id", "org_id", "ordinal");
    }

    /**
     * V17 (full-capture P2.3). The parent self-FK dated from V4 with no index behind it — harmless
     * while trees were only ever read whole per page, a sequential scan the moment the L2 surface
     * resolves a single element's children. The L2 resolvers land in the same phase, so the index
     * is pinned here rather than trusted to survive the next schema pass.
     */
    @Test
    void layout_element_parent_resolution_is_indexed() {
        List<String> indexes =
                jdbc.queryForList(
                        """
                        SELECT indexname FROM pg_indexes
                        WHERE schemaname = 'public' AND tablename = 'layout_element'
                        """,
                        String.class);
        assertThat(indexes).contains("layout_element_org_parent_idx");
    }

    @Test
    void parsed_content_tables_force_rls_with_policies() {
        List<String> protectedTables =
                jdbc.queryForList(
                        """
                        SELECT c.relname
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public'
                          AND c.relkind = 'r'
                          AND c.relrowsecurity AND c.relforcerowsecurity
                          AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)
                        """,
                        String.class);
        assertThat(protectedTables)
                .contains(
                        "page",
                        "text_span",
                        "layout_element",
                        "layout_element_span",
                        "parser_output");
    }
}
