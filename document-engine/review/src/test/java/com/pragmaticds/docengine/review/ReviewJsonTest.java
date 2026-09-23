package com.pragmaticds.docengine.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewJsonTest {

    @Test
    void a_correction_envelope_carries_the_page_beside_the_value() {
        String json = ReviewJson.correction("4,760.69", 1);

        assertThat(ReviewJson.read(json, "value")).isEqualTo("4,760.69");
        assertThat(ReviewJson.readInt(json, "pageIndex")).isEqualTo(1);
    }

    @Test
    void a_correction_without_a_page_omits_the_key_and_reads_back_null() {
        String json = ReviewJson.correction("4,760.69", null);

        assertThat(json).doesNotContain("pageIndex");
        assertThat(ReviewJson.read(json, "value")).isEqualTo("4,760.69");
        assertThat(ReviewJson.readInt(json, "pageIndex")).isNull();
        // The pre-existing single-key envelope reads the same way — nothing old changes shape.
        assertThat(ReviewJson.readInt(ReviewJson.object("value", "x"), "pageIndex")).isNull();
        assertThat(ReviewJson.readInt(null, "pageIndex")).isNull();
    }
}
