package com.pragmaticds.rag.lab.model;

import com.pragmaticds.rag.lab.run.domain.EstimateQuality;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Bounds how many tokens a piece of text will become.
 *
 * <p><b>No provider tokenizer ships in this build,</b> so any text measured here comes back as a
 * band labelled {@link EstimateQuality#ESTIMATED_RANGE}. A catalog entry declaring
 * {@code EXACT_PROVIDER} does not change that: the label follows what this process can actually
 * compute, not what configuration claims. When a real tokenizer is added it will narrow the band
 * to a point and the label will follow it — which is why callers already read a range.
 *
 * <p>Counts that are exact by construction — a configured retrieval allowance, an output ceiling —
 * are exact and say so, via {@link TokenRange#exactly(long)}. Adding one of those to measured text
 * still yields a range, because a sum is only as good as its worst part.
 *
 * <p><b>The band.</b> Tokenizers for the models in use average roughly four characters per token
 * on English prose. The bounds here are deliberately wider than that average in both directions:
 *
 * <ul>
 *   <li>{@value #MAX_CHARS_PER_TOKEN} characters per token as the <em>lower</em> bound on count.
 *       Long words and repeated whitespace compress better than prose, so a text can genuinely
 *       need fewer tokens than the average suggests.
 *   <li>{@value #MIN_CHARS_PER_TOKEN} characters per token as the <em>upper</em> bound. JSON
 *       schemas, code, tables of figures, and non-Latin scripts all tokenize far denser than
 *       prose; two characters per token is the level at which dense structured text stops
 *       surprising us. This is the bound a budget reserves against, so it errs high on purpose.
 * </ul>
 *
 * <p>Erring high costs headroom; erring low overspends a budget that already approved the run. The
 * asymmetry is intentional.
 */
@Component
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstanceTokenEstimator {

    /** Densest tokenization we bound for: JSON, code, tables, non-Latin scripts. */
    public static final double MIN_CHARS_PER_TOKEN = 2.0;

    /** Sparsest tokenization we bound for: long words and runs of whitespace. */
    public static final double MAX_CHARS_PER_TOKEN = 5.0;

    /** An inclusive token band. {@code min == max} exactly when the count is exact. */
    public record TokenRange(long min, long max, EstimateQuality quality) {

        public TokenRange {
            if (min < 0 || max < min) {
                throw new IllegalArgumentException("a token range must be ordered and non-negative");
            }
            Objects.requireNonNull(quality, "quality");
        }

        public TokenRange plus(TokenRange other) {
            // Two ranges combine into the widest they could jointly be, and the result is only as
            // good as its worse half: adding an exact count to an estimated one is still an
            // estimate, and rounding that up to EXACT would be a lie about the sum.
            EstimateQuality combined =
                    quality == EstimateQuality.EXACT_TOKENIZER
                            && other.quality == EstimateQuality.EXACT_TOKENIZER
                            ? EstimateQuality.EXACT_TOKENIZER
                            : EstimateQuality.ESTIMATED_RANGE;
            return new TokenRange(min + other.min, max + other.max, combined);
        }

        /** A count that is already in tokens — an allowance or a ceiling, not text. */
        public static TokenRange exactly(long tokens) {
            return new TokenRange(tokens, tokens, EstimateQuality.EXACT_TOKENIZER);
        }

        public static TokenRange none() {
            return exactly(0);
        }
    }

    /** Bounds one string. Null and blank both cost nothing rather than throwing. */
    public TokenRange estimate(String text) {
        if (text == null || text.isEmpty()) {
            return TokenRange.none();
        }
        int characters = text.length();
        long min = (long) Math.ceil(characters / MAX_CHARS_PER_TOKEN);
        long max = (long) Math.ceil(characters / MIN_CHARS_PER_TOKEN);
        return new TokenRange(min, max, EstimateQuality.ESTIMATED_RANGE);
    }

    /** Bounds several strings as one body of text. */
    public TokenRange estimateAll(Iterable<String> texts) {
        TokenRange total = TokenRange.none();
        for (String text : texts) {
            total = total.plus(estimate(text));
        }
        return total;
    }
}
