package com.pragmaticds.rag.lab.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.analyze.InstanceOutputSchemaRegistry.OutputSchemaException;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The output-schema allowlist.
 *
 * <p>A release names a schema by id and digest. These pin that the id must be one this build
 * shipped, that the digest must match the bytes actually on the classpath, and that neither a path
 * nor a URL is ever a usable id — a release cannot make the server read something of its choosing.
 */
class InstanceOutputSchemaRegistryTest {

    private static final String ALLOWLISTED = "analyzer-envelope-v2";
    private static final String RESOURCE = "ai/analyzer-envelope-v2.schema.json";

    private final InstanceOutputSchemaRegistry registry =
            new ClasspathInstanceOutputSchemaRegistry(new ObjectMapper());

    @Test
    void anAllowlistedSchemaResolvesAtItsRealDigest() throws Exception {
        // Computed from the shipped resource rather than hardcoded, so editing the schema does not
        // silently invalidate this test — it keeps asserting the mechanism, not one frozen value.
        String digest = actualDigest();

        assertNotNull(registry.require(ALLOWLISTED, digest));
        assertSame(registry.require(ALLOWLISTED, digest), registry.require(ALLOWLISTED, digest),
                "a resolved schema is cached rather than reparsed per run");
    }

    /**
     * What the authoring surface is offered is the digest {@code require} will accept.
     *
     * <p>The digest is over the resource's bytes, so it is not something a client can compute or
     * safely remember. Offering one this registry would then reject would hand the author a
     * release that cannot run, which is why the published digest is checked by resolving it.
     */
    @Test
    void everyAllowlistedSchemaIsPublishedAtTheDigestRequireWillAccept() throws Exception {
        List<InstanceOutputSchemaRegistry.SchemaDescriptor> published = registry.list();

        assertEquals(List.of(ALLOWLISTED), published.stream()
                .map(InstanceOutputSchemaRegistry.SchemaDescriptor::schemaId).toList());
        assertEquals(actualDigest(), published.getFirst().sha256());
        for (InstanceOutputSchemaRegistry.SchemaDescriptor schema : published) {
            assertNotNull(registry.require(schema.schemaId(), schema.sha256()));
        }
        assertEquals(published, registry.list(), "the offered order must not move");
    }

    @Test
    void anIdOutsideTheAllowlistIsRefusedHoweverItIsSpelled() throws Exception {
        String digest = actualDigest();

        for (String id : new String[] {
                "unknown-schema",
                RESOURCE,
                "classpath:" + RESOURCE,
                "/etc/passwd",
                "file:///etc/passwd",
                "https://example.invalid/schema.json"}) {
            assertEquals(OutputSchemaException.Code.OUTPUT_SCHEMA_NOT_ALLOWLISTED,
                    assertThrows(OutputSchemaException.class,
                            () -> registry.require(id, digest)).code(),
                    "a release may name an id, never a location: " + id);
        }
    }

    @Test
    void theResolvedTextIsTheVerifiedBytesThemselves() throws Exception {
        // The prompt shows a model this text, so it must be what the digest was checked against
        // rather than a re-serialization of the parsed form.
        String digest = actualDigest();

        try (InputStream in = InstanceOutputSchemaRegistryTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            assertNotNull(in);
            assertEquals(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    registry.requireText(ALLOWLISTED, digest));
        }
    }

    @Test
    void textResolutionEnforcesTheSameAllowlistAndDigest() {
        assertEquals(OutputSchemaException.Code.OUTPUT_SCHEMA_NOT_ALLOWLISTED,
                assertThrows(OutputSchemaException.class,
                        () -> registry.requireText("unknown-schema", "f".repeat(64))).code());
        assertEquals(OutputSchemaException.Code.OUTPUT_SCHEMA_DIGEST_MISMATCH,
                assertThrows(OutputSchemaException.class,
                        () -> registry.requireText(ALLOWLISTED, "f".repeat(64))).code());
    }

    @Test
    void aDigestThatDoesNotMatchTheShippedBytesIsRefused() {
        assertEquals(OutputSchemaException.Code.OUTPUT_SCHEMA_DIGEST_MISMATCH,
                assertThrows(OutputSchemaException.class,
                        () -> registry.require(ALLOWLISTED, "f".repeat(64))).code());
    }

    @Test
    void aMalformedOrAbsentDigestIsNeverTreatedAsAWildcard() {
        for (String digest : new String[] {null, "", "NOT-A-DIGEST", "A".repeat(64), "abc"}) {
            assertEquals(OutputSchemaException.Code.OUTPUT_SCHEMA_DIGEST_MISMATCH,
                    assertThrows(OutputSchemaException.class,
                            () -> registry.require(ALLOWLISTED, digest)).code());
        }
    }

    private static String actualDigest() throws Exception {
        try (InputStream in = InstanceOutputSchemaRegistryTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            assertNotNull(in, "the allowlisted schema must ship on the classpath");
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
    }
}
