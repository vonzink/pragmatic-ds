package com.pragmaticds.docengine.platform.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The masking format, exhaustively. Every case proves the invariant that binds all of them: no more
 * than the last four characters of a sensitive value may survive.
 */
class MaskingServiceTest {

    @Test
    void a_nine_digit_ssn_masks_to_the_ssn_shape_revealing_only_the_last_four() {
        assertThat(MaskingService.mask("123456789")).isEqualTo("•••-••-6789");
    }

    @Test
    void a_punctuated_ssn_normalises_to_the_same_ssn_shape() {
        assertThat(MaskingService.mask("123-45-6789")).isEqualTo("•••-••-6789");
    }

    @Test
    void a_sixteen_digit_account_reveals_only_the_last_four() {
        String masked = MaskingService.mask("4111111111111234");

        assertThat(masked).isEqualTo("••••1234");
        assertThat(masked).doesNotContain("411111111111");
    }

    @Test
    void a_four_character_value_is_fully_masked_because_the_last_four_would_be_everything() {
        assertThat(MaskingService.mask("1234")).isEqualTo("••••");
    }

    @Test
    void an_empty_value_masks_to_empty() {
        assertThat(MaskingService.mask("")).isEqualTo("");
    }

    @Test
    void a_null_value_stays_null() {
        assertThat(MaskingService.mask(null)).isNull();
    }

    @Test
    void an_alphanumeric_secret_never_reveals_more_than_the_last_four() {
        String masked = MaskingService.mask("SECRETVALUE9876");

        assertThat(masked).endsWith("9876");
        assertThat(masked).doesNotContain("SECRETVALUE");
        assertThat(masked).isEqualTo("••••9876");
    }
}
