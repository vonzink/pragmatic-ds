package com.pragmaticds.docengine.parsing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test of the reviewer verdict-override mutators. Built through the production
 * constructor and the existing state-setting mutators ({@code applyTextVerdict} /
 * {@code applyContentSignals}) — no test-only factory is added to production code.
 */
class PageVerdictTest {

    private static Page newPage() {
        return new Page(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                0,
                new BigDecimal("612"),
                new BigDecimal("792"),
                0);
    }

    @Test
    void override_blank_clears_the_blank_flag() {
        Page p = newPage();
        p.applyTextVerdict(TextLayer.NONE, null, null); // a NONE verdict marks the page blank
        assertThat(p.isBlank()).isTrue();

        p.overrideBlank();

        assertThat(p.isBlank()).isFalse();
    }

    @Test
    void override_duplicate_clears_the_duplicate_pointer() {
        Page p = newPage();
        p.applyContentSignals("hash", UUID.randomUUID()); // points this page at a "first" page
        assertThat(p.getDuplicateOfPageId()).isNotNull();

        p.overrideDuplicate();

        assertThat(p.getDuplicateOfPageId()).isNull();
    }
}
