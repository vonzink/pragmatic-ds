package com.pragmaticds.rag.lab.eval;

import com.pragmaticds.rag.lab.engine.EngineArtifactDescriptor;
import com.pragmaticds.rag.lab.engine.EngineEnvelopeParser;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The synthetic parsed packages a scenario may name, read from the classpath.
 *
 * <p><b>Source-controlled and allowlisted</b>, exactly as scenario sets are, and for the same
 * reason: a fixture is half of what decides whether a release may go live. A fixture nobody
 * reviewed could be shaped to make any release pass.
 *
 * <p><b>Every digest is computed or verified, never trusted.</b> The artifact digest is taken over
 * the exact bytes read, through the same {@link EngineArtifactDescriptor#of} a real engine read
 * uses. The parse's own {@code sourceSetSha256} cannot be computed here — it is a value the engine
 * stamps into the envelope — so it is <em>checked</em> against a digest derived from the fixture's
 * own source content hashes, and a mismatch refuses the fixture. A fixture that declared a digest
 * unrelated to its content would be precisely the invented content hash this system refuses
 * everywhere else, and every provenance record downstream of an evaluation would inherit it.
 *
 * <p><b>Synthetic only.</b> Nothing here is a real borrower package. An evaluation report quotes
 * model output about whatever it was given, which is why scenario sets may not name real packages
 * and why these files carry invented names, amounts, and identifiers throughout.
 *
 * <p><b>Invented values, borrowed vocabulary.</b> The amounts are made up; the field names,
 * document type codes, dataTypes and rule-pack identifiers are not — they are the engine's own,
 * and nothing here can check that, because no consumer in this repo looks a field up by name. See
 * {@code instances/fixtures/README.md} for the engine migrations each vocabulary comes from.
 */
@Component
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstancePackageFixtureRegistry {

    /**
     * Every shipped fixture, keyed by the name a scenario uses. A fixed map is the trust boundary:
     * adding a fixture is a reviewed code change, like adding a scenario set or an output schema.
     */
    private static final Map<String, String> ALLOWLIST = Map.of(
            "fixture-paystub-single", "instances/fixtures/fixture-paystub-single.json",
            "fixture-paystub-w2", "instances/fixtures/fixture-paystub-w2.json",
            "fixture-paystub-unreadable", "instances/fixtures/fixture-paystub-unreadable.json",
            "fixture-unsupported-only", "instances/fixtures/fixture-unsupported-only.json",
            "fixture-paystub-review-required",
                    "instances/fixtures/fixture-paystub-review-required.json",
            "fixture-bank-statement-single",
                    "instances/fixtures/fixture-bank-statement-single.json");

    /** Why a fixture cannot be used. Stable codes; never a fixture's contents. */
    public static final class PackageFixtureException extends RuntimeException {
        public enum Code {
            FIXTURE_NOT_ALLOWLISTED,
            FIXTURE_UNREADABLE,
            /** The declared parse digest does not match the fixture's own source content. */
            FIXTURE_DIGEST_MISMATCH,
            /** The fixture is internally inconsistent — a reference with no referent. */
            FIXTURE_NOT_SELF_CONSISTENT
        }

        private final Code code;

        public PackageFixtureException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    /**
     * One loaded fixture: the parse, the exact bytes it came from, and their digest.
     *
     * <p>The bytes are kept because identity is the received bytes rather than a reserialization —
     * the same rule a real engine read follows.
     */
    public record PackageFixture(
            String name,
            EngineResultEnvelope envelope,
            EngineArtifactDescriptor artifact,
            List<UUID> sourceIds) {

        public PackageFixture {
            sourceIds = List.copyOf(Objects.requireNonNull(sourceIds, "sourceIds"));
        }
    }

    /**
     * The same parser a real engine response goes through.
     *
     * <p>Not a plain object mapper: whatever the parser enforces about a genuine envelope — required
     * fields, bounded values, referential rules — a fixture must satisfy too, or fixtures would be
     * held to a lower standard than the parses they stand in for.
     */
    private static final EngineEnvelopeParser PARSER = new EngineEnvelopeParser();

    private final Map<String, PackageFixture> cache = new LinkedHashMap<>();

    /** Whether a scenario's fixture name is one this build ships. */
    public boolean has(String name) {
        return name != null && ALLOWLIST.containsKey(name);
    }

    /** The fixture, or a refusal. */
    public PackageFixture require(String name) {
        return find(name).orElseThrow(() ->
                new PackageFixtureException(PackageFixtureException.Code.FIXTURE_NOT_ALLOWLISTED));
    }

    public synchronized Optional<PackageFixture> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        PackageFixture cached = cache.get(name);
        if (cached != null) {
            return Optional.of(cached);
        }
        String path = ALLOWLIST.get(name);
        if (path == null) {
            return Optional.empty();
        }
        PackageFixture loaded = read(name, path);
        cache.put(name, loaded);
        return Optional.of(loaded);
    }

    private PackageFixture read(String name, String path) {
        byte[] bytes;
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            bytes = stream.readAllBytes();
        } catch (IOException | RuntimeException unreadable) {
            throw new PackageFixtureException(PackageFixtureException.Code.FIXTURE_UNREADABLE);
        }

        EngineResultEnvelope envelope;
        try {
            envelope = PARSER.parse(bytes);
        } catch (Exception malformed) {
            // The cause names a classpath location and quotes envelope content; the code is the
            // whole disclosure, as everywhere else in this package.
            throw new PackageFixtureException(PackageFixtureException.Code.FIXTURE_UNREADABLE);
        }

        requireSelfConsistent(envelope);
        requireDeclaredDigestMatchesContent(envelope);

        // Digest over the exact bytes read, through the same factory a real engine read uses.
        return new PackageFixture(name, envelope, EngineArtifactDescriptor.of(bytes),
                envelope.sources().stream().map(EngineResultEnvelope.SourceFile::id).toList());
    }

    /**
     * Refuses a fixture whose parts do not refer to each other.
     *
     * <p>A dangling page reference is the dangerous kind of wrong: the prompt renderer would
     * happily render <em>something</em>, and the gate would then be judging a release against a
     * parse the engine could never have produced. Caught here, at load, rather than becoming a
     * verdict.
     */
    private static void requireSelfConsistent(EngineResultEnvelope envelope) {
        if (envelope.sources().isEmpty() || envelope.pages().isEmpty()
                || envelope.documents().isEmpty()) {
            throw new PackageFixtureException(
                    PackageFixtureException.Code.FIXTURE_NOT_SELF_CONSISTENT);
        }
        List<UUID> sourceIds = envelope.sources().stream()
                .map(EngineResultEnvelope.SourceFile::id).toList();
        List<UUID> pageIds = envelope.pages().stream()
                .map(EngineResultEnvelope.EnginePage::id).toList();

        boolean everyPageHasASource = envelope.pages().stream()
                .allMatch(page -> sourceIds.contains(page.sourceFileId()));
        boolean everyDocumentPageExists = envelope.documents().stream()
                .allMatch(document -> !document.pageIds().isEmpty()
                        && pageIds.containsAll(document.pageIds()));
        boolean everyUnassignedPageExists = pageIds.containsAll(envelope.unassignedPageIds());

        if (!everyPageHasASource || !everyDocumentPageExists || !everyUnassignedPageExists) {
            throw new PackageFixtureException(
                    PackageFixtureException.Code.FIXTURE_NOT_SELF_CONSISTENT);
        }
    }

    /**
     * Checks the parse digest the fixture declares against its own content.
     *
     * <p>The engine stamps {@code sourceSetSha256} into a real envelope, so a fixture must declare
     * one too or the record cannot be built. Declaring is not the same as being believed: it is
     * derived here from the source content digests in ordinal order, length-prefixed so no
     * reordering of the same hashes produces the same result, and a fixture that disagrees is
     * refused rather than quietly carrying a digest that describes nothing.
     */
    private static void requireDeclaredDigestMatchesContent(EngineResultEnvelope envelope) {
        String expected = sourceSetDigest(envelope);
        if (!expected.equals(envelope.generation().sourceSetSha256())) {
            throw new PackageFixtureException(
                    PackageFixtureException.Code.FIXTURE_DIGEST_MISMATCH);
        }
    }

    /** The digest a fixture's {@code generation.sourceSetSha256} must carry. Public so a fixture
     * author can regenerate it rather than guess at it. */
    public static String sourceSetDigest(EngineResultEnvelope envelope) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            envelope.sources().stream()
                    .sorted(java.util.Comparator.comparingInt(
                            EngineResultEnvelope.SourceFile::ordinal))
                    .forEach(source -> {
                        byte[] field = source.contentSha256().getBytes(StandardCharsets.UTF_8);
                        digest.update(Integer.toString(field.length)
                                .getBytes(StandardCharsets.US_ASCII));
                        digest.update((byte) ':');
                        digest.update(field);
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
