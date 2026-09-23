package com.pragmaticds.docengine.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.docengine.classification.match.TextFold;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The value patterns {@code schedule_e@1.0.0} repeats — the MONEY pattern (twenty-one fields),
 * the ENTITY-NAME pattern (three) and the TAXPAYER-NAME pattern (one field, two rungs) — read
 * back FROM THE SEEDED SCHEMA and exercised against the spellings real returns actually print.
 *
 * <p>Why the seeded row and not the migration text: §5 authors each pattern ONCE and stamps it into
 * the JSON with {@code replace()}, so the file has one copy and the database has twenty. Asserting
 * on the file would prove only that {@code replace} was typed; asserting on
 * {@code extraction_schema.definition} proves what actually ships, and the identity assertions
 * below are what would catch a hand-edit that reintroduced a per-field copy.
 *
 * <p>Why patterns and not persisted fields: {@link ScheduleEExtractionIT} proves the end-to-end
 * claim on the fixture, but a fixture prints each spelling once. A pattern is the thing that must
 * hold for spellings no fixture contains, and the sign of a number is not a formatting detail —
 * a rental LOSS booked as INCOME is an underwriting error with a correct-looking evidence box.
 */
class ScheduleESeedPatternIT extends AbstractExtractionIT {

    /** MONEY fields of {@code schedule_e@1.0.0}: every one must carry the SAME value pattern. */
    private static final int MONEY_FIELDS = 21;

    /** The three entity-name row groups: Part II, Part III, Part IV. */
    private static final List<String> ENTITY_NAME_FIELDS =
            List.of("partnershipName", "estateOrTrustName", "remicName");

    private JsonNode seededScheduleE() {
        return readJson(
                jdbc.queryForObject(
                        "SELECT definition::text FROM extraction_schema"
                                + " WHERE org_id IS NULL AND document_type_code = 'SCHEDULE_E'"
                                + " AND version = '1.0.0'",
                        String.class));
    }

