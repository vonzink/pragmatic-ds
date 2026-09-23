package com.pragmaticds.docengine.platform.regex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guard that stops a pattern which compiles but cannot finish.
 *
 * <p>Every test here uses a SMALL explicit budget. The production default is two million reads,
 * which a pathological pattern reaches in about 13ms but a test should not be asked to spend; the
 * mechanism is identical at any size.
 */
class BoundedCharSequenceTest {

    /**
     * A shape that genuinely backtracks on this JDK.
     *
     * <p><b>Not the textbook one.</b> Measured on Java 21, {@code (a+)+$}, {@code ^(a+)+$},
     * {@code (a|aa)+$} and {@code ([a-z]+)*$} all finish in HUNDREDS of reads — the engine's loop
     * optimisations defuse every classic ReDoS example. A nested {@code .*} inside a counted
     * repetition still explodes: this pattern spends over 50,000,000 reads on a 200-character
     * subject.
     *
     * <p>Recorded because the next person to test this guard will reach for the textbook pattern,
     * watch it return instantly, and conclude the guard does nothing.
     */
    private static final Pattern CATASTROPHIC = Pattern.compile("(.*a){20}b");

    private static String subject(int length) {
        return "a".repeat(length);
    }

    @Test
    @DisplayName("a pattern that cannot finish is abandoned instead of running forever")
    void catastrophic_backtracking_is_abandoned() {
        assertThatThrownBy(
                        () ->
                                CATASTROPHIC
                                        .matcher(BoundedCharSequence.over(subject(200), 200_000))
                                        .find())
                .isInstanceOf(RegexBudgetExceededException.class);
    }

    @Test
    @DisplayName("it is abandoned QUICKLY — the point is a bound, not an eventual answer")
    void the_bound_actually_bounds_wall_clock() {
        // Unbounded, this match does not return in any time worth waiting for. The assertion is
        // deliberately loose: it proves termination, it does not benchmark.
        long start = System.nanoTime();
        assertThatThrownBy(
                        () ->
                                CATASTROPHIC
                                        .matcher(BoundedCharSequence.over(subject(200), 1_000_000))
                                        .find())
                .isInstanceOf(RegexBudgetExceededException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an honest pattern over a page of text is nowhere near the budget")
    void a_legitimate_pattern_is_unaffected() {
        // A money regex of the shape the schemas actually carry, over a subject larger than a
        // dense page of extracted text.
        String page = "Wages, tips, other compensation 104,288.80  ".repeat(200);
        Pattern money =
                Pattern.compile("(?<![\\d,.])\\$?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}(?!\\d)");

        BoundedCharSequence bounded = BoundedCharSequence.over(page);
        assertThat(money.matcher(bounded).find()).isTrue();
        // Measured: a page-scale legitimate match spends under 10k reads, ~200x below the budget.
        // This assertion IS the headroom claim the budget is calibrated on — if a legitimate
        // pattern ever approaches it, the budget was set wrong, not this test.
        assertThat(bounded.reads()).isLessThan(10_000L);
    }

    @Test
    @DisplayName("one budget covers a whole find() loop, not one budget per occurrence")
    void the_budget_is_shared_across_repeated_finds() {
        // SpanText.findOccurrence walks find() up to `occurrence` times on ONE matcher. If each
        // find reset the budget, a large occurrence index would buy unbounded total work.
        BoundedCharSequence bounded = BoundedCharSequence.over("ab".repeat(500));
        var matcher = Pattern.compile("b").matcher(bounded);

        long afterFirst = 0;
        for (int i = 0; i < 10; i++) {
            assertThat(matcher.find()).isTrue();
            if (i == 0) {
                afterFirst = bounded.reads();
            }
        }
        assertThat(bounded.reads()).isGreaterThan(afterFirst);
    }

    @Test
    @DisplayName("a subsequence inherits the budget rather than resetting it")
    void subsequence_cannot_buy_an_unbounded_subject() {
        BoundedCharSequence bounded = BoundedCharSequence.over("abcdefghij", 100);
        for (int i = 0; i < 10; i++) {
            bounded.charAt(i);
        }

        CharSequence child = bounded.subSequence(0, 5);
        assertThat(child).isInstanceOf(BoundedCharSequence.class);
        assertThat(((BoundedCharSequence) child).reads()).isEqualTo(bounded.reads());
    }

    @Test
    @DisplayName("length and toString are not charged: they are not where backtracking happens")
    void bookkeeping_reads_are_free() {
        BoundedCharSequence bounded = BoundedCharSequence.over("hello", 3);

        bounded.length();
        assertThat(bounded.toString()).isEqualTo("hello");
        assertThat(bounded.reads()).isZero();
    }

    @Test
    @DisplayName("the exception carries the budget and subject size, never the pattern or the text")
    void the_exception_leaks_neither_the_pattern_nor_the_subject() {
        String secret = "123-45-6789 Jonathan Q Borrower";
        RegexBudgetExceededException thrown = null;
        try {
            CATASTROPHIC
                    .matcher(BoundedCharSequence.over(secret + subject(200), 500))
                    .find();
        } catch (RegexBudgetExceededException e) {
            thrown = e;
        }

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).doesNotContain(secret).doesNotContain("(.*a)");
        assertThat(thrown.budget()).isEqualTo(500);
        assertThat(thrown.subjectLength()).isPositive();
    }
}
