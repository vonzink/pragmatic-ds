package com.pragmaticds.docengine.classification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.classification.rules.RulePackLoader;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared plumbing for the classification ITs. The worker is deliberately NOT involved:
 * classification consumes PERSISTED spans, so the bridge here loads a fixture's truth JSON and
 * inserts its words as {@code text_span} rows VERBATIM — truth text and boxes ARE the spans, by
 * the same construction that makes the coordinate contract testable at all.
 *
 * <p>{@code adapter=worker} activates the real {@code WorkerParserAdapter} so the CLASSIFYING and
 * SPLITTING branches are exercisable — neither branch makes a worker HTTP call, so no mock server
 * exists here and any accidental call fails loudly against a dead port.
 */
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "docengine.processing.adapter=worker",
            "docengine.worker.shared-secret=unused-no-call-expected",
            // This property set forks one more cached Spring context against the shared
            // Postgres container; a default-size Hikari pool on top of the existing contexts'
            // pools exhausts max_connections. Classification ITs are sequential — two is plenty.
            "spring.datasource.hikari.maximum-pool-size=2"
        })
public abstract class AbstractClassificationIT extends AbstractPostgresIT {

    protected static final ObjectMapper JSON = new ObjectMapper();

    @Autowired protected MockMvc mockMvc;
    @Autowired protected RulePackLoader rulePackLoader;

    protected JdbcTemplate jdbc;

    @BeforeEach
    void classificationSetup() {
        TenantContext.set(ORG_DEV);
        jdbc = new JdbcTemplate(dataSource);
        // Pack rows may have been inserted/deleted by a previous test in the shared container.
        rulePackLoader.invalidateAll();
    }

    @AfterEach
    void classificationTeardown() {
        TenantContext.clear();
    }

    // ── fixture truth bridge ────────────────────────────────────────────────

    /** Repo root, found by walking up from the module dir until fixtures/truth appears. */
    protected static Path fixtureTruthDir() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && current != null; i++, current = current.getParent()) {
            Path truth = current.resolve("fixtures").resolve("truth");
            if (Files.isDirectory(truth)) {
                return truth;
            }
        }
        throw new IllegalStateException("fixtures/truth not found above " + System.getProperty("user.dir"));
    }

    protected static JsonNode truth(String fixtureName) {
        try {
            return JSON.readTree(
                    Files.readString(fixtureTruthDir().resolve(fixtureName + ".json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected UUID insertPackage(String name) {
        UUID packageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_package (id, org_id, name) VALUES (?, ?, ?)",
                packageId,
                ORG_DEV,
                name);
        return packageId;
    }

    protected UUID insertSourceFile(UUID packageId, UUID orgId) {
        UUID sourceFileId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO source_file (id, org_id, package_id, ordinal, original_filename,
                    content_type, size_bytes, sha256, storage_key_original)
                VALUES (?, ?, ?, 0, 'fixture.pdf', 'application/pdf', 10, ?, 'unused')
                """,
                sourceFileId,
                orgId,
                packageId,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
        return sourceFileId;
    }

    /**
     * Inserts every truth page of a fixture as page + text_span rows.
     *
     * <p>Blank truth pages (zero words) insert as {@code is_blank} with no spans; pages whose
     * word content exactly repeats an earlier page's get {@code duplicate_of_page_id} pointing at
     * that first page — the same first-occurrence rule the PARSING stage applies.
     *
     * <p>{@code contentRotation} rides onto {@code page.rotation} so a rotated fixture is stored
     * the way the worker reports one; spans stay canonical rotation-0 either way, which is the
     * whole point of the coordinate contract.
     *
     * @return page ids in package_page_index order
     */
    protected List<UUID> insertFixturePages(UUID packageId, String fixtureName) {
        return insertFixturePages(packageId, ORG_DEV, truth(fixtureName).get("pages"));
    }

    protected List<UUID> insertFixturePages(UUID packageId, UUID orgId, JsonNode truthPages) {
        UUID sourceFileId = insertSourceFile(packageId, orgId);
        List<UUID> pageIds = new ArrayList<>();
        Map<String, UUID> firstPageByContent = new HashMap<>();
        int packagePageIndex = 0;
        for (JsonNode truthPage : truthPages) {
            JsonNode words = truthPage.get("words");
            boolean blank = words == null || words.isEmpty();

            StringBuilder contentKey = new StringBuilder();
            for (JsonNode word : words) {
                contentKey
                        .append(word.get("text").asText())
                        .append('\u001F')
                        .append(word.get("x").asDouble())
                        .append('\u001F')
                        .append(word.get("y").asDouble())
                        .append('\u001E');
            }
            UUID duplicateOf =
                    blank ? null : firstPageByContent.get(contentKey.toString());

            UUID pageId = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO page (id, org_id, source_file_id, package_id, page_index,
                        package_page_index, width_pt, height_pt, rotation, text_layer,
                        is_blank, duplicate_of_page_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    pageId,
                    orgId,
                    sourceFileId,
                    packageId,
                    packagePageIndex,
                    packagePageIndex,
                    truthPage.get("widthPt").decimalValue(),
                    truthPage.get("heightPt").decimalValue(),
                    truthPage.path("contentRotation").asInt(0),
                    blank ? "NONE" : "NATIVE",
                    blank,
                    duplicateOf);
            if (!blank) {
                firstPageByContent.putIfAbsent(contentKey.toString(), pageId);
                int ordinal = 0;
                for (JsonNode word : words) {
                    jdbc.update(
                            """
                            INSERT INTO text_span (org_id, page_id, ordinal, text, x, y, width,
                                height, source, confidence)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'NATIVE', 1.0)
                            """,
                            orgId,
                            pageId,
                            ordinal++,
                            word.get("text").asText(),
                            word.get("x").decimalValue(),
                            word.get("y").decimalValue(),
                            word.get("width").decimalValue(),
                            word.get("height").decimalValue());
                }
            }
            pageIds.add(pageId);
            packagePageIndex++;
        }
        return pageIds;
    }
}