    private JsonNode readJson(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable seeded schedule_e definition", e);
        }
    }

    /** field name → the value pattern of its (single) extractor, in schema order. */
    private Map<String, String> valuePatterns(String dataType) {
        Map<String, String> patterns = new LinkedHashMap<>();
        for (JsonNode field : seededScheduleE().get("fields")) {
            if (!field.get("dataType").asText().equals(dataType)) {
                continue;
            }
            List<String> distinct = new ArrayList<>();
            for (JsonNode extractor : field.get("extractors")) {
                String pattern = extractor.get("value").get("pattern").asText();
                if (!distinct.contains(pattern)) {
                    distinct.add(pattern);
                }
            }
            assertThat(distinct)
                    .as("%s declares one value pattern across its rungs", field.get("name").asText())
                    .hasSize(1);
            patterns.put(field.get("name").asText(), distinct.get(0));
        }
        return patterns;
    }

    /**
     * The engine's own seam: a value pattern is compiled through {@link TextFold} and run against
     * FOLDED text, so a test that compiled the raw string would be testing a different regex from
     * the one that ships.
     */
    private static String matchOf(String pattern, String subject) {
        Pattern compiled = TextFold.regexPattern(pattern);
        Matcher matcher = compiled.matcher(TextFold.fold(subject));
        return matcher.find() ? subject.substring(matcher.start(), matcher.end()) : null;
    }

    @Test
    void the_money_pattern_is_ONE_pattern_repeated_never_seventeen_that_drifted() {
        // §5 stamps it in with replace(), so this can only fail if someone hand-edited a field
        // back to its own copy — which is exactly the drift the stamping exists to prevent, and
        // exactly how a corrected sign rule would end up applying to sixteen fields out of
        // seventeen.
        Map<String, String> money = valuePatterns("MONEY");
        assertThat(money).as("MONEY fields in schedule_e@1.0.0").hasSize(MONEY_FIELDS);
        assertThat(money.values().stream().distinct().toList())
                .as("all %d MONEY fields share ONE byte-identical value pattern", MONEY_FIELDS)
                .hasSize(1);
    }

    @Test
    void the_money_pattern_captures_the_SIGN_in_every_spelling_a_return_prints() {
        String money = valuePatterns("MONEY").values().iterator().next();

        // Accounting parentheses, in the four ways a form lays them out. The parenthesis pair is
        // preprinted and the amount typed between it, so the text layer routinely yields the
        // paren and the digits as separate runs — which SpanText joins with a space. Before this
        // fix only the tightly-typeset first row matched as a whole; every other row fell through
        // to the unsigned alternative, which matched the DIGITS ALONE and dropped the sign.
        assertThat(matchOf(money, "(18,470)")).isEqualTo("(18,470)");
        assertThat(matchOf(money, "( 18,470 )")).isEqualTo("( 18,470 )");
        assertThat(matchOf(money, "(18,470 )")).isEqualTo("(18,470 )");
        assertThat(matchOf(money, "( 18,470)")).isEqualTo("( 18,470)");
        // The dollar sign lives INSIDE the parentheses, which is where accounting notation puts
        // it and where the unsigned alternative could never reach it.
        assertThat(matchOf(money, "($18,470)")).isEqualTo("($18,470)");
        assertThat(matchOf(money, "( $18,470 )")).isEqualTo("( $18,470 )");

        // The other negative convention: a leading minus, adjacent or spaced.
        assertThat(matchOf(money, "-18,470")).isEqualTo("-18,470");
        assertThat(matchOf(money, "- 18,470")).isEqualTo("- 18,470");
        assertThat(matchOf(money, "-$18,470")).isEqualTo("-$18,470");

        // A TRAILING minus is the statement convention. It is captured DELIBERATELY even though
        // the money normalizer does not read it: normalization then fails, the rung fails, and
        // the occurrence goes MISSING. Refusing to match it instead would leave the amount in
        // place and shift `occurrence` onto the NEXT number on the line — a confident value from
        // the wrong column, which is the one outcome worse than missing.
        assertThat(matchOf(money, "1,234.56-")).isEqualTo("1,234.56-");

        // And nothing above weakened the positives or the line-number guard: Schedule E is dense
        // with bare line numbers sitting on the very lines the rungs read.
        assertThat(matchOf(money, "44,400")).isEqualTo("44,400");
        assertThat(matchOf(money, "$1,234.56")).isEqualTo("$1,234.56");
        assertThat(matchOf(money, "1234.56")).isEqualTo("1234.56");
        assertThat(matchOf(money, "20")).as("a bare line number is not an amount").isNull();
        assertThat(matchOf(money, "1040")).as("a form number is not an amount").isNull();
        assertThat(matchOf(money, "Rents received 44,400")).isEqualTo("44,400");
        assertThat(matchOf(money, "Subtract line 20 from line 3 ( 18,470 )"))
                .as("the label's own digits are not amounts, and the loss keeps its parens")
                .isEqualTo("( 18,470 )");
    }

    @Test
    void no_column_is_anchored_on_its_LETTER_and_every_caption_is_the_form_s_own_text() {
        // The defect this guards is not "a caption was wrong" — it is the CORRECTION that a
        // wrong caption invites. The schema shipped with (g)/(h)/(j) where the form prints
        // (h)/(i)/(k), so three money columns matched nothing; the cheap repair is to anchor
        // on the letter, or to shorten "(j) Nonpassive income" to "(j)". On the real form (j)
        // is the SECTION 179 DEDUCTION, so that repair reports a deduction as income at full
        // confidence. A missing field is recoverable; this is not.
        //
        // Every columnHeader is therefore asserted to carry WORDS, not just a letter, and the
        // Part II band is pinned to the IRS's exact strings.
        Map<String, String> headers = new LinkedHashMap<>();
        for (JsonNode field : seededScheduleE().get("fields")) {
            for (JsonNode extractor : field.get("extractors")) {
                if (extractor.hasNonNull("columnHeader")) {
                    headers.put(
                            field.get("name").asText(),
                            extractor.get("columnHeader").get("pattern").asText());
                }
            }
        }

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            assertThat(entry.getValue())
                    .as("%s is anchored on a caption, never on a bare column letter", entry.getKey())
                    .doesNotMatch("\\(\\w\\)\\s*");
            assertThat(entry.getValue().replaceAll("\\(\\w\\)", "").trim())
                    .as("%s names the column in words", entry.getKey())
                    .isNotEmpty();
        }

        // The five Part II money captions, exactly as the IRS prints them.
        assertThat(headers)
                .containsEntry("partnershipPassiveLossAllowed", "(g) Passive loss allowed")
                .containsEntry("partnershipPassiveIncome", "(h) Passive income")
                .containsEntry("partnershipNonpassiveLossAllowed", "(i) Nonpassive loss allowed")
                .containsEntry("partnershipSection179Expense", "(j) Section 179 expense")
                .containsEntry("partnershipNonpassiveIncome", "(k) Nonpassive income")
                // Part III's band, read whole rather than at half its width.
                .containsEntry(
                        "estateOrTrustPassiveDeductionOrLoss",
                        "(c) Passive deduction or loss allowed")
                .containsEntry("estateOrTrustPassiveIncome", "(d) Passive income")
                .containsEntry("estateOrTrustDeductionOrLoss", "(e) Deduction or loss")
                .containsEntry("estateOrTrustOtherIncome", "(f) Other income from")
                // The captions the form WRAPS: anchored on their first printed line and no
                // further, because a literal is matched against ONE assembled line.
                .containsEntry("partnershipEin", "(d) Employer")
                .containsEntry("remicIncome", "(e) Income from")
                .containsEntry("remicExcessInclusion", "(c) Excess inclusion from");
    }

    @Test
    void a_line_number_a_form_number_or_a_tax_year_is_never_an_amount() {
        // Read as a standing constraint on the amount alternation, not as a restatement of
        // the sign test: the totals rungs are ANCHOR_LABEL with scope LINE_RIGHT and NO
        // column band, and every total line on this form prints its own line number in the
        // gutter between the label and the amount. Widening the alternation to a bare \\d+
        // — tempting, because a real return prints whole dollars — makes occurrence 0 of
        //
        //   32 Total partnership and S corporation income or (loss). Combine lines 30 and 31 . 32
        //
        // the LINE NUMBER, reported as the partnership total.
        String money = valuePatterns("MONEY").values().iterator().next();

        for (String bare :
                List.of("20", "28", "32", "39", "41", "179", "1040", "4562", "8582", "6198",
                        "2025", "950")) {
            assertThat(matchOf(money, bare))
                    .as("\"%s\" is a line, form or year number — not an amount", bare)
                    .isNull();
        }
        // In context, on the shapes those numbers actually appear in.
        assertThat(matchOf(money, "Combine lines 30 and 31 . . . . . 32 50,465"))
                .as("the line number in the gutter is skipped and the TOTAL is read")
                .isEqualTo("50,465");
        assertThat(matchOf(money, "(j) Section 179 expense deduction from Form 4562"))
                .as("a caption's own numerals are not amounts")
                .isNull();
        assertThat(matchOf(money, "Schedule E (Form 1040) 2025 Attachment Sequence No. 13"))
                .as("the continuation header carries no amount")
                .isNull();
        // And the accepted cost, stated as a test so it cannot be "fixed" by accident: a
        // sub-$1,000 amount is read only when the form prints its cents.
        assertThat(matchOf(money, "950.00")).isEqualTo("950.00");
    }

    @Test
    void the_entity_name_pattern_is_ONE_pattern_repeated_and_reads_a_WHOLE_name() {
        Map<String, String> names = valuePatterns("STRING");
        List<String> entityPatterns =
                ENTITY_NAME_FIELDS.stream().map(names::get).distinct().toList();
        assertThat(entityPatterns)
                .as("Parts II, III and IV share ONE byte-identical entity-name pattern")
                .hasSize(1);
        String entity = entityPatterns.get(0);

        // The five shapes the fixture prints, each of which an all-caps-only pattern reduced to
        // a FRAGMENT: a different legal entity, at certainty 1.0, with an evidence box drawn
        // around part of a word.
        assertThat(matchOf(entity, "SUMMIT RIDGE PARTNERS LP")).isEqualTo("SUMMIT RIDGE PARTNERS LP");
        assertThat(matchOf(entity, "1ST CHOICE PROPERTIES LLC")).isEqualTo("1ST CHOICE PROPERTIES LLC");
        assertThat(matchOf(entity, "O'BRIEN FAMILY TRUST")).isEqualTo("O'BRIEN FAMILY TRUST");
        assertThat(matchOf(entity, "Meridian Fixture Estate")).isEqualTo("Meridian Fixture Estate");
        assertThat(matchOf(entity, "McALLISTER HOLDINGS LLC")).isEqualTo("McALLISTER HOLDINGS LLC");
        // The punctuation seam: a name set with a typographic apostrophe matches an authored
        // ASCII one, and the DISPLAYED value keeps the document's own character.
        assertThat(matchOf(entity, "O’BRIEN FAMILY TRUST")).isEqualTo("O’BRIEN FAMILY TRUST");
        // Ampersands and legal punctuation are ordinary in this column.
        assertThat(matchOf(entity, "SMITH & JONES HOLDINGS, L.P."))
                .isEqualTo("SMITH & JONES HOLDINGS, L.P.");

        // A whole-token boundary on BOTH sides: the pattern cannot start or stop in the middle of
        // a word, so it can never hand back the tail of a name it could not fully read.
        assertThat(matchOf(entity, "47-3920184")).as("an EIN is not a name").isNull();
    }

    @Test
    void the_taxpayer_name_pattern_reads_a_JOINT_return_name_WHOLE() {
        // The caption is "Name(s) shown on return" — the form's own text says the value may
        // be TWO people. A one-person pattern run against a joint return captures the first
        // spouse and STOPS: the second borrower silently vanishes from the name field at
        // 0.9 confidence. Wrong-not-missing — nothing looks wrong to a reviewer.
        String name = valuePatterns("STRING").get("taxpayerName");

        // A single filer, exactly as before.
        assertThat(matchOf(name, "Jordan Q. Fixture")).isEqualTo("Jordan Q. Fixture");
        assertThat(matchOf(name, "Jordan Fixture")).isEqualTo("Jordan Fixture");

        // The joint spellings returns actually print: a conjunction and a SECOND whole name.
        assertThat(matchOf(name, "Jordan Q. Fixture and Casey R. Fixture"))
                .isEqualTo("Jordan Q. Fixture and Casey R. Fixture");
        assertThat(matchOf(name, "Jordan Q. Fixture & Casey R. Fixture"))
                .isEqualTo("Jordan Q. Fixture & Casey R. Fixture");
        // The shared-surname spelling: only the second spouse carries the family name.
        assertThat(matchOf(name, "Jordan and Casey Fixture"))
                .isEqualTo("Jordan and Casey Fixture");

        // And the widened pattern still REFUSES to run past the name. The SSN caption sits
        // on the same printed line region of the real form; a pattern that swallowed
        // neighbouring boilerplate would be a NEW wrong value, not a fix.
        assertThat(matchOf(name,
                        "Jordan Q. Fixture and Casey R. Fixture Your social security number"))
                .isEqualTo("Jordan Q. Fixture and Casey R. Fixture");
        assertThat(matchOf(name, "Jordan Q. Fixture Your social security number"))
                .isEqualTo("Jordan Q. Fixture");
    }

    @Test
    void the_taxpayer_name_pattern_reads_a_SPELLED_middle_name_WHOLE() {
        // The real return this fix came from prints the second filer's middle name SPELLED
        // OUT — "First Middle Last", not "First M. Last". A middle slot that admits only an
        // initial reads the spelled word as the SURNAME and stops: the true surname is
        // dropped and the capture is "… and First Middle" at 0.9, with nothing textually
        // wrong for a reviewer to see. A middle token is an initial OR a spelled word, up
        // to two of them, for BOTH persons — person 1 carries the same latent gap.
        String name = valuePatterns("STRING").get("taxpayerName");

        // FIRST, the real document's shape — joint, initial x spelled — so the mutation
        // check (drop the spelled-word alternative from the seed) fails HERE, with the
        // exact truncated capture the defect persisted: "Jordan Q. Fixture and Casey
        // Reese", surname gone, nothing visibly wrong.
        assertThat(matchOf(name, "Jordan Q. Fixture and Casey Reese Fixture"))
                .isEqualTo("Jordan Q. Fixture and Casey Reese Fixture");

        // A single filer, in every middle shape: spelled, spelled twice, initial+spelled.
        assertThat(matchOf(name, "Jordan Quinn Fixture")).isEqualTo("Jordan Quinn Fixture");
        assertThat(matchOf(name, "Jordan Quinn Reese Fixture"))
                .isEqualTo("Jordan Quinn Reese Fixture");
        assertThat(matchOf(name, "Jordan Q. Reese Fixture")).isEqualTo("Jordan Q. Reese Fixture");

        // The remaining joint middle-shape combinations, each one filled form field away
        // from the shape above.
        assertThat(matchOf(name, "Jordan Quinn Fixture and Casey R. Fixture"))
                .isEqualTo("Jordan Quinn Fixture and Casey R. Fixture");
        assertThat(matchOf(name, "Jordan Fixture and Casey Reese Fixture"))
                .isEqualTo("Jordan Fixture and Casey Reese Fixture");
        assertThat(matchOf(name, "Jordan Quinn Fixture & Casey Reese Fixture"))
                .isEqualTo("Jordan Quinn Fixture & Casey Reese Fixture");
    }

    @Test
    void the_taxpayer_name_pattern_still_stops_before_the_SSN_caption_after_a_SPELLED_middle() {
        // "Your" is shaped exactly like a name word, so admitting spelled middles hands the
        // pattern two more slots that could swallow it. What bounds the capture is the word
        // AFTER it: a caption's first word drags its own lowercase text behind it ("social
        // security number"), while a true final surname is followed by the caption's
        // capital, the SSN digits, or nothing at all. The pattern refuses to END where a
        // non-conjunction lowercase word follows, so a greedy attempt to consume "Your"
        // backtracks off it — the caption survives, whatever the middle shape.
        String name = valuePatterns("STRING").get("taxpayerName");

        assertThat(matchOf(name, "Jordan Quinn Fixture Your social security number"))
                .isEqualTo("Jordan Quinn Fixture");
        assertThat(matchOf(name,
                        "Jordan Q. Fixture and Casey Reese Fixture Your social security number"))
                .isEqualTo("Jordan Q. Fixture and Casey Reese Fixture");
        // The LINE_RIGHT fallback rung's own trap, the caption ALONE: "Your" now ends
        // before its own lowercase tail, so the rung reads NOTHING rather than handing
        // back the caption's first word as the taxpayer.
        assertThat(matchOf(name, "Your social security number"))
                .as("the SSN caption's own first word is not a taxpayer name")
                .isNull();
    }

    @Test
    void the_taxpayer_name_field_routes_through_personName_so_a_capture_is_SCORED() {
        // The partial-capture cost lives in the NORMALIZER: personName scores a dangling
        // conjunction — the residue an incomplete joint capture leaves — at 0.7. A null
        // normalizer would answer 1.0 to anything, so the routing is part of the contract.
        for (JsonNode field : seededScheduleE().get("fields")) {
            if (field.get("name").asText().equals("taxpayerName")) {
                assertThat(field.hasNonNull("normalizer") ? field.get("normalizer").asText() : null)
                        .as("taxpayerName is scored, not waved through")
                        .isEqualTo("personName");
            }
        }
    }

    @Test
    void the_entity_name_fields_declare_a_normalizer_so_a_capture_is_SCORED() {
        // A null normalizer answers certainty 1.0 to anything it is handed — no validation, no
        // cost, nothing to distinguish a whole name from a fragment of one. personName has always
        // scored what it is given; the entity columns went out without that.
        for (JsonNode field : seededScheduleE().get("fields")) {
            if (ENTITY_NAME_FIELDS.contains(field.get("name").asText())) {
                assertThat(field.hasNonNull("normalizer") ? field.get("normalizer").asText() : null)
                        .as("%s is scored, not waved through", field.get("name").asText())
                        .isEqualTo("entityName");
            }
        }
    }
}
