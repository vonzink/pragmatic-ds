package com.pragmaticds.rag.lab.findings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingDigestTest {

    @Test
    void sameInputsProduceSameDigest() {
        assertEquals(FindingDigest.of("a", "b"), FindingDigest.of("a", "b"));
    }

    @Test
    void digestIsPrefixedLowercaseHex() {
        String digest = FindingDigest.of("a");
        assertTrue(digest.startsWith("sha256:"), digest);
        assertTrue(digest.substring(7).matches("[0-9a-f]{64}"), digest);
    }

    @Test
    void partBoundariesAreNotAmbiguous() {
        assertNotEquals(FindingDigest.of("ab", "c"), FindingDigest.of("a", "bc"));
    }

    @Test
    void nullPartIsNotTheStringNull() {
        assertNotEquals(FindingDigest.of((String) null), FindingDigest.of("null"));
    }

    @Test
    void orderMatters() {
        assertNotEquals(FindingDigest.of("a", "b"), FindingDigest.of("b", "a"));
    }
}
