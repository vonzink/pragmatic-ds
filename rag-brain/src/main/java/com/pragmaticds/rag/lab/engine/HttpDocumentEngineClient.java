package com.pragmaticds.rag.lab.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pragmaticds.rag.lab.config.LabProperties;
import com.pragmaticds.rag.lab.engine.DocumentEngineFailure.Code;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The backend-only HTTP adapter onto Pragmatic DS Document Engine, pinned to release {@code spec5a}
 * ({@code c424870}): envelope {@code 1.0.0}, canonicalization {@code DOCENGINE-C14N-1}, and the
 * versioned canonical media type.
 *
 * <p>Endpoints, verified against engine source:
 *
 * <ul>
 *   <li>{@code POST /v1/packages} — multipart, part name {@code files}, {@code Idempotency-Key}
 *       header ({@code ingestion/.../web/PackageController.java:77-89})
 *   <li>{@code GET /v1/jobs/{id}} ({@code orchestration/.../web/JobController.java:19,28-31})
 *   <li>{@code GET /v1/packages/{id}/engine-results} — ascending metadata, never bytes
 *       ({@code results/.../web/EngineResultController.java:32-36})
 *   <li>{@code GET /v1/packages/{id}/engine-result} — current exact bytes
 *       ({@code EngineResultController.java:38-43})
 *   <li>{@code GET /v1/packages/{id}/engine-results/{revision}} — historical exact bytes
 *       ({@code EngineResultController.java:45-52})
 *   <li>{@code GET /v1/documents/{id}/fields} — current reviewed values, masked, never a citation
 * </ul>
 *
 * <p>Both exact-content shapes are gated in the engine
 * ({@code app/.../security/SecurityConfig.java}) because their bodies are unmasked borrower text,
 * which is why local dev-auth mode sends a backend-fixed {@code X-Dev-Role: ADMIN}
 * ({@code app/.../security/DevAuthFilter.java:56-58}). A deployment does not use dev-auth: it
 * presents an engine API key in {@code X-DocEngine-Api-Key}, and the authority comes from the
 * scopes that key holds rather than from anything this client asserts. Give that key the least
 * scope the engine offers for reading engine results — this client only ever GETs.
 *
 * <p>Fields view pinned at engine main @ 9a300e3 (see engine-goldens/MANIFEST.json).
 *
 * <p>Exists exactly when something can call the engine: the Income Lab prototype, the instance
 * dispatcher, or the Document Manager connector. With all three off this bean is never
 * constructed, so no collaborator can hold a reference to it and no engine call is reachable.
 *
 * <p>The condition deliberately does <em>not</em> include {@code ragbrain.instances.enabled}.
 * That flag opens generalized reads, which never touch the engine, and this constructor refuses
 * an engine configuration it cannot use — so binding the bean to the read flag would turn a
 * read-only deployment with no engine settings into a startup failure. It also must include more
 * than the Lab flag: {@code ParsedDataResolver} resolves this client through an
 * {@code ObjectProvider} and answers {@code PARSE_ENGINE_UNAVAILABLE} when it is absent, so
 * execution with the Lab off used to construct a dispatcher and then fail every dispatched run at
 * parse re-verification — a deployment that passed every gate and worked for nothing.
 */
@Component
@ConditionalOnExpression(
        "${ragbrain.lab.enabled:false} or ${ragbrain.instances.execution.enabled:false} "
                + "or ${ragbrain.instances.connector-enabled:false}")
public class HttpDocumentEngineClient implements DocumentEngineClient {

    /** The engine reads exactly this multipart part name. */
    private static final String FILE_PART_NAME = "files";

    /**
     * The engine's service-to-service credential header
     * ({@code ApiKeyAuthFilter.API_KEY_HEADER}). Spelled exactly: the engine matches the header
     * name, and a near-miss authenticates nothing while looking configured.
     */
    static final String API_KEY_HEADER = "X-DocEngine-Api-Key";

    /**
     * A fixed synthetic part filename. The browser's filename is never forwarded: it is untrusted
     * input that would otherwise be interpolated straight into a multipart header, and the engine
     * sniffs the real content type from the bytes regardless
     * ({@code ingestion/.../UploadService.java:214-221}).
     */
    private static final String SYNTHETIC_PART_FILENAME = "upload.bin";

    private static final String OCTET_STREAM = "application/octet-stream";
    private static final String JSON = "application/json";

    private static final String CANONICAL_BASE_TYPE =
            "application/vnd.pragmaticds.document-engine-result+json";
    private static final String CANONICAL_VERSION_PARAM = "version=1";

