package com.pragmaticds.docengine.extraction.extract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The normalizer registry, keyed by the schema's {@code normalizer} name. A null name is the
 * raw-text normalizer (trim + collapse internal whitespace, certainty 1). Normalization FAILURE
 * (empty result) fails the extractor RUNG — the ladder moves on. Certainty is the
 * normalizer-certainty confidence component: 1.0 = strict-format parse, lower = lenient parse.
 */
public final class Normalizers {

    private static final BigDecimal LENIENT_MONEY = new BigDecimal("0.9");
    private static final BigDecimal TWO_DIGIT_YEAR = new BigDecimal("0.8");
    private static final BigDecimal SUSPICIOUS_NAME = new BigDecimal("0.7");

    /** {@code $48,231.30} — comma-grouped with exact cents; certainty 1. */
    private static final Pattern MONEY_STRICT =
            Pattern.compile("\\$?\\d{1,3}(?:,\\d{3})*\\.\\d{2}");
    /** Plain digits, optional cents, after stripping {@code $} and commas; certainty 0.9. */
    private static final Pattern MONEY_LENIENT = Pattern.compile("\\d+(?:\\.\\d{1,2})?");

    /** Accounting notation for a negative amount: the WHOLE value wrapped in parentheses. */
    private static final Pattern ACCOUNTING_NEGATIVE = Pattern.compile("\\((.*)\\)");

    /** The characters ONE person's printed name is made of. */
    private static final String PERSON_CHARS = "[\\p{L}.'\\- ]+";

    /**
     * One person, or a JOINT pair — "Name(s) shown on return" is two people on one line. The
     * "and" spelling is letters and was always inside the clean set; the "&" spelling is clean
     * only as a single SPACED joiner between two names, so {@code J&B} stays an entity spelling
     * and scores SUSPICIOUS.
     */
    private static final Pattern NAME_CLEAN =
            Pattern.compile(PERSON_CHARS + "(?: & " + PERSON_CHARS + ")?");

    /**
     * The residue an incomplete JOINT capture leaves: a bare conjunction dangling at either end
     * ({@code Jordan Q. Fixture and}). No person is named that, so the value is reported and
     * scored down rather than trusted — the personName analogue of {@link
     * #DEGENERATE_ENTITY_NAME}. Never a rejection: a reviewer sees what the form says either way.
     */
    private static final Pattern DANGLING_CONJUNCTION =
            Pattern.compile("(?i)(?:.* )?(?:and|&)|(?i)(?:and|&)(?: .*)?");

    /**
     * An ENTITY name's clean set. Wider than a PERSON's by exactly what a legal entity prints
     * and a person does not: DIGITS ({@code 1ST CHOICE PROPERTIES LLC}), an AMPERSAND anywhere
     * ({@code SMITH & JONES}, where a person admits one only as a spaced joint-name joiner) and
     * a COMMA ({@code ACME, INC.}). Deliberately ASCII in its punctuation, like NAME_CLEAN:
     * this class sits outside the TextFold seam, so a name the form set with a typographic
     * apostrophe scores SUSPICIOUS here. That is the known, separately tracked gap, and it is
     * the same one personName already has — degrading a WHOLE name is not the failure this
     * normalizer exists to stop.
     */
    private static final Pattern ENTITY_NAME_CLEAN = Pattern.compile("[\\p{L}\\p{N}&.,'\\- ]+");

    /**
     * The shape a truncated entity name leaves behind: one token, three characters or fewer —
     * {@code O} from {@code O'BRIEN FAMILY TRUST}, {@code LP} from {@code Summit Ridge Partners
     * LP}. No entity in this position on a return is named that, so the value is reported and
     * scored down rather than trusted.
     */
    private static final Pattern DEGENERATE_ENTITY_NAME = Pattern.compile("\\S{1,3}");

    /** US policy: month-first, always. */
    private record DateFormat(DateTimeFormatter formatter, BigDecimal certainty) {}

