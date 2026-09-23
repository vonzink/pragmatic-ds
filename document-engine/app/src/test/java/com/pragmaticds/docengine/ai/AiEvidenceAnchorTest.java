package com.pragmaticds.docengine.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.parsing.domain.SpanSource;
import com.pragmaticds.docengine.parsing.domain.TextSpan;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiEvidenceAnchorTest {

    private static final UUID PAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    void uniquely_matches_printed_text_inside_one_engine_span() {
        TextSpan line = span(0, "Ending balance  $1,234.56");

        AiEvidenceAnchor.Match match =
                new AiEvidenceAnchor().match("$1,234.56", List.of(line));

        assertThat(match.status()).isEqualTo(AiEvidenceAnchor.Status.MATCHED);
        assertThat(match.spans()).containsExactly(line);
    }

    @Test
    void matches_printed_text_split_across_adjacent_spans_with_whitespace_folding() {
        TextSpan first = span(0, "Freeport");
        TextSpan second = span(1, "Community");
        TextSpan third = span(2, "Bank");

        AiEvidenceAnchor.Match match =
                new AiEvidenceAnchor()
                        .match("freeport   community bank", List.of(first, second, third));

        assertThat(match.status()).isEqualTo(AiEvidenceAnchor.Status.MATCHED);
        assertThat(match.spans()).containsExactly(first, second, third);
    }

    @Test
    void refuses_to_choose_between_duplicate_printed_values() {
        TextSpan first = span(0, "$42.50");
        TextSpan second = span(1, "Other text");
        TextSpan duplicate = span(2, "$42.50");

        AiEvidenceAnchor.Match match =
                new AiEvidenceAnchor().match("$42.50", List.of(first, second, duplicate));

        assertThat(match.status()).isEqualTo(AiEvidenceAnchor.Status.AMBIGUOUS);
        assertThat(match.spans()).isEmpty();
        // Retained for AiRowAnchorResolver — the matcher cannot choose, but a caller that knows
        // the row can. spans() staying empty is what keeps every status-keyed gate unchanged.
        assertThat(match.candidates()).containsExactly(List.of(first), List.of(duplicate));
    }

    @Test
    void a_unique_match_reports_itself_as_uniquely_resolved() {
        AiEvidenceAnchor.Match match =
                new AiEvidenceAnchor().match("$1,234.56", List.of(span(0, "$1,234.56")));

        assertThat(match.resolution()).isEqualTo(AiEvidenceAnchor.Resolution.UNIQUE);
        assertThat(match.candidates()).isEmpty();
    }

    @Test
    void an_unmatched_value_carries_no_candidates_to_resolve() {
        AiEvidenceAnchor.Match match =
                new AiEvidenceAnchor().match("$9.99", List.of(span(0, "$8.88")));

        assertThat(match.status()).isEqualTo(AiEvidenceAnchor.Status.UNANCHORED);
        assertThat(match.candidates()).isEmpty();
    }

    @Test
    void missing_or_unmatched_printed_text_is_unanchored() {
        AiEvidenceAnchor anchor = new AiEvidenceAnchor();

        assertThat(anchor.match(null, List.of(span(0, "Anything"))).status())
                .isEqualTo(AiEvidenceAnchor.Status.UNANCHORED);
        assertThat(anchor.match("$9.99", List.of(span(0, "$8.88"))).status())
                .isEqualTo(AiEvidenceAnchor.Status.UNANCHORED);
    }

    private static TextSpan span(int ordinal, String text) {
        return new TextSpan(
                PAGE_ID,
                ordinal,
                text,
                BigDecimal.valueOf(10 + ordinal * 20L),
                BigDecimal.TEN,
                BigDecimal.valueOf(18),
                BigDecimal.valueOf(8),
                SpanSource.NATIVE,
                null,
                BigDecimal.ONE,
                BigDecimal.TEN,
                "Synthetic");
    }
}
