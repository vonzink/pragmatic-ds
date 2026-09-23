package com.pragmaticds.docengine.classification.match;

import com.pragmaticds.docengine.classification.rules.AnchorKind;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * THE punctuation seam: the one place where an AUTHORED string (a rule-pack anchor, an extraction
 * schema label, a value pattern) is reconciled with the punctuation a real document actually
 * prints. Classification's {@link AnchorMatcher} and extraction's {@code SpanText} both go through
 * it, so the two subsystems cannot drift apart about what "the same text" means.
 *
 * <h2>Why it exists</h2>
 *
 * Real IRS and lender forms set {@code Employee’s} with U+2019 RIGHT SINGLE QUOTATION MARK. Every
 * anchor and label in this repository is authored with the ASCII apostrophe U+0027, so those
 * captions never matched: a real filled W-2 classified at 1.00 and extracted 8 of 10 while the
 * synthetic fixture — drawn from the same belief as the schema — extracted 10 of 10 and every test
 * passed with both wrong. Fixing it in the DATA would mean editing 11 packs and 11 schemas and
 * repeating the exercise for the next curly quote, en dash or non-breaking space. It is fixed HERE
 * instead, once.
 *
 * <h2>The invariant: 1 char in, 1 char out</h2>
 *
 * Extraction and evidence are built on character offsets into the joined span text, so the
 * transform MUST preserve length. {@link #fold(String)} maps each {@code char} independently and
 * never inserts or deletes one, so {@code fold(s).length() == s.length()} holds by CONSTRUCTION,
 * not by inspection of the table — surrogate pairs pass through as their two unchanged units.
 *
 * <p>Full {@code NFKC}/{@code NFKD} normalization is deliberately NOT used: it expands ligatures
 * ({@code ﬁ} → {@code fi}), fractions ({@code ½} → {@code 1⁄2}) and much more, so a single such
 * character on a page would shift every later offset and hand the reviewer a confident evidence
 * box over the wrong words — strictly worse than the missing field this seam exists to fix, and
 * the same trap as the case-fold offset defect the two matchers already guard against.
 *
 * <h2>What is folded, and what deliberately is not</h2>
 *
 * Only characters whose sole reading on a printed form is "the ASCII character an author would
 * type". Every entry is exactly 1:1 (see {@link #foldings()}):
 *
 * <ul>
 *   <li>the typographic quotes (U+2018/2019/201B/02BC → {@code '}, U+201C/201D/201F → {@code "})
 *       — the defect itself;
 *   <li>the dashes U+2010..U+2015 → {@code -}: the IRS sets {@code W‑2} with U+2011 NON-BREAKING
 *       HYPHEN precisely so a form number never breaks a line, and the W2 pack's highest
 *       signal-per-character anchor is literally {@code W-2};
 *   <li>the no-break spaces U+00A0 and U+202F → {@code  }: PDF producers emit them inside captions
 *       that must not break, and Java's {@code \s} does not match either, so an authored space and
 *       an authored {@code \s} both failed on the same text.
 * </ul>
 *
 * <p>NOT folded, on purpose: U+201A/U+201E (the low-9 quotes print as COMMAS — folding them would
 * let a comma satisfy an apostrophe anchor), U+2032/U+2033 (primes carry feet/inches and
 * minutes/seconds), U+00B4/U+0060 (diacritics), U+2212 MINUS SIGN (a sign, not punctuation:
 * folding it widens VALUE patterns into arithmetic the normalizer — which reads the ORIGINAL
 * text — would not agree with), the fixed-width spaces U+2007..U+200A (numeric-alignment
 * typography, not a word separator), and U+200B ZERO WIDTH SPACE (it separates nothing a reader
 * sees; mapping it to a space would insert a separator the page does not have). Each is one line
 * away if a real document ever demands it — which is the entire point of having one seam.
 *
 * <h2>Where the fold is applied</h2>
 *
 * To the DOCUMENT text only for MATCHING, and to the AUTHORED pattern. What is reported —
 * {@code displayedText}, the persisted raw value, the evidence box — always comes from the
 * document's own characters, so a value and its evidence never disagree with the page.
 */
public final class TextFold {

    /**
     * The mapping table. STRICTLY 1:1 — {@code TextFoldTest} asserts it over every entry, and
     * {@link #fold(String)} could not honour a 1:many entry even if one were added here.
     */
    private static final Map<Character, Character> FOLDINGS = foldingTable();

    /** Dense lookup derived from {@link #FOLDINGS}: index {@code c - LOW}, {@code 0} = no fold. */
    private static final char LOW;

    private static final char[] TABLE;

    static {
        char low = Character.MAX_VALUE;
        char high = Character.MIN_VALUE;
        for (char key : FOLDINGS.keySet()) {
            low = (char) Math.min(low, key);
            high = (char) Math.max(high, key);
        }
        LOW = low;
        TABLE = new char[high - low + 1];
        FOLDINGS.forEach((from, to) -> TABLE[from - LOW] = to);
    }

    private TextFold() {}

    private static Map<Character, Character> foldingTable() {
        Map<Character, Character> table = new LinkedHashMap<>();
        // ── the apostrophes and quotes: THE defect ──────────────────────────
        table.put('‘', '\''); // LEFT SINGLE QUOTATION MARK
        table.put('’', '\''); // RIGHT SINGLE QUOTATION MARK — "Employee’s"
        table.put('‛', '\''); // SINGLE HIGH-REVERSED-9 QUOTATION MARK
        table.put('ʼ', '\''); // MODIFIER LETTER APOSTROPHE — some OCR engines emit it
        table.put('“', '"'); // LEFT DOUBLE QUOTATION MARK
        table.put('”', '"'); // RIGHT DOUBLE QUOTATION MARK
        table.put('‟', '"'); // DOUBLE HIGH-REVERSED-9 QUOTATION MARK
        // ── the dashes: "W‑2", "Jan – Mar", hyphenated captions ─────────────
        table.put('‐', '-'); // HYPHEN
        table.put('‑', '-'); // NON-BREAKING HYPHEN
        table.put('‒', '-'); // FIGURE DASH
        table.put('–', '-'); // EN DASH
        table.put('—', '-'); // EM DASH
        table.put('―', '-'); // HORIZONTAL BAR
        // ── the no-break spaces: captions a producer refused to break ───────
        table.put(' ', ' '); // NO-BREAK SPACE
        table.put(' ', ' '); // NARROW NO-BREAK SPACE
        return Map.copyOf(table);
    }

    /** The table itself, so a test can assert 1:1 over every entry rather than a sample. */
    public static Map<Character, Character> foldings() {
        return FOLDINGS;
    }

    /** One character's fold, or the character unchanged. Never more, never fewer, than one. */
    public static char fold(char c) {
        if (c < LOW || c - LOW >= TABLE.length) {
            return c;
        }
        char folded = TABLE[c - LOW];
        return folded == 0 ? c : folded;
    }

    /**
     * The document text a pattern is matched AGAINST, with printed punctuation folded to the
     * characters an author types. LENGTH-PRESERVING by construction: every {@code char} is
     * replaced in place, so an offset into the result indexes the same character of the input and
     * every evidence box stays where the page put it.
     *
     * @return {@code text} itself when it contains nothing to fold — the overwhelmingly common
     *     case, and the proof that unfolded pages take no new code path at all
     */
    public static String fold(String text) {
        int first = -1;
        for (int i = 0; i < text.length(); i++) {
            if (fold(text.charAt(i)) != text.charAt(i)) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return text;
        }
        char[] folded = text.toCharArray();
        for (int i = first; i < folded.length; i++) {
            folded[i] = fold(folded[i]);
        }
        return new String(folded);
    }

    /**
     * The compiled matcher for an AUTHORED anchor or label, ready to run against {@link
     * #fold(String)}ed document text. The one place either subsystem turns a pattern into a
     * {@link Pattern}, so literal-vs-regex semantics cannot diverge between them.
     */
    public static Pattern pattern(AnchorKind kind, String authored) {
        return switch (kind) {
            case LITERAL -> literalPattern(authored);
            case REGEX -> regexPattern(authored);
        };
    }

    /**
     * A LITERAL anchor: case-insensitive containment of the FOLDED phrase. Quoted, so every
     * character in it is a literal and the full fold is safe to apply.
     *
     * <p>Case-insensitivity is a regex FLAG over the original text, never a lowered copy — case
     * folding can change string LENGTH (Turkish dotted capital İ lowers to two chars) and would
     * shift every later offset (Phase 4 review finding). The punctuation fold is the length-
     * preserving transform that lowering is not.
     */
    public static Pattern literalPattern(String authored) {
        return Pattern.compile(
                Pattern.quote(fold(authored)), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * A REGEX anchor, structurally exactly as authored: flags, groups, quantifiers and boundaries
     * are untouched, and ONLY the pattern's own foldable literals are rewritten.
     *
     * <p>Each rewrite is emitted BACKSLASH-ESCAPED, and that is not decoration. Inside {@code
     * [...]} the ASCII hyphen is the RANGE operator, so folding an authored en dash to a bare
     * {@code -} would silently turn {@code [a–z]}'s three literals into the whole {@code a-z}
     * range, and a space is ignored under {@code (?x)} COMMENTS mode. An escaped {@code \-},
     * {@code \ }, {@code \'} or {@code \"} is the same literal in EVERY regex context, so folding
     * can widen what the pattern matches but can never change its structure. Turning a safe miss
     * into a confident wrong match is the one outcome worse than the defect being fixed.
     *
     * <p>Known limit, stated rather than discovered: a foldable character inside {@code \Q…\E} or
     * an {@code (?x)} comment is rewritten too, and there it means the escape sequence rather than
     * the literal. The result is a NON-match, never a wrong match — the safe direction — and no
     * pattern in any shipped pack or schema is written that way.
     */
    public static Pattern regexPattern(String authored) {
        return Pattern.compile(foldAuthoredRegex(authored));
    }

    private static String foldAuthoredRegex(String authored) {
        StringBuilder folded = new StringBuilder(authored.length());
        for (int i = 0; i < authored.length(); i++) {
            char c = authored.charAt(i);
            char to = fold(c);
            if (to == c) {
                folded.append(c);
            } else {
                folded.append('\\').append(to);
            }
        }
        return folded.toString();
    }
}
