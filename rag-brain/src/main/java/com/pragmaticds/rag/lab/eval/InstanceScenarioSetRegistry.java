package com.pragmaticds.rag.lab.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The scenario sets a release may be evaluated against, read from the classpath.
 *
 * <p><b>Source-controlled, not administrator-supplied.</b> A scenario set decides whether a release
 * may go live, so letting one be uploaded would make the promotion gate self-certifying: an
 * administrator could author a candidate and the test that approves it in the same session. These
 * live in the repository, change through review, and are addressed by id and version.
 *
 * <p><b>Synthetic fixtures only.</b> Every document referenced here is a package fixture created
 * for testing. A scenario set never names a real borrower package, because an evaluation report
 * quotes model output about whatever it was given.
 *
 * <p>The registry is an allowlist in exactly the sense {@code InstanceOutputSchemaRegistry} is: a
 * release naming a set that is not here cannot be evaluated, and therefore cannot be promoted.
 */
@Component
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstanceScenarioSetRegistry {

    /**
     * Every shipped scenario set, keyed by id and version. A fixed map is the whole trust
     * boundary: adding a set is a code change that goes through review, exactly like adding an
     * output schema.
     *
     * <p>An older version is never removed or edited once shipped. A stored evaluation pins the
     * digest of the exact bytes it was read from, and {@code InstanceEvaluationService} folds that
     * digest into the report digest — so changing a set in place would silently stop every
     * historical report for that set from reproducing. A changed gate is a new version.
     */
    private static final Map<String, String> ALLOWLIST = Map.of(
            "income-smoke:1", "instances/evaluations/income-smoke-v1.json",
            "income-smoke:2", "instances/evaluations/income-smoke-v2.json",
            "income-smoke:4", "instances/evaluations/income-smoke-v4.json",
            "income-smoke:5", "instances/evaluations/income-smoke-v5.json",
            "assets-smoke:1", "instances/evaluations/assets-smoke-v1.json");

    // income-smoke:3 ships as a resource but is NEVER ALLOWLISTED, so no release can name it. It
    // asserts /findings/findings/0/subjectKey, and a subject key is hashed from a scope. A
    // dispatched run gets its scope from the registration, but an evaluation runs a fixture that
    // came from no registration, so under v3 no evaluation can produce the key and every correct
    // release would fail. income-smoke:5 carries the same assertion on a scenario that declares a
    // synthetic scope of its own (Scenario#subjectScope), which is the gate v3 meant to be.

    /** Why a scenario set cannot be used. Stable codes; never a scenario's contents. */
    public static final class ScenarioSetException extends RuntimeException {
        public enum Code {
            SCENARIO_SET_NOT_ALLOWLISTED,
            SCENARIO_SET_UNREADABLE,
            /**
             * A pointer that cannot address anything in the assertion document.
             *
             * <p>Refused at load rather than at evaluation, because an unaddressable
             * <em>forbidden</em> pointer is satisfied by every release forever — a recorded pass
             * that measured nothing. See {@link InstanceAssertionDocument}.
             */
            SCENARIO_SET_POINTER_UNKNOWN
        }

        private final Code code;

        public ScenarioSetException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    /**
     * One scenario set: its identity, its cases, and the digest of the exact bytes it was read
     * from. The digest is what a stored evaluation result pins to, so a set edited after a pass
     * cannot keep vouching for a release it no longer describes.
     */
    public record ScenarioSet(String id, int version, String sha256, List<Scenario> scenarios) {
        public ScenarioSet {
            scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
        }
    }

    /**
     * One case. {@code packageFixture} names a synthetic parse; the assertions are JSON pointers
     * into the analyzer's envelope, never expected borrower values.
     *
     * <p>{@code subjectScope} is null unless the case asserts a subject key. A fixture came from no
     * registration, so it has no scope of its own; a case that needs one declares a synthetic scope
     * here, and the evaluation runner hands it to the run in place of a registration's.
     */
    public record Scenario(
            String name,
            String packageFixture,
            int revision,
            List<String> requiredPointers,
            List<String> forbiddenPointers,
            String subjectScope) {

        public Scenario {
            requiredPointers = List.copyOf(
                    Objects.requireNonNull(requiredPointers, "requiredPointers"));
            forbiddenPointers = List.copyOf(
                    Objects.requireNonNull(forbiddenPointers, "forbiddenPointers"));
            if (subjectScope != null && subjectScope.isBlank()) {
                throw new IllegalArgumentException("subjectScope must be absent or non-blank");
            }
        }

        public Scenario(String name, String packageFixture, int revision,
                        List<String> requiredPointers, List<String> forbiddenPointers) {
            this(name, packageFixture, revision, requiredPointers, forbiddenPointers, null);
        }
    }

    private final ObjectMapper mapper;
    private final Map<String, ScenarioSet> cache = new LinkedHashMap<>();

    public InstanceScenarioSetRegistry(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** One offerable scenario set: identity, digest and size. Never a scenario's contents. */
    public record ScenarioSetDescriptor(String id, int version, String sha256, int scenarioCount) {}

    /**
     * Every shipped scenario set, as something a wizard can offer.
     *
     * <p>The count is included because it is the one thing about a set worth seeing before
     * choosing it — a set with two cases and a set with two hundred are different promotion gates.
     * The scenarios themselves stay here: they name fixtures and JSON pointers, and neither is
     * anything a form needs.
     *
     * <p>An unreadable set propagates rather than being skipped. The allowlist is a compile-time
     * constant pointing at resources this build ships, so a set that will not load is a broken
     * deployment; dropping it silently would leave someone wondering why a set they can see in the
     * source is missing from the form.
     */
    public synchronized List<ScenarioSetDescriptor> list() {
        List<ScenarioSetDescriptor> offerable = new ArrayList<>(ALLOWLIST.size());
        for (String key : ALLOWLIST.keySet().stream().sorted().toList()) {
            int separator = key.lastIndexOf(':');
            String id = key.substring(0, separator);
            int version = Integer.parseInt(key.substring(separator + 1));
            find(id, version).ifPresent(set -> offerable.add(new ScenarioSetDescriptor(
                    set.id(), set.version(), set.sha256(), set.scenarios().size())));
        }
        return List.copyOf(offerable);
    }

    /** The set, or a refusal. Used where absence is a failure. */
    public ScenarioSet require(String scenarioSetId, int version) {
        return find(scenarioSetId, version).orElseThrow(() ->
                new ScenarioSetException(ScenarioSetException.Code.SCENARIO_SET_NOT_ALLOWLISTED));
    }

    /** The set, or empty. Used where absence is a violation to report rather than throw. */
    public synchronized Optional<ScenarioSet> find(String scenarioSetId, int version) {
        if (scenarioSetId == null || version < 1) {
            return Optional.empty();
        }
        String key = scenarioSetId + ":" + version;
        ScenarioSet cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        String path = ALLOWLIST.get(key);
        if (path == null) {
            return Optional.empty();
        }
        ScenarioSet loaded = read(scenarioSetId, version, path);
        cache.put(key, loaded);
        return Optional.of(loaded);
    }

    private ScenarioSet read(String scenarioSetId, int version, String path) {
        byte[] bytes;
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            bytes = stream.readAllBytes();
        } catch (IOException | RuntimeException unreadable) {
            // The cause names a classpath location; the code is the whole disclosure.
            throw new ScenarioSetException(ScenarioSetException.Code.SCENARIO_SET_UNREADABLE);
        }
        try {
            JsonNode root = mapper.readTree(bytes);
            List<Scenario> scenarios = new ArrayList<>();
            for (JsonNode node : root.path("scenarios")) {
                List<String> required = strings(node.path("requiredPointers"));
                List<String> forbidden = strings(node.path("forbiddenPointers"));
                requireAddressable(required);
                requireAddressable(forbidden);
                scenarios.add(new Scenario(
                        node.path("name").asText(),
                        node.path("packageFixture").asText(),
                        node.path("revision").asInt(1),
                        required,
                        forbidden,
                        node.hasNonNull("subjectScope") ? node.get("subjectScope").asText() : null));
            }
            if (scenarios.isEmpty()) {
                throw new ScenarioSetException(ScenarioSetException.Code.SCENARIO_SET_UNREADABLE);
            }
            // Digest of the exact bytes read, not of the parsed tree: what a result pins to must
            // be the file, so that reformatting it is visible as a change.
            return new ScenarioSet(scenarioSetId, version, sha256Hex(bytes), scenarios);
        } catch (ScenarioSetException refused) {
            throw refused;
        } catch (Exception malformed) {
            throw new ScenarioSetException(ScenarioSetException.Code.SCENARIO_SET_UNREADABLE);
        }
    }

    /**
     * Refuses a pointer that cannot address the assertion document.
     *
     * <p>The pointer itself is not in the exception: a scenario set is source-controlled and
     * reviewed, so the code plus the file it came from is enough to find it, and the taxonomy
     * stays value-free like every other one here.
     */
    private static void requireAddressable(List<String> pointers) {
        for (String pointer : pointers) {
            if (!InstanceAssertionDocument.addressable(pointer)) {
                throw new ScenarioSetException(
                        ScenarioSetException.Code.SCENARIO_SET_POINTER_UNKNOWN);
            }
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            values.add(node.asText());
        }
        return values;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
