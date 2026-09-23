package com.pragmaticds.docengine.parsing.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.parsing.domain.SpanSource;
import org.junit.jupiter.api.Test;

/**
 * A tripwire, not a behaviour test.
 *
 * <p>{@code TextSpanRepository.findPageSpansAfter} expresses the source half of the total order as
 * a DISJUNCTION — "the cursor's own source, plus the whole OCR block when the cursor sits in
 * NATIVE" — rather than as an inequality on an {@code @Enumerated(STRING)} attribute, which in the
 * database would silently mean "alphabetical". That formulation is exhaustive for exactly two
 * sources and quietly incomplete for three: a new third source would be reachable only when the
 * cursor happened to be parked inside it, so a cursor walk would stop early and a consumer would
 * conclude the page had fewer spans than it does. Missing-over-wrong does not help here, because
 * the omission is invisible.
 *
 * <p>If this test fails, the fix is in the query, not here.
 */
class TextSpanOrderContractTest {

    @Test
    void the_cursor_query_is_written_for_exactly_two_span_sources() {
        assertThat(SpanSource.values())
                .as(
                        "adding a span source means rewriting the cursor arm in"
                                + " TextSpanRepository.findPageSpansAfter — it enumerates the blocks"
                                + " that follow NATIVE, it does not compare them")
                .containsExactly(SpanSource.NATIVE, SpanSource.OCR);
    }
}
