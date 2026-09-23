package com.pragmaticds.docengine.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.docengine.ingestion.AbstractIngestionIT;
import com.pragmaticds.docengine.platform.storage.SignedObjectRef;
import com.pragmaticds.docengine.platform.storage.SignedUrlService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * App-issued, app-verified download URLs — the local-storage stand-in for a native presign. Issuing
 * is an ordinary org-guarded read that audits; the download endpoint is deliberately UNAUTHENTICATED
 * (that is the point of a shareable link) but is authorized by the signature and gated on the org
 * baked into the token, so it can neither be forged nor pointed at another tenant's bytes.
 */
class SignedDownloadIT extends AbstractIngestionIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private SignedUrlService signedUrls;

    private String issuedUrl(MvcResult result) throws Exception {
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        return body.get("url").asText();
    }

    @Test
    void a_valid_file_token_serves_the_original_bytes() throws Exception {
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "signed-file");

        MvcResult issued =
                mockMvc.perform(get("/v1/files/{id}/signed-url", seed.sourceFileId()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.url").exists())
                        .andExpect(jsonPath("$.expiresAt").exists())
                        .andReturn();

        byte[] served =
                mockMvc.perform(get(issuedUrl(issued)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();

        assertThat(new String(served, StandardCharsets.UTF_8)).isEqualTo("signed-file");
    }

    @Test
    void a_valid_page_token_serves_the_render() throws Exception {
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "signed-page");

        MvcResult issued =
                mockMvc.perform(get("/v1/pages/{id}/signed-url", seed.pageId()))
                        .andExpect(status().isOk())
                        .andReturn();

        mockMvc.perform(get(issuedUrl(issued)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("image/png"));
    }

    @Test
    void issuing_a_signed_url_writes_a_signed_url_issued_audit_event() throws Exception {
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "signed-audit");

        mockMvc.perform(get("/v1/files/{id}/signed-url", seed.sourceFileId()))
                .andExpect(status().isOk());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT actor_type FROM audit_event WHERE action ="
                                        + " 'SIGNED_URL_ISSUED' AND subject_id = ?",
                                String.class,
                                seed.sourceFileId()))
                .isEqualTo("USER");
        // PII-free: an object-type code, never a filename or content.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT metadata->>'objectType' FROM audit_event WHERE action ="
                                        + " 'SIGNED_URL_ISSUED' AND subject_id = ?",
                                String.class,
                                seed.sourceFileId()))
                .isEqualTo(SignedObjectRef.TYPE_FILE_CONTENT);
    }

    @Test
    void an_expired_token_is_rejected() throws Exception {
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "expired");
        // Already past its expiry at mint time.
        String token =
                signedUrls.sign(
                        new SignedObjectRef(
                                SignedObjectRef.TYPE_FILE_CONTENT, seed.sourceFileId(), ORG_DEV),
                        Duration.ofSeconds(-30));

        mockMvc.perform(get("/v1/download").param("token", token))
                .andExpect(status().isNotFound());
    }

    @Test
    void a_tampered_token_is_rejected() throws Exception {
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "tampered");
        String token =
                signedUrls.sign(
                        new SignedObjectRef(
                                SignedObjectRef.TYPE_FILE_CONTENT, seed.sourceFileId(), ORG_DEV),
                        Duration.ofMinutes(5));
        // Flip the FIRST char of the MAC segment, not the last char of the token. The 32-byte HMAC
        // base64url-without-padding ends on a char whose low 2 bits are unused, so flipping it can
        // decode to the SAME MAC — a no-op "tamper" that is genuinely valid and correctly served,
        // which made this test flakily fail. The first MAC char always maps to a real byte.
        int dot = token.indexOf('.');
        char first = token.charAt(dot + 1);
        String tampered =
                token.substring(0, dot + 1) + (first == 'A' ? 'B' : 'A') + token.substring(dot + 2);

        mockMvc.perform(get("/v1/download").param("token", tampered))
                .andExpect(status().isNotFound());
    }

    @Test
    void a_token_bound_to_another_org_cannot_reach_this_orgs_object() throws Exception {
        // A real file in ORG_DEV. A validly-signed token that NAMES org OTHER for that same id must
        // not serve it: the download binds the token's org and loads the object under it, so the
        // object is simply absent for the wrong org.
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "cross-org-download");
        String crossOrgToken =
                signedUrls.sign(
                        new SignedObjectRef(
                                SignedObjectRef.TYPE_FILE_CONTENT, seed.sourceFileId(), ORG_OTHER),
                        Duration.ofMinutes(5));

        mockMvc.perform(get("/v1/download").param("token", crossOrgToken))
                .andExpect(status().isNotFound());

        // And a foreign org cannot even ISSUE a URL for this org's file (the issuing load is
        // org-scoped, so the id is absent to org OTHER).
        mockMvc.perform(
                        get("/v1/files/{id}/signed-url", seed.sourceFileId())
                                .header("X-Dev-Org", ORG_OTHER))
                .andExpect(status().isNotFound());
    }

    @Test
    void a_signed_url_for_a_soft_deleted_package_no_longer_serves() throws Exception {
        LifecycleFixtures.Seed seed =
                LifecycleFixtures.seedFullPackage(jdbc, blobStorage, ORG_DEV, "signed-then-deleted");
        String token =
                signedUrls.sign(
                        new SignedObjectRef(
                                SignedObjectRef.TYPE_FILE_CONTENT, seed.sourceFileId(), ORG_DEV),
                        Duration.ofMinutes(5));
        // Tombstone the package after the token was minted.
        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                "/v1/packages/{id}", seed.packageId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/v1/download").param("token", token))
                .andExpect(status().isNotFound());
    }
}
