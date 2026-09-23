package com.pragmaticds.docengine.classification.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmaticds.docengine.classification.rules.AnchorKind;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The punctuation seam, and above all its ONE non-negotiable property: 1 char in, 1 char out.
 *
 * <p>Extraction and evidence are built on character offsets into the joined span text. A transform
 * that changed length would shift every later offset and draw a confident evidence box over the
 * wrong words — worse than the missing field the fold exists to fix, and the same class of defect
 * as the case-fold offset trap the matchers already guard against. The length assertions here are
 * therefore the primary test in this file; the mapping table is secondary.
 */
class TextFoldTest {

    // ── THE INVARIANT: length preservation ──────────────────────────────────

    @Test
    void every_entry_in_the_mapping_table_is_exactly_one_char_in_one_char_out() {
        // Over the WHOLE table, not a sample: adding a row is meant to be a one-line change, so
        // the guard has to cover rows that do not exist yet.
        assertThat(TextFold.foldings()).isNotEmpty();
        for (Map.Entry<Character, Character> entry : TextFold.foldings().entrySet()) {
            String from = String.valueOf(entry.getKey());
            String folded = TextFold.fold(from);

            assertThat(folded)
                    .as("U+%04X folds to exactly one character", (int) entry.getKey())
                    .hasSize(1)
                    .isEqualTo(String.valueOf(entry.getValue()));
            assertThat(TextFold.fold(entry.getKey())).isEqualTo(entry.getValue());
        }
    }

    @Test
    void folding_never_changes_the_length_of_any_string_in_the_basic_multilingual_plane() {
        // Every char the fold could ever meet, one at a time. A single non-1:1 row anywhere in
        // the table fails here rather than in production, on a page nobody thought to test.
        for (int codeUnit = Character.MIN_VALUE; codeUnit <= Character.MAX_VALUE; codeUnit++) {
            String one = String.valueOf((char) codeUnit);
            assertThat(TextFold.fold(one).length())
                    .as("U+%04X keeps its length", codeUnit)
                    .isEqualTo(1);
        }
    }

    @Test
    void folding_preserves_length_across_the_whole_mapping_table_at_once() {
        StringBuilder everyFoldable = new StringBuilder();
        TextFold.foldings().keySet().forEach(everyFoldable::append);
        String subject = "Employee’s W‑2 – “net” pay " + everyFoldable + " 𝔘 surrogate pair";

        assertThat(TextFold.fold(subject)).hasSameSizeAs(subject);
    }

    @Test
    void a_surrogate_pair_passes_through_as_its_two_unchanged_units() {
        // Astral characters are two chars in UTF-16. Nothing in the table is a surrogate, so they
        // survive untouched — and, crucially, are neither combined nor split.
        String astral = "𝔘𝔫𝔦𝔠𝔬𝔡𝔢";

        assertThat(TextFold.fold(astral)).isEqualTo(astral);
    }

    @Test
    void blanket_NFKC_is_rejected_precisely_because_it_is_not_length_preserving() {
        // The alternative this seam deliberately does NOT take, pinned so nobody "simplifies" the
        // fold into java.text.Normalizer later. Both of these characters appear in real typeset
        // documents; either one on a page would shift every offset after it under NFKC.
        for (String expanding : List.of("ﬁ", "½", "ﬄ", "™")) {
            assertThat(Normalizer.normalize(expanding, Normalizer.Form.NFKC).length())
                    .as("NFKC expands %s — which is why it is not used here", expanding)
                    .isGreaterThan(expanding.length());
            assertThat(TextFold.fold(expanding))
                    .as("the punctuation fold leaves it alone, at its own length")
                    .isEqualTo(expanding);
        }
    }

    // ── the table's contents ────────────────────────────────────────────────

    @Test
    void the_typographic_quotes_fold_to_their_ascii_equivalents() {
        assertThat(TextFold.fold("‘’‛ʼ")).isEqualTo("''''");
        assertThat(TextFold.fold("“”‟")).isEqualTo("\"\"\"");
        assertThat(TextFold.fold("Employee’s")).isEqualTo("Employee's");
    }

    @Test
    void the_dashes_fold_to_the_ascii_hyphen() {
        // U+2010..U+2015. The IRS sets "W‑2" with U+2011 so the form number never breaks a line.
        assertThat(TextFold.fold("‐‑‒–—―")).isEqualTo("------");
        assertThat(TextFold.fold("W‑2")).isEqualTo("W-2");
    }

    @Test
    void the_no_break_spaces_fold_to_a_plain_space() {
        assertThat(TextFold.fold("a b c")).isEqualTo("a b c");
    }