    /** A quoted lowercase SHA-256, which is exactly what the engine's ETag is. */
    private static final Pattern QUOTED_SHA256 = Pattern.compile("\"([0-9a-f]{64})\"");

    /** Header tokens only: no whitespace, no CR/LF, so a key cannot inject another header. */
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~:@+-]{1,200}");

    /** The engine's approved descriptor members, pinned exactly as spec5a serves them. */
    private static final Set<String> DESCRIPTOR_MEMBERS =
            Set.of(
                    "revision",
                    "processingJobId",
                    "parseGeneration",
                    "materializedJobAttempt",
                    "envelopeSchemaVersion",
                    "sourceSetSha256",
                    "provenanceSha256",
                    "envelopeSha256",
                    "envelopeSizeBytes",
                    "reuseEligibility",
                    "createdAt");

    /** Operational JSON only (upload, job, metadata). Envelope bytes go to the strict parser. */
    private static final ObjectMapper OPERATIONAL = JsonMapper.builder().build();

    private static final EngineEnvelopeParser PARSER = new EngineEnvelopeParser();
    private static final ReviewedFieldsParser REVIEWED_FIELDS = new ReviewedFieldsParser();

    private final LabProperties.Engine engine;
    private final long maxUploadBytes;
    private final HttpClient http;

    public HttpDocumentEngineClient(LabProperties properties) {
        // Defence in depth: LabProperties already guards this while enabled, but a
        // programmatically assembled configuration must not be able to skip the check.
        LabProperties.requireUsable(properties.engine());
        this.engine = properties.engine();
        this.maxUploadBytes = properties.maxUploadBytes();
        this.http =
                HttpClient.newBuilder()
                        // A 3xx to another host would bypass the base-URL guard entirely.
                        .followRedirects(HttpClient.Redirect.NEVER)
                        // The engine is a Tomcat HTTP/1.1 service; pinning the version keeps the
                        // streamed upload deterministically chunked rather than upgrade-dependent.
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofMillis(engine.connectTimeoutMs()))
                        .build();
    }

    // ------------------------------------------------------------------ registration

    @Override
    public UploadRegistration register(EngineUpload upload, String idempotencyKey) {
        Objects.requireNonNull(upload, "upload");
        String key = requireIdempotencyKey(idempotencyKey);
        // Both ceilings are enforced from the declared size, before a byte leaves this process.
        if (upload.sizeBytes() <= 0L) {
            throw new DocumentEngineFailure(Code.UPLOAD_EMPTY);
        }
        if (upload.sizeBytes() > maxUploadBytes) {
            throw new DocumentEngineFailure(Code.UPLOAD_TOO_LARGE);
        }

        String boundary = "LabUpload" + UUID.randomUUID().toString().replace("-", "");
        HttpRequest request =
                authorized("/v1/packages")
                        .header("Accept", JSON)
                        .header("Idempotency-Key", key)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(streamedMultipart(upload, boundary))
                        .build();

        JsonNode body = operationalJson(send(request, HttpResponse.BodyHandlers.ofByteArray()));
        List<RegisteredSource> sources = new ArrayList<>();
        for (JsonNode file : array(body, "files")) {
            // originalFilename and the declared contentType are deliberately never read.
            sources.add(
                    new RegisteredSource(
                            uuid(file, "id"),
                            text(file, "sha256"),
                            longValue(file, "sizeBytes"),
                            nullableInteger(file, "pageCount")));
        }
        List<String> duplicates = new ArrayList<>();
        for (JsonNode warning : array(body, "warnings")) {
            duplicates.add(text(warning, "shaPrefix"));
        }
        return new UploadRegistration(
                uuid(body, "packageId"), uuid(body, "jobId"), sources, duplicates);
    }

    /**
     * A multipart body whose file part is streamed straight from the request-scoped resource.
     *
     * <p>The publisher's content length is unknown, so the JDK sends the upload with chunked
     * transfer encoding. That is the point: nothing here calls {@code getBytes()}, allocates a
     * whole-document array, or opens an application-owned temporary file.
     */
    private static HttpRequest.BodyPublisher streamedMultipart(
            EngineUpload upload, String boundary) {
        byte[] preamble =
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\""
                                + FILE_PART_NAME
                                + "\"; filename=\""
                                + SYNTHETIC_PART_FILENAME
                                + "\"\r\nContent-Type: "
                                + OCTET_STREAM
                                + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8);
        byte[] epilogue = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(preamble),
                HttpRequest.BodyPublishers.ofInputStream(
                        () -> {
                            try {
                                return upload.content().getInputStream();
                            } catch (IOException exception) {
                                throw new UncheckedIOException(exception);
                            }
                        }),
                HttpRequest.BodyPublishers.ofByteArray(epilogue));
    }

    // ------------------------------------------------------------------ job and metadata

    @Override
    public JobSnapshot job(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        HttpRequest request = authorized("/v1/jobs/" + jobId).header("Accept", JSON).GET().build();

        JsonNode body = operationalJson(send(request, HttpResponse.BodyHandlers.ofByteArray()));
        // The stage trail is operational detail the Lab has no use for and must not surface.
        return new JobSnapshot(
                uuid(body, "id"),
                uuid(body, "packageId"),
                text(body, "status"),
                nullableText(body, "currentStage"));
    }

    @Override
    public List<RevisionDescriptor> revisionHistory(UUID packageId) {
        Objects.requireNonNull(packageId, "packageId");
        HttpRequest request =
                authorized("/v1/packages/" + packageId + "/engine-results")
                        .header("Accept", JSON)
                        .GET()
                        .build();

        JsonNode body = operationalJson(send(request, HttpResponse.BodyHandlers.ofByteArray()));
        if (!body.isArray()) {
            throw malformed();
        }
        List<RevisionDescriptor> history = new ArrayList<>();
        for (JsonNode node : body) {
            history.add(descriptor(node));
        }
        return List.copyOf(history);
    }

    private static RevisionDescriptor descriptor(JsonNode node) {
        if (!node.isObject()) {
            throw malformed();
        }
        // Pinned to spec5a: a member the approved view does not serve is a contract change, not a
        // detail to ignore silently.
        node.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!DESCRIPTOR_MEMBERS.contains(name)) {
                                throw malformed();
                            }
                        });
        return new RevisionDescriptor(
                integer(node, "revision"),
                uuid(node, "processingJobId"),
                integer(node, "parseGeneration"),
                integer(node, "materializedJobAttempt"),
                text(node, "envelopeSchemaVersion"),
                nullableText(node, "sourceSetSha256"),
                nullableText(node, "provenanceSha256"),
                text(node, "envelopeSha256"),
                longValue(node, "envelopeSizeBytes"),
                text(node, "reuseEligibility"),
                instant(node, "createdAt"));
    }

    // ------------------------------------------------------------------ exact content

    @Override
    public VerifiedEnvelope currentEnvelope(UUID packageId) {
        Objects.requireNonNull(packageId, "packageId");
        return exactContent("/v1/packages/" + packageId + "/engine-result", packageId, null);
    }

    @Override
    public VerifiedEnvelope envelopeRevision(UUID packageId, int revision) {
        Objects.requireNonNull(packageId, "packageId");
        if (revision < 1) {
            throw new DocumentEngineFailure(Code.REQUEST_ARGUMENT_INVALID);
        }
        return exactContent(
                "/v1/packages/" + packageId + "/engine-results/" + revision, packageId, revision);
    }

    @Override
    public ReviewedFields reviewedFields(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId");
        HttpRequest request =
                authorized("/v1/documents/" + documentId + "/fields").header("Accept", JSON).GET().build();

        HttpResponse<InputStream> response;
        byte[] bytes;
        try {
            response = send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (DocumentEngineFailure transport) {
            throw new DocumentEngineFailure(Code.ENGINE_READMODEL_UNAVAILABLE, transport.httpStatus());
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new DocumentEngineFailure(Code.ENGINE_READMODEL_UNAVAILABLE, response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            String baseType = contentType == null ? null : contentType.split(";")[0].trim();
            if (baseType == null || !baseType.equalsIgnoreCase(JSON)) {
                throw new DocumentEngineFailure(
                        Code.ENGINE_READMODEL_UNAVAILABLE, response.statusCode());
            }
            refuseDeclaredLengthBeyondCeiling(
                    response.headers().firstValue("Content-Length").orElse(null));
            bytes = readBounded(body);
        } catch (DocumentEngineFailure failure) {
            if (failure.code() == Code.ENGINE_ENVELOPE_TOO_LARGE) {
                throw new DocumentEngineFailure(
                        Code.ENGINE_READMODEL_UNAVAILABLE, response.statusCode());
            }
            throw failure;
        } catch (IOException exception) {
            throw new DocumentEngineFailure(Code.ENGINE_READMODEL_UNAVAILABLE);
        }
        ReviewedFields fields;
        try {
            fields = REVIEWED_FIELDS.parse(bytes);
        } catch (LabContractException contract) {
            throw new DocumentEngineFailure(Code.ENGINE_READMODEL_MALFORMED);
        }
        if (!documentId.equals(fields.documentId())) {
            throw new DocumentEngineFailure(Code.ENGINE_READMODEL_MALFORMED);
        }
        return fields;
    }

    /**
     * Reads one exact-content shape and verifies it completely before the strict parser sees a
     * byte: status, media type, quoted ETag, {@code Content-Length}, received length, ceiling, and
     * digest. Only then is the envelope parsed and its declared package/revision reconciled with
     * what was actually asked for.
     */
    private VerifiedEnvelope exactContent(String path, UUID packageId, Integer expectedRevision) {
        HttpRequest request =
                authorized(path)
                        .header("Accept", EngineArtifactDescriptor.CANONICAL_MEDIA_TYPE)
                        .GET()
                        .build();

        HttpResponse<InputStream> response =
                send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        String contentType;
        String etag;
        String contentLength;
        try (InputStream body = response.body()) {
            requireSuccess(response.statusCode());
            contentType = response.headers().firstValue("Content-Type").orElse(null);
            etag = response.headers().firstValue("ETag").orElse(null);
            contentLength = response.headers().firstValue("Content-Length").orElse(null);
            // Refuse an over-large result from its declared length, before reading any content.
            refuseDeclaredLengthBeyondCeiling(contentLength);
            bytes = readBounded(body);
        } catch (IOException exception) {
            throw new DocumentEngineFailure(Code.ENGINE_REQUEST_FAILED);
        }

        EngineArtifactDescriptor artifact =
                verifyExactContent(
                        contentType, etag, contentLength, bytes, engine.maxEnvelopeBytes());

        EngineResultEnvelope envelope = PARSER.parse(bytes);
        if (!packageId.equals(envelope.packageId())) {
            throw new DocumentEngineFailure(Code.ENGINE_PACKAGE_MISMATCH);
        }
        int revision = envelope.generation().packageRevision();
        if (expectedRevision != null && expectedRevision.intValue() != revision) {
            throw new DocumentEngineFailure(Code.ENGINE_REVISION_MISMATCH);
        }
        return new VerifiedEnvelope(artifact, envelope, revision);
    }

    /**
     * Verifies received engine-result bytes against the descriptor headers that accompanied them.
     * Package-private and pure so every branch — including a declared length that disagrees with
     * the received bytes, which a conforming HTTP client never lets through — is directly pinned.
     *
     * @return the verified artifact: the exact received bytes and their SHA-256
     * @throws DocumentEngineFailure with a value-free code on any disagreement
     */
    static EngineArtifactDescriptor verifyExactContent(
            String contentType,
            String etag,
            String contentLength,
            byte[] body,
            long maxEnvelopeBytes) {
        if (!isCanonicalMediaType(contentType)) {
            throw new DocumentEngineFailure(Code.ENGINE_CONTENT_TYPE_UNEXPECTED);
        }
        if (etag == null || etag.isBlank()) {
            throw new DocumentEngineFailure(Code.ENGINE_ETAG_MISSING);
        }
        Matcher quoted = QUOTED_SHA256.matcher(etag.trim());
        if (!quoted.matches()) {
            throw new DocumentEngineFailure(Code.ENGINE_ETAG_MALFORMED);
        }
        if (contentLength == null || contentLength.isBlank()) {
            throw new DocumentEngineFailure(Code.ENGINE_CONTENT_LENGTH_MISSING);
        }
        long declared;
        try {
            declared = Long.parseLong(contentLength.trim());
        } catch (NumberFormatException exception) {
            throw new DocumentEngineFailure(Code.ENGINE_CONTENT_LENGTH_MALFORMED);
        }
        if (declared < 0L) {
            throw new DocumentEngineFailure(Code.ENGINE_CONTENT_LENGTH_MALFORMED);
        }
        if (declared > maxEnvelopeBytes || body.length > maxEnvelopeBytes) {
            throw new DocumentEngineFailure(Code.ENGINE_ENVELOPE_TOO_LARGE);
        }
        if (declared != body.length) {
            throw new DocumentEngineFailure(Code.ENGINE_CONTENT_LENGTH_MISMATCH);
        }
        EngineArtifactDescriptor artifact = EngineArtifactDescriptor.of(body);
        if (!artifact.sha256().equals(quoted.group(1))) {
            throw new DocumentEngineFailure(Code.ENGINE_DIGEST_MISMATCH);
        }
        return artifact;
    }

    /** Parameter-tolerant equality with the engine's versioned canonical media type. */
    private static boolean isCanonicalMediaType(String header) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String[] parts = header.split(";");
        if (!parts[0].trim().equalsIgnoreCase(CANONICAL_BASE_TYPE)) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].trim().replace(" ", "").equalsIgnoreCase(CANONICAL_VERSION_PARAM)) {
                return true;
            }
        }
        return false;
    }

    private void refuseDeclaredLengthBeyondCeiling(String contentLength) {
        if (contentLength == null || contentLength.isBlank()) {
            return;
        }
        try {
            if (Long.parseLong(contentLength.trim()) > engine.maxEnvelopeBytes()) {
                throw new DocumentEngineFailure(Code.ENGINE_ENVELOPE_TOO_LARGE);
            }
        } catch (NumberFormatException malformedHeader) {
            // Header shape is the full verifier's problem; this guard only bounds the download.
        }
    }

    private byte[] readBounded(InputStream body) throws IOException {
        long limit = engine.maxEnvelopeBytes();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = body.read(chunk)) != -1) {
            if (buffer.size() + (long) read > limit) {
                throw new DocumentEngineFailure(Code.ENGINE_ENVELOPE_TOO_LARGE);
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    // ------------------------------------------------------------------ transport

    private HttpRequest.Builder authorized(String path) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri(path))
                        .timeout(Duration.ofMillis(engine.readTimeoutMs()));
        if (engine.hasApiKey()) {
            // The engine's service-to-service protocol. It hashes the key, resolves the
            // organization FROM it, and binds the key's own scopes — so unlike dev-auth there is
            // no org or role for this side to assert, and nothing request-borne can influence it.
            builder.header(API_KEY_HEADER, engine.apiKey());
        } else if (engine.devAuth()) {
            // Backend-fixed. Exact result bytes are ADMIN-only in the engine, and no request-borne
            // value may influence either header.
            builder.header("X-Dev-Role", "ADMIN");
            if (engine.orgId() != null) {
                builder.header("X-Dev-Org", engine.orgId().toString());
            }
        } else {
            builder.header("Authorization", "Bearer " + engine.bearerToken());
        }
        return builder;
    }

    private URI uri(String path) {
        try {
            return new URI(engine.normalizedBaseUrl() + path);
        } catch (URISyntaxException exception) {
            throw new DocumentEngineFailure(Code.REQUEST_ARGUMENT_INVALID);
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (HttpTimeoutException timeout) {
            throw new DocumentEngineFailure(Code.ENGINE_TIMEOUT);
        } catch (IOException | UncheckedIOException failure) {
            throw new DocumentEngineFailure(Code.ENGINE_REQUEST_FAILED);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DocumentEngineFailure(Code.ENGINE_REQUEST_FAILED);
        }
    }

    private static void requireSuccess(int status) {
        if (status >= 300 && status < 400) {
            throw new DocumentEngineFailure(Code.ENGINE_REDIRECT_REFUSED, status);
        }
        if (status < 200 || status >= 300) {
            throw new DocumentEngineFailure(Code.ENGINE_STATUS_UNEXPECTED, status);
        }
    }

    private static String requireIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new DocumentEngineFailure(Code.IDEMPOTENCY_KEY_INVALID);
        }
        return key;
    }

    // ------------------------------------------------------------------ operational JSON

    private static JsonNode operationalJson(HttpResponse<byte[]> response) {
        requireSuccess(response.statusCode());
        try {
            JsonNode node = OPERATIONAL.readTree(response.body());
            if (node == null || node.isMissingNode() || node.isNull()) {
                throw malformed();
            }
            return node;
        } catch (IOException exception) {
            throw malformed();
        }
    }

    private static DocumentEngineFailure malformed() {
        return new DocumentEngineFailure(Code.ENGINE_RESPONSE_MALFORMED);
    }

    private static Iterable<JsonNode> array(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw malformed();
        }
        return value;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw malformed();
        }
        return value.textValue();
    }

    private static String nullableText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw malformed();
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw malformed();
        }
        return value.intValue();
    }

    private static Integer nullableInteger(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        return integer(node, name);
    }

    private static long longValue(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw malformed();
        }
        return value.longValue();
    }

    private static UUID uuid(JsonNode node, String name) {
        try {
            return UUID.fromString(text(node, name));
        } catch (IllegalArgumentException exception) {
            throw malformed();
        }
    }

    private static Instant instant(JsonNode node, String name) {
        try {
            return Instant.parse(text(node, name));
        } catch (DateTimeParseException exception) {
            throw malformed();
        }
    }

    /** Configuration shape only; the engine record redacts its own token. */
    @Override
    public String toString() {
        return "HttpDocumentEngineClient[engine="
                + engine
                + ", maxUploadBytes="
                + maxUploadBytes
                + "]";
    }
}
