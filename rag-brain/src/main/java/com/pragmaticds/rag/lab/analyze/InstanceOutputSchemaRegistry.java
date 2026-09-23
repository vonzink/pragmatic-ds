package com.pragmaticds.rag.lab.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The output schemas a release is allowed to pin, as a server-side allowlist.
 *
 * <p>A release names a schema by id and digest, never by path or URL. The id resolves through the
 * fixed map below to a classpath resource this build ships, and the resource's bytes must hash to
 * the digest the release recorded. A release therefore cannot make the server read an arbitrary
 * file, reach a network location, or silently accept a schema that changed after the release was
 * pinned — the digest mismatch is the same failure either way.
 */
public interface InstanceOutputSchemaRegistry {

    /** Resolves one pinned schema, or throws if it is not allowlisted or the digest moved. */
    JsonSchema require(String schemaId, String expectedSha256);

    /**
     * The same resolution, returning the schema's own verified text.
     *
     * <p>A release's output schema is also the instruction that tells a model what to emit, so the
     * prompt needs the text and not just a parsed validator. These are the exact bytes the digest
     * was checked against rather than a re-serialization of the parsed form, so what the model is
     * shown is what the release pinned.
     */
    String requireText(String schemaId, String expectedSha256);

    /**
     * Every allowlisted schema, with the digest of the bytes this build actually ships.
     *
     * <p>A release must pin both the id and the digest, and the digest is over the resource's
     * bytes — so it is not something a client can know, compute, or safely remember. Without this
     * a wizard could only author an output contract by hardcoding a digest that silently stops
     * matching the day the schema file is edited.
     *
     * <p>Ids and digests only. The schema text is served nowhere: a release reads it through
     * {@link #requireText} after the digest check, and nothing else needs it.
     */
    List<SchemaDescriptor> list();

    /** One offerable schema. */
    record SchemaDescriptor(String schemaId, String sha256) {}

    /** Stable, value-free failures. */
    final class OutputSchemaException extends RuntimeException {
        public enum Code {
            OUTPUT_SCHEMA_NOT_ALLOWLISTED,
            OUTPUT_SCHEMA_DIGEST_MISMATCH,
            OUTPUT_SCHEMA_UNREADABLE
        }

        private final Code code;

        public OutputSchemaException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }
}

@Component
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class ClasspathInstanceOutputSchemaRegistry implements InstanceOutputSchemaRegistry {

    /**
     * Schema id to classpath resource. This map is the entire trust boundary: adding an entry is a
     * deliberate server-side act, and nothing a release says can extend it.
     */
    private static final Map<String, String> ALLOWLIST = Map.of(
            "analyzer-envelope-v2", "ai/analyzer-envelope-v2.schema.json");

    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, JsonSchema> cache = new ConcurrentHashMap<>();

    ClasspathInstanceOutputSchemaRegistry(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String requireText(String schemaId, String expectedSha256) {
        return new String(verifiedBytes(schemaId, expectedSha256), StandardCharsets.UTF_8);
    }

    @Override
    public List<SchemaDescriptor> list() {
        // A missing resource is a broken build, not a schema to quietly leave out of the list.
        // Failing here is loud and immediate; offering one fewer option would hide it until
        // somebody wondered why they could not select a schema this build is supposed to ship.
        return ALLOWLIST.keySet().stream().sorted()
                .map(id -> new SchemaDescriptor(id, sha256Hex(read(ALLOWLIST.get(id)))))
                .toList();
    }

    @Override
    public JsonSchema require(String schemaId, String expectedSha256) {
        byte[] bytes = verifiedBytes(schemaId, expectedSha256);
        // Keyed by digest as well as id: a rebuilt schema is a different schema, and reusing a
        // cached one across that change would defeat the whole check.
        return cache.computeIfAbsent(schemaId + ':' + expectedSha256, ignored -> parse(bytes));
    }

    /** The allowlist and digest gate, shared by both entry points. */
    private byte[] verifiedBytes(String schemaId, String expectedSha256) {
        String resource = ALLOWLIST.get(schemaId);
        if (resource == null) {
            throw new OutputSchemaException(
                    OutputSchemaException.Code.OUTPUT_SCHEMA_NOT_ALLOWLISTED);
        }
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new OutputSchemaException(
                    OutputSchemaException.Code.OUTPUT_SCHEMA_DIGEST_MISMATCH);
        }

        byte[] bytes = read(resource);
        if (!sha256Hex(bytes).equals(expectedSha256)) {
            throw new OutputSchemaException(
                    OutputSchemaException.Code.OUTPUT_SCHEMA_DIGEST_MISMATCH);
        }
        return bytes;
    }

    private JsonSchema parse(byte[] bytes) {
        try {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(mapper.readTree(new String(bytes, StandardCharsets.UTF_8)));
        } catch (IOException unparseable) {
            throw new OutputSchemaException(OutputSchemaException.Code.OUTPUT_SCHEMA_UNREADABLE);
        }
    }

    private static byte[] read(String resource) {
        try (InputStream in = ClasspathInstanceOutputSchemaRegistry.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new OutputSchemaException(
                        OutputSchemaException.Code.OUTPUT_SCHEMA_UNREADABLE);
            }
            return in.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
