package com.pragmaticds.docengine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.ingestion.AbstractIngestionIT;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * Soft delete tombstones a package and hides its content behind a 404 on every read path, while the
 * rows and blobs survive for the retention window (the purge is a separate job). The
 * read-exclusion half is the fix for the Phase-7c latent bug: only the pages LIST checked
 * {@code deleted_at}; the package GET, the page render, and the file content did not, so a
 * tombstoned package still served borrower bytes.
 */
class PackageSoftDeleteIT extends AbstractIngestionIT {

    @Test
    void delete_tombstones_the_package_and_every_read_path_then_404s() throws Exception {
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "soft-delete-me");

        // Before: original/page reads and all three immutable-result routes serve the live package.
        mockMvc.perform(get("/v1/packages/{id}", seed.packageId())).andExpect(status().isOk());
        mockMvc.perform(get("/v1/packages/{id}/pages", seed.packageId()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/pages/{id}/render", seed.pageId())).andExpect(status().isOk());
        mockMvc.perform(get("/v1/files/{id}/content", seed.sourceFileId()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/packages/{id}/engine-result", seed.packageId()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/packages/{id}/engine-results", seed.packageId()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/packages/{id}/engine-results/{revision}", seed.packageId(), 1))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/v1/packages/{id}", seed.packageId()))
                .andExpect(status().isNoContent());

        // The tombstone columns are set; the rows and blobs still exist (purge is separate).
        assertThat(deletedAt(seed)).isNotNull();
        assertThat(purgeAfter(seed)).isNotNull();
        assertThat(blobStorage.exists(seed.originalBlobKey())).isTrue();
        assertThat(engineResultCount(seed)).isEqualTo(1);
        assertThat(blobStorage.exists(seed.engineResultBlobKey())).isTrue();

        // After: every read path now reads as absent — a tombstoned package serves nothing.
        mockMvc.perform(get("/v1/packages/{id}", seed.packageId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/packages/{id}/pages", seed.packageId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/pages/{id}/render", seed.pageId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/files/{id}/content", seed.sourceFileId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/packages/{id}/engine-result", seed.packageId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/packages/{id}/engine-results", seed.packageId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/packages/{id}/engine-results/{revision}", seed.packageId(), 1))
                .andExpect(status().isNotFound());

        // Tombstoning is deliberately not physical deletion. Both immutable descriptor and blob
        // remain throughout the retention window even though every API route is opaque 404.
        assertThat(engineResultCount(seed)).isEqualTo(1);
        assertThat(blobStorage.exists(seed.engineResultBlobKey())).isTrue();
    }

    @Test
    void delete_writes_a_pii_free_package_deleted_audit_event() throws Exception {
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "audit-delete");

        mockMvc.perform(delete("/v1/packages/{id}", seed.packageId()))
                .andExpect(status().isNoContent());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT actor_type FROM audit_event WHERE action = 'PACKAGE_DELETED'"
                                        + " AND subject_id = ?",
                                String.class,
                                seed.packageId()))
                .isEqualTo("USER");
        // Metadata is a count, never a name/filename: default config window is 30 days.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT (metadata->>'purgeAfterDays')::int FROM audit_event WHERE"
                                        + " action = 'PACKAGE_DELETED' AND subject_id = ?",
                                Integer.class,
                                seed.packageId()))
                .isEqualTo(30);
    }

    @Test
    void a_retention_policy_row_drives_the_purge_window() throws Exception {
        // ORG_OTHER carries a 7-day default policy; its deletes must honour it, not the config 30.
        jdbc.update(
                "INSERT INTO retention_policy (org_id, document_category, retain_days,"
                        + " purge_after_days) VALUES (?, NULL, 10, 7) ON CONFLICT DO NOTHING",
                ORG_OTHER);
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_OTHER, "policy-delete");

        mockMvc.perform(
                        delete("/v1/packages/{id}", seed.packageId()).header("X-Dev-Org", ORG_OTHER))
                .andExpect(status().isNoContent());

        OffsetDateTime purgeAfter = purgeAfter(seed);
        long days = ChronoUnit.DAYS.between(OffsetDateTime.now(), purgeAfter);
        assertThat(days).isBetween(6L, 7L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT (metadata->>'purgeAfterDays')::int FROM audit_event WHERE"
                                        + " action = 'PACKAGE_DELETED' AND subject_id = ?",
                                Integer.class,
                                seed.packageId()))
                .isEqualTo(7);
    }

    @Test
    void a_second_delete_of_an_already_tombstoned_package_is_404() throws Exception {
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "double-delete");

        mockMvc.perform(delete("/v1/packages/{id}", seed.packageId()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/v1/packages/{id}", seed.packageId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void another_org_cannot_delete_this_orgs_package() throws Exception {
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "cross-tenant-delete");

        mockMvc.perform(
                        delete("/v1/packages/{id}", seed.packageId()).header("X-Dev-Org", ORG_OTHER))
                .andExpect(status().isNotFound());

        // Untouched: no tombstone was written by the foreign org's attempt.
        assertThat(deletedAt(seed)).isNull();
    }

    @Test
    void delete_is_reserved_to_admin() throws Exception {
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "rbac-delete");

        mockMvc.perform(
                        delete("/v1/packages/{id}", seed.packageId()).header("X-Dev-Role", "REVIEWER"))
                .andExpect(status().isForbidden());
        assertThat(deletedAt(seed)).isNull();
    }

    private OffsetDateTime deletedAt(LifecycleFixtures.Seed seed) {
        return jdbc.queryForObject(
                "SELECT deleted_at FROM document_package WHERE id = ?",
                OffsetDateTime.class,
                seed.packageId());
    }

    private OffsetDateTime purgeAfter(LifecycleFixtures.Seed seed) {
        return jdbc.queryForObject(
                "SELECT purge_after FROM document_package WHERE id = ?",
                OffsetDateTime.class,
                seed.packageId());
    }

    private long engineResultCount(LifecycleFixtures.Seed seed) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM engine_result WHERE package_id = ? AND org_id = ?",
                Long.class,
                seed.packageId(),
                seed.orgId());
    }
}
