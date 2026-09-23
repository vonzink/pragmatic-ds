package com.pragmaticds.rag.lab.release;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manifest digest is the release's identity: {@code lab_instance_release} keys idempotent
 * bootstrap on {@code UNIQUE (brain_id, instance_slug, manifest_sha256)}, and drift is nothing
 * more than "the live manifest hashes differently". Both only work if canonicalization is a
 * function of the manifest's <em>content</em> and of nothing else — not of map iteration order,
 * not of the JVM's default charset, not of how a number happened to be spelled.
 *
 * <p>So these tests pin the serialization itself rather than merely round-tripping it: a golden
 * byte string and a golden SHA-256, plus order-independence and a strict reader that refuses the
 * two shapes a digest cannot survive — duplicate members and non-finite numbers.
 */
class LabManifestWriterTest {

    private final LabManifestWriter writer = new LabManifestWriter();

    // ---------------------------------------------------------------- determinism

    @Test
    void canonicalFormIsMemberSortedWhitespaceFreeUtf8() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("zeta", "z");
        manifest.put("alpha", 1);
        manifest.put("Beta", true);

        assertEquals("{\"Beta\":true,\"alpha\":1,\"zeta\":\"z\"}",
                new String(writer.canonicalize(manifest), StandardCharsets.UTF_8));
    }

    @Test
    void memberOrderIsByUtf8ByteSequenceNotLocaleCollation() {
        // 'Z' (0x5A) sorts before 'a' (0x61) by byte; a locale-aware collator would disagree.
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("apple", 1);
        manifest.put("Zebra", 2);
        manifest.put("éclair", 3);   // U+00E9 -> 0xC3 0xA9, sorts after ASCII

        assertEquals("{\"Zebra\":2,\"apple\":1,\"éclair\":3}",
                new String(writer.canonicalize(manifest), StandardCharsets.UTF_8));
    }

    @Test
    void insertionOrderCannotChangeTheBytesOrTheDigest() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("a", "1");
        ordered.put("b", Map.of("y", 2, "x", 1));
        ordered.put("c", List.of("first", "second"));

        Map<String, Object> shuffled = new TreeMap<>(Collections.reverseOrder());
        shuffled.put("c", new ArrayList<>(List.of("first", "second")));
        shuffled.put("b", new HashMap<>(Map.of("x", 1, "y", 2)));
        shuffled.put("a", "1");

        assertArrayEquals(writer.canonicalize(ordered), writer.canonicalize(shuffled));
        assertEquals(writer.sha256Hex(writer.canonicalize(ordered)),
                writer.sha256Hex(writer.canonicalize(shuffled)));
    }

    @Test
    void arrayOrderIsSignificantBecauseItIsPartOfTheContract() {
        String forward = writer.sha256Hex(writer.canonicalize(Map.of("m", List.of("a", "b"))));
        String reversed = writer.sha256Hex(writer.canonicalize(Map.of("m", List.of("b", "a"))));

        assertTrue(!forward.equals(reversed), "array order must change the digest");
    }

    @Test
    void aFixedManifestHashesToItsGoldenDigest() {
        // Golden vector: if canonicalization ever changes shape, every stored release digest
        // silently stops matching its own manifest. That must fail here, loudly, first.
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("canonicalization", LabManifestWriter.CANONICALIZATION_VERSION);
        manifest.put("count", 3);
        manifest.put("enabled", false);
        manifest.put("ratio", new BigDecimal("0.50"));
        manifest.put("nested", Map.of("b", List.of(1, 2), "a", "x"));
        manifest.put("absent", null);

        assertEquals("{\"absent\":null,\"canonicalization\":\"LAB-MANIFEST-C14N-1\",\"count\":3,"
                        + "\"enabled\":false,\"nested\":{\"a\":\"x\",\"b\":[1,2]},\"ratio\":0.50}",
                new String(writer.canonicalize(manifest), StandardCharsets.UTF_8));
        // Independently computed: `printf '%s' '<the string above>' | shasum -a 256`.
        assertEquals("82163fb32e9692e9b74a2383ba48ae59593b83d83b545ba7e1c6409688dec3bd",
                writer.sha256Hex(writer.canonicalize(manifest)));
    }

    @Test
    void repeatedCanonicalizationOfTheSameManifestIsByteIdentical() {
        Map<String, Object> manifest = Map.of("a", 1, "b", List.of(Map.of("d", 4, "c", 3)));

        assertArrayEquals(writer.canonicalize(manifest), writer.canonicalize(manifest));
    }

    // ---------------------------------------------------------------- number fidelity

    @Test
    void decimalsKeepTheirExactScaleAndNeverGainAnExponent() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("scaled", new BigDecimal("1.100"));
        manifest.put("large", new BigDecimal("1E+3"));
        manifest.put("longValue", 9007199254740993L);

        assertEquals("{\"large\":1000,\"longValue\":9007199254740993,\"scaled\":1.100}",
                new String(writer.canonicalize(manifest), StandardCharsets.UTF_8));
    }

    @Test
    void nonFiniteNumbersAreRejectedRatherThanSerialized() {
        for (Double value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("bad", value);
            LabManifestWriter.ManifestException failure =
                    assertThrows(LabManifestWriter.ManifestException.class,
                            () -> writer.canonicalize(manifest));
            assertEquals(LabManifestWriter.ManifestException.Code.MANIFEST_NUMBER_NOT_FINITE,
                    failure.code());
        }
    }

    @Test
    void anUnsupportedValueTypeIsRejectedRatherThanStringified() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bad", new Object());

        assertEquals(LabManifestWriter.ManifestException.Code.MANIFEST_VALUE_UNSUPPORTED,
                assertThrows(LabManifestWriter.ManifestException.class,
                        () -> writer.canonicalize(manifest)).code());
    }

    @Test
    void aNonStringMemberNameIsRejected() {
        Map<Object, Object> manifest = new LinkedHashMap<>();
        manifest.put(7, "seven");

        @SuppressWarnings("unchecked")
        Map<String, Object> unchecked = (Map<String, Object>) (Map<?, ?>) manifest;
        assertEquals(LabManifestWriter.ManifestException.Code.MANIFEST_MEMBER_NAME_NOT_STRING,
                assertThrows(LabManifestWriter.ManifestException.class,
                        () -> writer.canonicalize(unchecked)).code());
    }

    @Test
    void aNullManifestIsRejected() {
        assertEquals(LabManifestWriter.ManifestException.Code.MANIFEST_ROOT_NOT_OBJECT,
                assertThrows(LabManifestWriter.ManifestException.class,
                        () -> writer.canonicalize(null)).code());
    }

    // ---------------------------------------------------------------- strict re-read

    @Test
    void readCanonicalRoundTripsToTheSameBytes() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("s", "text");
        manifest.put("n", new BigDecimal("2.50"));
        manifest.put("b", true);
        manifest.put("z", (Object) null);
        manifest.put("arr", List.of("x", 1));
        manifest.put("obj", Map.of("k", "v"));

        byte[] canonical = writer.canonicalize(manifest);
        assertArrayEquals(canonical, writer.canonicalize(writer.readCanonical(canonical)));
        assertEquals(writer.sha256Hex(canonical),
                writer.sha256Hex(writer.canonicalize(writer.readCanonical(canonical))));
    }

    @Test
    void aDuplicateMemberIsRejectedInsteadOfSilentlyLastWinning() {
        byte[] duplicate = "{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8);

        assertEquals(LabManifestWriter.ManifestException.Code.MANIFEST_DUPLICATE_MEMBER,
                assertThrows(LabManifestWriter.ManifestException.class,
                        () -> writer.readCanonical(duplicate)).code());
    }

    @Test
    void aNestedDuplicateMemberIsRejectedToo() {
        byte[] duplicate = "{\"a\":{\"b\":1,\"b\":2}}".getBytes(StandardCharsets.UTF_8);

        assertEquals(LabManifestWriter.ManifestException.Code.MANIFEST_DUPLICATE_MEMBER,
                assertThrows(LabManifestWriter.ManifestException.class,
                        () -> writer.readCanonical(duplicate)).code());
    }

    @Test
    void aTrailingTokenIsRejected() {
        byte[] trailing = "{\"a\":1} {\"b\":2}".getBytes(StandardCharsets.UTF_8);

        assertEquals(LabManifestWriter.ManifestException.Code.MANIFEST_NOT_STRICT_JSON,
                assertThrows(LabManifestWriter.ManifestException.class,
                        () -> writer.readCanonical(trailing)).code());
    }

    @Test
    void aNonFiniteLiteralIsRejectedOnRead() {
        byte[] nonFinite = "{\"a\":NaN}".getBytes(StandardCharsets.UTF_8);

        assertEquals(LabManifestWriter.ManifestException.Code.MANIFEST_NOT_STRICT_JSON,
                assertThrows(LabManifestWriter.ManifestException.class,
                        () -> writer.readCanonical(nonFinite)).code());
    }

    @Test
    void aNonObjectRootIsRejectedOnRead() {
        byte[] array = "[1,2]".getBytes(StandardCharsets.UTF_8);

        assertEquals(LabManifestWriter.ManifestException.Code.MANIFEST_ROOT_NOT_OBJECT,
                assertThrows(LabManifestWriter.ManifestException.class,
                        () -> writer.readCanonical(array)).code());
    }

    @Test
    void readCanonicalPreservesDecimalScaleSoTheDigestSurvivesStorage() {
        byte[] canonical = "{\"ratio\":0.50}".getBytes(StandardCharsets.UTF_8);

        assertEquals(new BigDecimal("0.50"), writer.readCanonical(canonical).get("ratio"));
    }

    // ---------------------------------------------------------------- safety

    @Test
    void manifestFailuresCarryACodeAndNoManifestContent() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("borrowerNameCanary", Double.NaN);

        LabManifestWriter.ManifestException failure =
                assertThrows(LabManifestWriter.ManifestException.class,
                        () -> writer.canonicalize(manifest));

        assertEquals("MANIFEST_NUMBER_NOT_FINITE", failure.getMessage());
        assertTrue(!failure.toString().contains("borrowerNameCanary"),
                "a manifest failure must not echo manifest content");
    }

    @Test
    void digestIsLowercaseHexOfTheCanonicalBytes() {
        String digest = writer.sha256Hex(writer.canonicalize(Map.of("a", 1)));

        assertTrue(digest.matches("^[0-9a-f]{64}$"), digest);
    }
}