    @Test
    void characters_that_are_deliberately_not_folded_pass_through_untouched() {
        // Each exclusion is a judgement, so each is pinned. Widening the table is a decision to
        // be made deliberately, not a drift nobody notices.
        assertThat(TextFold.fold("−")).isEqualTo("−"); // U+2212 MINUS SIGN: a sign, not punctuation
        assertThat(TextFold.fold("‚„")).isEqualTo("‚„"); // low-9 quotes print as COMMAS
        assertThat(TextFold.fold("′″")).isEqualTo("′″"); // primes carry feet/inches
        assertThat(TextFold.fold("´`")).isEqualTo("´`"); // diacritics
        assertThat(TextFold.fold("​")).isEqualTo("​"); // ZWSP separates nothing visible
        assertThat(TextFold.fold("  ")).isEqualTo("  "); // alignment spaces
    }

    @Test
    void text_with_nothing_to_fold_is_returned_as_the_same_instance() {
        // Not micro-optimisation: it is the proof that an ASCII page — every existing fixture and
        // every existing golden — takes no new code path whatsoever.
        String ascii = "Employee's social security number";

        assertThat(TextFold.fold(ascii)).isSameAs(ascii);
    }

    // ── authored patterns ───────────────────────────────────────────────────

    @Test
    void a_literal_pattern_folds_so_either_spelling_matches_either_page() {
        assertThat(TextFold.pattern(AnchorKind.LITERAL, "Employee's").matcher(
                        TextFold.fold("Employee’s social security number")).find())
                .isTrue();
        assertThat(TextFold.pattern(AnchorKind.LITERAL, "Employee’s").matcher(
                        TextFold.fold("Employee's social security number")).find())
                .isTrue();
    }

    @Test
    void a_literal_pattern_stays_case_insensitive_and_stays_a_literal() {
        assertThat(TextFold.pattern(AnchorKind.LITERAL, "net pay").matcher("NET PAY").find())
                .isTrue();
        assertThat(TextFold.pattern(AnchorKind.LITERAL, "a.c").matcher("abc").find())
                .as("a literal's dot is a dot, never any-character")
                .isFalse();
    }

    @Test
    void a_regex_pattern_keeps_its_structure_and_folds_only_its_own_literals() {
        assertThat(TextFold.regexPattern("\\bSeller's Signature\\b")
                        .matcher(TextFold.fold("Seller’s Signature"))
                        .find())
                .isTrue();
        assertThat(TextFold.regexPattern("(?i)net\\s+pay").matcher("NET  PAY").find())
                .as("flags, groups and quantifiers are untouched")
                .isTrue();
        assertThat(TextFold.regexPattern("\\bYTD\\b").matcher("YTDX").find())
                .as("a boundary still bounds")
                .isFalse();
    }

    @Test
    void a_folded_character_in_a_regex_is_escaped_so_it_can_never_become_a_metacharacter() {
        // THE TRAP. Inside [...] the ASCII hyphen is the RANGE operator, so rewriting an authored
        // en dash to a bare "-" would turn [a–z]'s three literals into the whole a-z range: a
        // confident WRONG match where there had been a safe miss — strictly worse than the defect
        // being fixed. Escaped, the folded character is the same literal in every regex context.
        assertThat(TextFold.regexPattern("[a–z]").matcher("m").find())
                .as("[a<en dash>z] must not become the a-z range")
                .isFalse();
        assertThat(TextFold.regexPattern("[a–z]").matcher("-").find())
                .as("but it does match a printed hyphen now")
                .isTrue();
        assertThat(TextFold.regexPattern("[a–z]").matcher("a").find()).isTrue();
        assertThat(TextFold.regexPattern("[a–z]").matcher("z").find()).isTrue();
    }

    @Test
    void a_folded_space_in_a_regex_survives_free_spacing_mode() {
        // Under (?x) an unescaped space is IGNORED, so a bare fold of U+00A0 would delete a
        // required literal from the pattern. The escape keeps it.
        assertThat(TextFold.regexPattern("(?x) Wage and").matcher("Wage and").find()).isTrue();
    }

    // ── properties ported from the superseded LiteralPattern suite ──────────

    @Test
    void a_soft_hyphen_does_not_fold_so_a_hyphen_literal_does_not_match_through_it() {
        // U+00AD appears ALONGSIDE the characters around it, so tolerating it would need an
        // optional and would break the one-character-to-one-character rule.
        assertThat(TextFold.fold("\u00AD")).isEqualTo("\u00AD");
        assertThat(
                        TextFold.literalPattern("Treasury-Internal")
                                .matcher(TextFold.fold("Treasury\u00ADInternal"))
                                .find())
                .isFalse();
    }

    @Test
    void a_literal_with_nothing_to_fold_compiles_byte_identical_to_pattern_quote() {
        // Six of the nine W-2 captions, and most labels in the system, contain no foldable
        // character. For those the compiled SOURCE is unchanged — not merely "behaves the same".
        String literal = "1 Wages, tips, other compensation";

        assertThat(TextFold.literalPattern(literal).pattern()).isEqualTo(Pattern.quote(literal));
    }

    @Test
    void an_empty_literal_compiles_and_matches_at_the_start() {
        assertThat(TextFold.literalPattern("").matcher("anything").find()).isTrue();
    }
}
