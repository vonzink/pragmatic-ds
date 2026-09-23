package com.pragmaticds.docengine.throughput;

import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Measures seconds per page from the org's last {@value #SAMPLE_JOBS} completed jobs within
 * {@value #WINDOW_DAYS} days. Native SQL because {@code processing_stage} (orchestration) and
 * {@code page} (parsing) live in modules that do not see each other; the {@code org_id}
 * predicate is explicit on every table because native statements bypass {@code @TenantId}
 * and the test role bypasses RLS.
 */
@Service
public class ProcessingThroughputService {

    static final int SAMPLE_JOBS = 50;
    static final int WINDOW_DAYS = 7;

    private static final String SQL =
            """
            WITH recent AS (
                SELECT j.id, j.package_id
                  FROM processing_job j
                 WHERE j.org_id = ?
                   AND j.status IN ('HUMAN_REVIEW_REQUIRED', 'COMPLETED')
                   AND j.finished_at > now() - make_interval(days => ?)
                 ORDER BY j.finished_at DESC
                 LIMIT ?
            ),
            stage_ms AS (
                SELECT s.stage, COALESCE(SUM(s.duration_ms), 0) AS ms
                  FROM processing_stage s
                  JOIN recent r ON r.id = s.job_id
                 WHERE s.org_id = ? AND s.status = 'SUCCEEDED'
                 GROUP BY s.stage
            ),
            -- package_id is not unique in `recent`: resume/regroup can create more than one job
            -- for the same package. Join pages to the DISTINCT package ids, or a package's pages
            -- are counted once per job on it and the per-page rates come out deflated.
            pages AS (
                SELECT COUNT(*) FILTER (WHERE p.text_layer IN ('SCANNED', 'MIXED')) AS ocr_pages,
                       COUNT(*) FILTER (WHERE p.text_layer <> 'NONE') AS all_pages
                  FROM page p
                  JOIN (SELECT DISTINCT package_id FROM recent) rp ON rp.package_id = p.package_id
                 WHERE p.org_id = ?
            )
            SELECT (SELECT COUNT(*) FROM recent) AS jobs,
                   COALESCE((SELECT ms FROM stage_ms WHERE stage = 'OCR_PROCESSING'), 0) AS ocr_ms,
                   COALESCE((SELECT SUM(ms) FROM stage_ms WHERE stage IN ('RENDERING', 'TEXT_EXTRACTION', 'PARSING')), 0) AS other_ms,
                   COALESCE((SELECT SUM(ms) FROM stage_ms WHERE stage IN ('VALIDATING', 'CLASSIFYING', 'SPLITTING', 'EXTRACTING', 'FINALIZING')), 0) AS fixed_ms,
                   (SELECT ocr_pages FROM pages) AS ocr_pages,
                   (SELECT all_pages FROM pages) AS all_pages
            """;

    private final JdbcTemplate jdbc;

    public ProcessingThroughputService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public Throughput current() {
        UUID orgId = TenantContext.require();
        return jdbc.queryForObject(
                SQL,
                (rs, n) -> {
                    int jobs = rs.getInt("jobs");
                    if (jobs == 0) {
                        return Throughput.SEEDED;
                    }
                    long ocrPages = rs.getLong("ocr_pages");
                    long allPages = rs.getLong("all_pages");
                    double ocr = ocrPages == 0
                            ? Throughput.SEEDED.ocrSecondsPerPage()
                            : rs.getLong("ocr_ms") / 1000.0 / ocrPages;
                    double other = allPages == 0
                            ? Throughput.SEEDED.otherSecondsPerPage()
                            : rs.getLong("other_ms") / 1000.0 / allPages;
                    double fixed = rs.getLong("fixed_ms") / 1000.0 / jobs;
                    return new Throughput(ocr, other, fixed, jobs, true);
                },
                orgId, WINDOW_DAYS, SAMPLE_JOBS, orgId, orgId);
    }
}