    private static final List<DateFormat> DATE_FORMATS =
            List.of(
                    new DateFormat(strict("M/d/uuuu"), BigDecimal.ONE),
                    new DateFormat(
                            DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT),
                            BigDecimal.ONE),
                    new DateFormat(strict("MMMM d, uuuu"), BigDecimal.ONE),
                    new DateFormat(strict("MMM d, uuuu"), BigDecimal.ONE),
                    new DateFormat(twoDigitYearPivot2000(), TWO_DIGIT_YEAR));

    private static final Map<String, String> PAY_FREQUENCIES =
            Map.of(
                    "weekly", "WEEKLY",
                    "biweekly", "BIWEEKLY",
                    "semimonthly", "SEMIMONTHLY",
                    "monthly", "MONTHLY");

    private Normalizers() {}

    /**
     * The one parsing implementation on the read and write paths alike. PUBLIC because
     * {@code extraction.web}'s {@link com.pragmaticds.docengine.extraction.web} correction path must type
     * a reviewer's string EXACTLY as the machine types its own capture — a second implementation
     * drifted once already (accounting negatives, SMART-vs-STRICT dates) and produced a null
     * typed value for a correction the machine path would have parsed.
     *
     * @throws IllegalArgumentException for a normalizer name the registry does not know
     */
    public static Optional<NormalizedValue> normalize(String normalizerName, String raw) {
        if (normalizerName == null) {
            return Optional.of(new NormalizedValue(collapse(raw), null, null, BigDecimal.ONE));
        }
        return switch (normalizerName) {
            case "money" -> money(raw);
            case "date" -> date(raw);
            case "payFrequency" -> payFrequency(raw);
            case "personName" -> personName(raw);
            case "entityName" -> entityName(raw);
            // The name is schema configuration, never document content — safe to throw.
            default -> throw new IllegalArgumentException("unknown normalizer: " + normalizerName);
        };
    }

    /**
     * Whether {@link #normalize} knows this normalizer name — the schema-authoring admission test.
     *
     * <p>It asks the registry ITSELF rather than comparing against a copied name list, because a
     * copied list is a second registry: adding {@code entityName} to the switch above and
     * forgetting the copy would admit a schema whose every field dies at extraction time with an
     * unknown-normalizer throw, one document at a time, long after the author left. A null name is
     * known — it means "raw text", which is what most fields declare.
     */
    public static boolean isKnownNormalizer(String normalizerName) {
        if (normalizerName == null) {
            return true;
        }
        try {
            // A probe value, never document content. Every branch either parses it or returns an
            // empty Optional; only an UNKNOWN NAME throws, which is the one thing being asked.
            normalize(normalizerName, "0");
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    /**
     * Money, with the SIGN taken seriously. Tax and accounting forms write a negative amount in
     * parentheses — Schedule E line 21 is income OR (loss) per rental property — and a leading
     * minus is the other convention. The sign is stripped first and re-applied last, so
     * strictness is judged on the amount exactly as before: parentheses change the sign, never
     * the certainty. Everything unsigned behaves byte-identically to the pre-Spec-5a normalizer.
     */
    private static Optional<NormalizedValue> money(String raw) {
        String trimmed = raw.trim();
        boolean negative = false;
        Matcher accounting = ACCOUNTING_NEGATIVE.matcher(trimmed);
        if (accounting.matches()) {
            negative = true;
            trimmed = accounting.group(1).trim();
        } else if (trimmed.startsWith("-")) {
            negative = true;
            trimmed = trimmed.substring(1).trim();
        } else if (trimmed.startsWith("+")) {
            // An explicit plus is a printed sign, not a different number: an online print-out
            // writes its month-to-date deposits as "+$2,180.40" beside "-$674.20" withdrawals.
            // Persisting the value as rendered (V39's rule) means the rung captures the sign,
            // and a captured sign that failed normalization would fail a correct rung.
            trimmed = trimmed.substring(1).trim();
        }
        String cleaned = trimmed.replace("$", "").replace(",", "");
        if (MONEY_STRICT.matcher(trimmed).matches()) {
            return Optional.of(
                    new NormalizedValue(null, signed(cleaned, negative), null, BigDecimal.ONE));
        }
        if (MONEY_LENIENT.matcher(cleaned).matches()) {
            return Optional.of(
                    new NormalizedValue(null, signed(cleaned, negative), null, LENIENT_MONEY));
        }
        return Optional.empty();
    }

    private static BigDecimal signed(String cleaned, boolean negative) {
        BigDecimal amount = new BigDecimal(cleaned);
        return negative ? amount.negate() : amount;
    }

    private static Optional<NormalizedValue> date(String raw) {
        String trimmed = raw.trim();
        for (DateFormat format : DATE_FORMATS) {
            try {
                LocalDate parsed = LocalDate.parse(trimmed, format.formatter());
                return Optional.of(new NormalizedValue(null, null, parsed, format.certainty()));
            } catch (DateTimeParseException e) {
                // try the next format
            }
        }
        return Optional.empty();
    }

    private static Optional<NormalizedValue> payFrequency(String raw) {
        String key = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return Optional.ofNullable(PAY_FREQUENCIES.get(key))
                .map(canonical -> new NormalizedValue(canonical, null, null, BigDecimal.ONE));
    }

    private static Optional<NormalizedValue> personName(String raw) {
        String collapsed = collapse(raw);
        boolean clean =
                NAME_CLEAN.matcher(collapsed).matches()
                        && !DANGLING_CONJUNCTION.matcher(collapsed).matches();
        return Optional.of(
                new NormalizedValue(
                        collapsed, null, null, clean ? BigDecimal.ONE : SUSPICIOUS_NAME));
    }

    /**
     * A LEGAL ENTITY's name — Schedule E Parts II, III and IV — scored the way personName scores a
     * person's. The point is that it is SCORED AT ALL: these columns shipped with a null
     * normalizer, which answers certainty 1.0 to any string it is handed, so a value pattern that
     * could match only part of a printed name persisted that FRAGMENT fully confident. A fragment
     * names a DIFFERENT legal entity; it is a wrong value, not a missing one.
     *
     * <p>Two things cost certainty, and neither can reject a value the pattern captured — a
     * reviewer sees what the form says either way:
     *
     * <ul>
     *   <li>a character outside the entity-name set, the personName contract;
     *   <li>a degenerate one-token name of three characters or fewer, which is the residue a
     *       truncation leaves and never how an entity in this position is actually named.
     * </ul>
     *
     * <p>Empty is the one refusal: a name that collapsed to nothing traces to no printed text, so
     * the rung fails and the ladder moves on rather than persisting a blank at full confidence.
     */
    private static Optional<NormalizedValue> entityName(String raw) {
        String collapsed = collapse(raw);
        if (collapsed.isEmpty()) {
            return Optional.empty();
        }
        boolean clean =
                ENTITY_NAME_CLEAN.matcher(collapsed).matches()
                        && !DEGENERATE_ENTITY_NAME.matcher(collapsed).matches();
        return Optional.of(
                new NormalizedValue(
                        collapsed, null, null, clean ? BigDecimal.ONE : SUSPICIOUS_NAME));
    }

    private static String collapse(String raw) {
        return raw.trim().replaceAll("\\s+", " ");
    }

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.US)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    /** {@code MM/dd/yy}: two-digit years pivot to the 2000s. */
    private static DateTimeFormatter twoDigitYearPivot2000() {
        return new DateTimeFormatterBuilder()
                .appendPattern("M/d/")
                .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
                .toFormatter(Locale.US)
                .withResolverStyle(ResolverStyle.STRICT);
    }
}
