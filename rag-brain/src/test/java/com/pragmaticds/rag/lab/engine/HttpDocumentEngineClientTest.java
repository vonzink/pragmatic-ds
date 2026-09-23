package com.pragmaticds.rag.lab.engine;

import com.pragmaticds.rag.lab.config.LabProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend-only Document Engine adapter contract tests.
 *
 * <p>Every identifier, digest and byte below is invented. Nothing here is derived from a corpus
 * document, a real package, or any borrower-shaped value.
 */
class HttpDocumentEngineClientTest {

    private static final String BEARER = "synthetic-bearer-not-a-real-token";
    private static final String API_KEY = "synthetic-engine-api-key-not-a-real-key";
    private static final UUID ENGINE_ORG = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID PKG = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID JOB = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SRC = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PAGE0 = UUID.fromString("44444444-4444-4444-8444-444444444440");
    private static final UUID DOC0 = UUID.fromString("55555555-5555-4555-8555-555555555550");
    private static final UUID SCHEMA0 = UUID.fromString("66666666-6666-4666-8666-666666666660");
    private static final String SRC_SHA = "a1".repeat(32);
    private static final String SET_SHA = "b2".repeat(32);

    /**
     * The smallest synthetic envelope the Task 1 strict parser accepts: canonical member order,
     * canonical numbers, one PAYSTUB document holding one MISSING field.
     */
    private static final String ENVELOPE =
            ("{\"canonicalizationVersion\":\"DOCENGINE-C14N-1\","
                            + "\"documents\":[{\"documentTypeCode\":\"PAYSTUB\",\"fields\":["
                            + "{\"confidence\":0,\"confidenceComponents\":null,\"dataType\":\"MONEY\","
                            + "\"displayedText\":null,\"evidence\":[],\"extractorVersion\":\"5.1.0\","
                            + "\"groupKey\":null,\"method\":\"NONE\",\"name\":\"gross_pay\","
                            + "\"normalized\":null,\"rawValue\":null,"
                            + "\"schema\":{\"id\":\"@SCHEMA0@\",\"version\":\"2024.1\"},"
                            + "\"sensitive\":false,\"status\":\"MISSING\","
                            + "\"validationStatus\":\"NOT_VALIDATED\"}],"
                            + "\"id\":\"@DOC0@\",\"ordinal\":0,\"pageIds\":[\"@PAGE0@\"]}],"
                            + "\"envelopeVersion\":\"1.0.0\","
                            + "\"generation\":{\"packageRevision\":@REVISION@,\"parseGeneration\":1,"
                            + "\"processingJobId\":\"@JOB@\","
                            + "\"reuseEligibility\":\"PARSE_ONCE_CURRENT_PACKAGE\","
                            + "\"sourceSetSha256\":\"@SET_SHA@\"},"
                            + "\"package\":{\"id\":\"@PKG@\"},"
                            + "\"pages\":[{\"blank\":false,\"classification\":null,"
                            + "\"duplicate\":false,\"heightPt\":792,\"id\":\"@PAGE0@\","
                            + "\"packagePageIndex\":0,\"renderDpi\":null,\"rotation\":0,"
                            + "\"sourceFileId\":\"@SRC@\",\"sourcePageIndex\":0,"
                            + "\"textLayer\":\"NATIVE\",\"widthPt\":612}],"
                            + "\"provenance\":{\"applicationRelease\":{\"availability\":\"UNAVAILABLE\"},"
                            + "\"extractionEngineRelease\":{\"availability\":\"UNAVAILABLE\"},"
                            + "\"stages\":[],"
                            + "\"workerContractRelease\":{\"availability\":\"UNAVAILABLE\"}},"
                            + "\"sources\":[{\"contentSha256\":\"@SRC_SHA@\","
                            + "\"contentType\":\"application/pdf\",\"id\":\"@SRC@\","
                            + "\"ordinal\":0,\"sizeBytes\":48211}],"
                            + "\"unassignedPageIds\":[]}");

    static byte[] envelopeBytes(UUID packageId, int revision) {
        String json =
                ENVELOPE
                        .replace("@PKG@", packageId.toString())
                        .replace("@JOB@", JOB.toString())
                        .replace("@SRC@", SRC.toString())
                        .replace("@PAGE0@", PAGE0.toString())
                        .replace("@DOC0@", DOC0.toString())
                        .replace("@SCHEMA0@", SCHEMA0.toString())
                        .replace("@SRC_SHA@", SRC_SHA)
                        .replace("@SET_SHA@", SET_SHA)
                        .replace("@REVISION@", Integer.toString(revision));
        return json.getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- fixture sanity

    @Test
    void theSyntheticEnvelopeFixtureSatisfiesTheStrictTaskOneParser() {
        EngineResultEnvelope parsed = new EngineEnvelopeParser().parse(envelopeBytes(PKG, 1));

        assertEquals(PKG, parsed.packageId());
        assertEquals("1.0.0", parsed.envelopeVersion());
        assertEquals("DOCENGINE-C14N-1", parsed.canonicalizationVersion());
        assertEquals(1, parsed.generation().packageRevision());
    }

    // ---------------------------------------------------------------- feature flag

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LabProperties.class)
    @Import(HttpDocumentEngineClient.class)
    static class LabWiring {}

    private ApplicationContextRunner wiring() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of())
                .withUserConfiguration(LabWiring.class);
    }

    @Test
    void theAdapterIsNeitherConstructedNorAvailableWhenTheLabIsDisabled() {
        wiring()
                .withPropertyValues("ragbrain.lab.enabled=false")
                .run(
                        context -> {
                            assertFalse(context.getStartupFailure() != null);
                            assertEquals(
                                    0,
                                    context.getBeanNamesForType(DocumentEngineClient.class).length,
                                    "no Document Engine port bean may exist with the Lab disabled");
                            assertEquals(
                                    0,
                                    context.getBeanNamesForType(HttpDocumentEngineClient.class)
                                            .length);
                        });
    }

    @Test
    void anAbsentLabSectionLeavesAnOrdinaryContextStartingWithNoAdapter() {
        wiring()
                .run(
                        context -> {
                            assertFalse(context.getStartupFailure() != null);
                            assertEquals(
                                    0,
                                    context.getBeanNamesForType(DocumentEngineClient.class).length);
                        });
    }

    @Test
    void theAdapterIsConstructedOnlyWhenTheLabIsEnabled() {
        wiring()
                .withPropertyValues(
                        "ragbrain.lab.enabled=true",
                        "ragbrain.lab.max-upload-bytes=26214400",
                        "ragbrain.lab.engine.base-url=http://localhost:9090",
                        "ragbrain.lab.engine.dev-auth=true",
                        "ragbrain.lab.engine.org-id=" + ENGINE_ORG,
                        "ragbrain.lab.engine.max-envelope-bytes=8388608",
                        "ragbrain.lab.engine.connect-timeout-ms=2000",
                        "ragbrain.lab.engine.read-timeout-ms=5000")
                .run(
                        context -> {
                            assertNotNull(context.getBean(DocumentEngineClient.class));
                            assertTrue(
                                    context.getBean(DocumentEngineClient.class)
                                            instanceof HttpDocumentEngineClient);
                        });
    }

    // ---------------------------------------------------------------- configuration guards

    private static LabProperties.Engine engine(String baseUrl) {
        return new LabProperties.Engine(baseUrl, ENGINE_ORG, null, null, true, 8_388_608L, 2_000, 5_000);
    }

    private static LabProperties enabled(LabProperties.Engine engine) {
        return new LabProperties(true, 26_214_400L, engine);
    }

    @Test
    void aBaseUrlCarryingUserInfoIsRejected() {
        assertThrows(
                IllegalStateException.class,
                () -> enabled(engine("http://operator:secret@localhost:9090")));
    }

    @Test
    void aBaseUrlCarryingAQueryOrFragmentIsRejected() {
        assertThrows(
                IllegalStateException.class,
                () -> enabled(engine("http://localhost:9090?org=other")));
        assertThrows(
                IllegalStateException.class,
                () -> enabled(engine("http://localhost:9090#fragment")));
    }

    @Test
    void aNonHttpOrRelativeBaseUrlIsRejected() {
        assertThrows(IllegalStateException.class, () -> enabled(engine("file:///etc/passwd")));
        assertThrows(IllegalStateException.class, () -> enabled(engine("/v1")));
        assertThrows(IllegalStateException.class, () -> enabled(engine("   ")));
    }

    @Test
    void exactlyOneAuthModeIsRequiredWhileTheLabIsEnabled() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        enabled(
                                new LabProperties.Engine(
                                        "http://localhost:9090",
                                        ENGINE_ORG,
                                        BEARER,
                                        null,
                                        true,
                                        8_388_608L,
                                        2_000,
                                        5_000)),
                "dev-auth and a bearer token together must fail closed");
        assertThrows(
                IllegalStateException.class,
                () ->
                        enabled(
                                new LabProperties.Engine(
                                        "http://localhost:9090",
                                        null,
                                        null,
                                        null,
                                        false,
                                        8_388_608L,
                                        2_000,
                                        5_000)),
                "no auth mode at all must fail closed");
    }

    @Test
    void anEngineOrgIdIsRejectedOutsideLocalDevAuthMode() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        enabled(
                                new LabProperties.Engine(
                                        "http://localhost:9090",
                                        ENGINE_ORG,
                                        BEARER,
                                        null,
                                        false,
                                        8_388_608L,
                                        2_000,
                                        5_000)),
                "a bearer deployment takes its org from the token, never from Lab configuration");
    }

    @Test
    void theLabUploadCeilingMayNotExceedTheServletMultipartCeiling() {
        assertEquals(26_214_400L, LabProperties.SERVLET_MULTIPART_MAX_FILE_BYTES);
        assertEquals(27_262_976L, LabProperties.SERVLET_MULTIPART_MAX_REQUEST_BYTES);

        assertThrows(
                IllegalStateException.class,
                () -> new LabProperties(true, 26_214_401L, engine("http://localhost:9090")));
        assertThrows(
                IllegalStateException.class,
                () -> new LabProperties(true, 0L, engine("http://localhost:9090")));
        assertThrows(
                IllegalStateException.class,
                () -> new LabProperties(true, -1L, engine("http://localhost:9090")));
        assertNotNull(new LabProperties(true, 26_214_400L, engine("http://localhost:9090")));
    }

    @Test
    void timeoutsAndEnvelopeCeilingMustBePositiveAndBounded() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        enabled(
                                new LabProperties.Engine(
                                        "http://localhost:9090",
                                        ENGINE_ORG,
                                        null,
                                        null,
                                        true,
                                        8_388_608L,
                                        0,
                                        5_000)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        enabled(
                                new LabProperties.Engine(
                                        "http://localhost:9090",
                                        ENGINE_ORG,
                                        null,
                                        null,
                                        true,
                                        8_388_608L,
                                        2_000,
                                        0)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        enabled(
                                new LabProperties.Engine(
                                        "http://localhost:9090",
                                        ENGINE_ORG,
                                        null,
                                        null,
                                        true,
                                        0L,
                                        2_000,
                                        5_000)));
    }

    @Test
    void aDisabledLabValidatesNothingSoOrdinaryStartupIsUnaffected() {
        assertNotNull(new LabProperties(false, 0L, null));
        assertNotNull(
                new LabProperties(
                        false,
                        -99L,
                        new LabProperties.Engine(
                                "http://operator:secret@localhost:9090?x=1#y",
                                null,
                                null,
                                null,
                                false,
                                0L,
                                0,
                                0)));
    }

    @Test
    void theShippedConfigurationBindsAndWiresTheAdapterWithTheLabTurnedOn() {
        wiring()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "ragbrain.lab.enabled=true", "ragbrain.lab.engine.dev-auth=true")
                .run(
                        context -> {
                            assertEquals(null, context.getStartupFailure());
                            LabProperties bound = context.getBean(LabProperties.class);

                            assertTrue(bound.enabled());
                            assertEquals(26_214_400L, bound.maxUploadBytes());
                            assertEquals(
                                    "http://localhost:9090", bound.engine().normalizedBaseUrl());
                            assertTrue(bound.engine().devAuth());
                            // The shipped defaults leave both secrets blank, which must bind as
                            // absent rather than as an empty credential.
                            assertEquals(null, bound.engine().orgId());
                            assertFalse(bound.engine().hasBearerToken());
                            assertEquals(8_388_608L, bound.engine().maxEnvelopeBytes());
                            assertEquals(5_000, bound.engine().connectTimeoutMs());
                            assertEquals(30_000, bound.engine().readTimeoutMs());
                            assertNotNull(context.getBean(DocumentEngineClient.class));
                        });
    }

    @Test
    void theShippedConfigurationDefaultsTheLabOffAndWiresNothing() {
        wiring()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(
                        context -> {
                            assertEquals(null, context.getStartupFailure());
                            assertFalse(context.getBean(LabProperties.class).enabled());
                            assertEquals(
                                    0,
                                    context.getBeanNamesForType(DocumentEngineClient.class).length);
                        });
    }

    @Test
    void theGlobalServletMultipartLimitsStayAtTwentyFiveAndTwentySixMegabytes() throws IOException {
        String yml =
                Files.readString(Path.of("src/main/resources/application.yml"))
                        .lines()
                        .map(String::strip)
                        .collect(Collectors.joining("\n"));

        assertTrue(yml.contains("max-file-size: 25MB"), "spring multipart file ceiling changed");
        assertTrue(yml.contains("max-request-size: 26MB"), "spring multipart request ceiling changed");
    }

    // ---------------------------------------------------------------- credential containment

    @Test
    void configurationToStringNeverRevealsTheBearerToken() {
        LabProperties properties =
                new LabProperties(
                        true,
                        26_214_400L,
                        new LabProperties.Engine(
                                "https://engine.example.invalid",
                                null,
                                BEARER,
                                null,
                                false,
                                8_388_608L,
                                2_000,
                                5_000));

        assertFalse(properties.toString().contains(BEARER));
        assertFalse(properties.engine().toString().contains(BEARER));
        assertTrue(properties.engine().toString().contains("bearerToken=<redacted>"));
    }

    @Test
    void theAdapterToStringNeverRevealsTheBearerToken() {
        LabProperties properties =
                new LabProperties(
                        true,
                        26_214_400L,
                        new LabProperties.Engine(
                                "https://engine.example.invalid",
                                null,
                                BEARER,
                                null,
                                false,
                                8_388_608L,
                                2_000,
                                5_000));

        HttpDocumentEngineClient client = new HttpDocumentEngineClient(properties);

        assertFalse(client.toString().contains(BEARER));
    }

    @Test
    void everyFailureCodeCarriesNoTextBeyondItsOwnName() {
        for (DocumentEngineFailure.Code code : DocumentEngineFailure.Code.values()) {
            DocumentEngineFailure failure = new DocumentEngineFailure(code, 503);

            assertEquals(code.name(), failure.getMessage());
            assertEquals(code, failure.code());
            assertEquals(503, failure.httpStatus());
            assertFalse(failure.toString().contains(BEARER));
            assertTrue(failure.toString().contains(code.name()));
            assertEquals(null, failure.getCause(), "a cause could smuggle a URI or provider text");
        }
    }

    // ---------------------------------------------------------------- port shape

    @Test
    void theUploadPortCarriesNoBytesFilenameOrDeclaredMimeType() {
        RecordComponent[] components =
                DocumentEngineClient.EngineUpload.class.getRecordComponents();
        String shape =
                Arrays.stream(components)
                        .map(component -> component.getType().getName() + " " + component.getName())
                        .collect(Collectors.joining(", "));

        assertTrue(
                Arrays.stream(components).noneMatch(c -> c.getType() == byte[].class),
                "an upload record holding byte[] would buffer the document: " + shape);
        assertTrue(
                Arrays.stream(components)
                        .noneMatch(
                                c ->
                                        c.getName().toLowerCase().contains("name")
                                                || c.getName().toLowerCase().contains("mime")
                                                || c.getName().toLowerCase().contains("type")),
                "the browser filename and declared MIME type must never reach the port: " + shape);
    }

    @Test
    void theRegisteredSourceRecordNeverCarriesTheOriginalFilename() {
        assertTrue(
                Stream.of(DocumentEngineClient.RegisteredSource.class.getRecordComponents())
                        .noneMatch(c -> c.getName().toLowerCase().contains("filename")),
                "the engine's originalFilename must be dropped, not modelled");
    }

    // ---------------------------------------------------------------- registration over HTTP

    private static final String KEY = "lab-upload-key-0001";
    private static final byte[] CONTENT = "%PDF-1.7 synthetic-lab-fixture".getBytes(StandardCharsets.UTF_8);

    private static String uploadJson() {
        return "{\"packageId\":\""
                + PKG
                + "\",\"jobId\":\""
                + JOB
                + "\",\"files\":[{\"id\":\""
                + SRC
                + "\",\"originalFilename\":\"synthetic-original-name.pdf\","
                + "\"contentType\":\"application/pdf\",\"sizeBytes\":"
                + CONTENT.length
                + ",\"sha256\":\""
                + SRC_SHA
                + "\",\"pageCount\":2}],"
                + "\"warnings\":[{\"shaPrefix\":\"a1a1a1a1\"}]}";
    }

    @Test
    void registrationPostsOneStreamedMultipartFileWithTheLocalDevAuthHeaders() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 202, uploadJson()));
            CountingSource source = new CountingSource(CONTENT);

            DocumentEngineClient.UploadRegistration registration =
                    new HttpDocumentEngineClient(devAuth(stub.baseUrl()))
                            .register(
                                    new DocumentEngineClient.EngineUpload(CONTENT.length, source),
                                    KEY);

            Recorded recorded = stub.only();
            assertEquals("POST", recorded.method());
            assertEquals("/v1/packages", recorded.path());
            assertEquals(KEY, recorded.header("Idempotency-Key"));
            assertEquals(ENGINE_ORG.toString(), recorded.header("X-Dev-Org"));
            assertEquals("ADMIN", recorded.header("X-Dev-Role"));
            assertEquals(null, recorded.header("Authorization"));
            assertTrue(
                    recorded.header("Content-Type").startsWith("multipart/form-data; boundary="),
                    "actual: " + recorded.header("Content-Type"));
            assertEquals(
                    "chunked",
                    recorded.header("Transfer-Encoding"),
                    "a declared Content-Length would mean the adapter buffered the document");

            String body = new String(recorded.body(), StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("name=\"files\""), "engine reads the part named files");
            assertTrue(body.contains("filename=\"upload.bin\""));
            assertTrue(body.contains("Content-Type: application/octet-stream"));
            assertTrue(body.contains(new String(CONTENT, StandardCharsets.ISO_8859_1)));
            assertEquals(1, source.opened(), "the resource must be streamed exactly once");

            assertEquals(PKG, registration.packageId());
            assertEquals(JOB, registration.jobId());
            assertEquals(1, registration.sources().size());
            assertEquals(SRC, registration.sources().get(0).id());
            assertEquals(SRC_SHA, registration.sources().get(0).contentSha256());
            assertEquals(CONTENT.length, registration.sources().get(0).sizeBytes());
            assertEquals(2, registration.sources().get(0).pageCount());
            assertEquals(List.of("a1a1a1a1"), registration.duplicateShaPrefixes());
            assertFalse(
                    registration.toString().contains("synthetic-original-name"),
                    "the engine's originalFilename must be dropped, never retained");

            // Nothing beyond this allowlist may leave for the engine. A future edit that forwards
            // a caller-supplied header — an org, a role, an auth override — fails here.
            Set<String> unexpected = new TreeSet<>(recorded.headers().keySet());
            unexpected.removeAll(
                    Set.of(
                            "host",
                            "user-agent",
                            "connection",
                            "transfer-encoding",
                            "content-length",
                            "accept",
                            "content-type",
                            "idempotency-key",
                            "x-dev-org",
                            "x-dev-role"));
            assertTrue(unexpected.isEmpty(), "unexpected outbound headers: " + unexpected);
        }
    }

    @Test
    void localDevAuthOmitsTheOrgHeaderWhenNoOrganizationIsConfigured() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 202, uploadJson()));
            LabProperties noOrg =
                    new LabProperties(
                            true,
                            26_214_400L,
                            new LabProperties.Engine(
                                    stub.baseUrl(), null, null, null, true, 8_388_608L, 2_000, 5_000));

            new HttpDocumentEngineClient(noOrg)
                    .register(
                            new DocumentEngineClient.EngineUpload(
                                    CONTENT.length, new CountingSource(CONTENT)),
                            KEY);

            Recorded recorded = stub.only();
            assertEquals(null, recorded.header("X-Dev-Org"), "X-Dev-Org is optional");
            assertEquals("ADMIN", recorded.header("X-Dev-Role"), "X-Dev-Role is backend-fixed");
        }
    }

    @Test
    void registrationUsesTheBearerTokenAndNeverTheDevHeadersInDeploymentMode() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 202, uploadJson()));

            new HttpDocumentEngineClient(bearer(stub.baseUrl()))
                    .register(
                            new DocumentEngineClient.EngineUpload(
                                    CONTENT.length, new CountingSource(CONTENT)),
                            KEY);

            Recorded recorded = stub.only();
            assertEquals("Bearer " + BEARER, recorded.header("Authorization"));
            assertEquals(null, recorded.header("X-Dev-Org"));
            assertEquals(null, recorded.header("X-Dev-Role"));
        }
    }

    @Test
    void registrationPresentsTheEnginesServiceToServiceHeaderAndNothingElse() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 202, uploadJson()));

            new HttpDocumentEngineClient(apiKey(stub.baseUrl()))
                    .register(
                            new DocumentEngineClient.EngineUpload(
                                    CONTENT.length, new CountingSource(CONTENT)),
                            KEY);

            Recorded recorded = stub.only();
            // Spelled exactly as the engine's ApiKeyAuthFilter matches it. A near-miss header
            // authenticates nothing while every gate still reports the deployment configured.
            assertEquals(API_KEY, recorded.header("X-DocEngine-Api-Key"));
            // The key carries its own org and scopes, so this side asserts no role and no tenant,
            // and never doubles up with the operator path.
            assertEquals(null, recorded.header("Authorization"));
            assertEquals(null, recorded.header("X-Dev-Org"));
            assertEquals(null, recorded.header("X-Dev-Role"));
            assertEquals(null, recorded.header("X-DocEngine-Acting-User"));
        }
    }

    @Test
    void anApiKeyIsNeverPrintedByConfigurationsToString() {
        LabProperties properties = apiKey("https://engine.example.invalid");

        assertFalse(properties.toString().contains(API_KEY));
        assertFalse(properties.engine().toString().contains(API_KEY));
    }

    @Test
    void exactlyOneEngineAuthModeIsConfigurable() {
        // Two credentials must fail as loudly as none: with three modes the adapter would
        // otherwise pick one by the order of its if-chain, and which one is not obvious.
        assertRefusesEngine(BEARER, API_KEY, false, "a bearer token and an api key together");
        assertRefusesEngine(null, API_KEY, true, "dev-auth and an api key together");
        assertRefusesEngine(BEARER, API_KEY, true, "all three modes at once");
        assertRefusesEngine(null, "   ", false, "a blank api key is not a configured mode");
    }

    private static void assertRefusesEngine(
            String bearer, String apiKey, boolean devAuth, String why) {
        IllegalStateException refused =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                enabled(
                                        new LabProperties.Engine(
                                                "http://localhost:9090",
                                                devAuth ? ENGINE_ORG : null,
                                                bearer,
                                                apiKey,
                                                devAuth,
                                                8_388_608L,
                                                2_000,
                                                5_000)),
                        why + " must fail closed");
        // Property keys only. A message that echoed a value would put a credential in the logs
        // of every deployment that mis-set one.
        assertTrue(refused.getMessage().contains("ragbrain.lab.engine.api-key"));
        assertFalse(refused.getMessage().contains(API_KEY));
        assertFalse(refused.getMessage().contains(BEARER));
    }

    /**
     * The real call site: a request-scoped {@code MultipartFile} forwarded through its resource.
     * {@code getBytes()} and both {@code transferTo} overloads throw, so a passing test proves the
     * adapter neither buffers the document on the heap nor spools it to an application temp file.
     */
    @Test
    void aRequestScopedMultipartFileIsForwardedWithoutGetBytesOrAnApplicationTempFile()
            throws Exception {
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 202, uploadJson()));
            NoBufferingMultipartFile file = new NoBufferingMultipartFile(CONTENT);

            new HttpDocumentEngineClient(devAuth(stub.baseUrl()))
                    .register(
                            new DocumentEngineClient.EngineUpload(file.getSize(), file.getResource()),
                            KEY);

            String body = new String(stub.only().body(), StandardCharsets.ISO_8859_1);
            assertTrue(body.contains(new String(CONTENT, StandardCharsets.ISO_8859_1)));
            assertFalse(
                    body.contains("synthetic-browser-name"),
                    "the browser filename must never be forwarded to the engine");
            assertFalse(
                    body.contains("text/html"),
                    "the browser's declared MIME type must never be forwarded to the engine");
        }
    }

    @Test
    void anEmptyUploadIsRejectedBeforeAnyEngineCall() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            DocumentEngineFailure failure =
                    assertThrows(
                            DocumentEngineFailure.class,
                            () ->
                                    client.register(
                                            new DocumentEngineClient.EngineUpload(
                                                    0L, new CountingSource(new byte[0])),
                                            KEY));

            assertEquals(DocumentEngineFailure.Code.UPLOAD_EMPTY, failure.code());
            assertEquals(0, stub.received().size(), "no engine call may be made");
        }
    }

    @Test
    void anOversizedUploadIsRejectedBeforeAnyEngineCall() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            DocumentEngineFailure failure =
                    assertThrows(
                            DocumentEngineFailure.class,
                            () ->
                                    client.register(
                                            new DocumentEngineClient.EngineUpload(
                                                    LabProperties.SERVLET_MULTIPART_MAX_FILE_BYTES + 1,
                                                    new CountingSource(CONTENT)),
                                            KEY));

            assertEquals(DocumentEngineFailure.Code.UPLOAD_TOO_LARGE, failure.code());
            assertEquals(0, stub.received().size(), "no engine call may be made");
        }
    }

    @Test
    void anUnusableIdempotencyKeyIsRejectedBeforeAnyEngineCall() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            for (String key :
                    Arrays.asList(
                            null,
                            "",
                            "   ",
                            "key with space",
                            "key\r\nX-Dev-Role: ADMIN",
                            "k".repeat(201))) {
                DocumentEngineFailure failure =
                        assertThrows(
                                DocumentEngineFailure.class,
                                () ->
                                        client.register(
                                                new DocumentEngineClient.EngineUpload(
                                                        CONTENT.length, new CountingSource(CONTENT)),
                                                key),
                                "key must be refused: " + key);
                assertEquals(DocumentEngineFailure.Code.IDEMPOTENCY_KEY_INVALID, failure.code());
            }
            assertEquals(0, stub.received().size(), "no engine call may be made");
        }
    }

    // ---------------------------------------------------------------- job and metadata reads

    @Test
    void theJobReadTargetsTheExactEngineJobPathAndIgnoresOperationalExtras() throws Exception {
        String json =
                "{\"id\":\""
                        + JOB
                        + "\",\"packageId\":\""
                        + PKG
                        + "\",\"status\":\"COMPLETED\",\"currentStage\":\"FINALIZING\","
                        + "\"attempt\":1,\"createdAt\":\"2026-08-17T09:00:00Z\","
                        + "\"startedAt\":\"2026-08-17T09:00:01Z\","
                        + "\"finishedAt\":\"2026-08-17T09:00:09Z\","
                        + "\"stages\":[{\"stage\":\"VALIDATING\",\"status\":\"SUCCEEDED\","
                        + "\"attempt\":1,\"skipReason\":null,\"errorCode\":null,"
                        + "\"durationMs\":12}]}";
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 200, json));

            DocumentEngineClient.JobSnapshot snapshot =
                    new HttpDocumentEngineClient(devAuth(stub.baseUrl())).job(JOB);

            Recorded recorded = stub.only();
            assertEquals("GET", recorded.method());
            assertEquals("/v1/jobs/" + JOB, recorded.path());
            assertEquals("application/json", recorded.header("Accept"));
            assertEquals(JOB, snapshot.jobId());
            assertEquals(PKG, snapshot.packageId());
            assertEquals("COMPLETED", snapshot.status());
            assertEquals("FINALIZING", snapshot.currentStage());
        }
    }

    private static final UUID DOC = UUID.fromString("77777777-7777-4777-8777-777777777770");

    private static String reviewedFieldsJson() {
        return "{\"documentId\":\"" + DOC + "\",\"documentTypeCode\":\"PAYSTUB\","
                + "\"schemaVersion\":\"2025.5a\",\"fields\":[{\"id\":\"" + UUID.randomUUID() + "\","
                + "\"fieldName\":\"currentGrossPay\",\"groupKey\":null,\"groupKind\":\"NONE\","
                + "\"textProvenance\":{\"source\":\"NATIVE\",\"ocrEngine\":null},"
                + "\"dataType\":\"MONEY\",\"displayedText\":\"4,670.69\",\"rawValue\":\"4,670.69\","
                + "\"normalized\":{\"text\":null,\"number\":4670.69,\"date\":null},"
                + "\"extractionMethod\":\"TABLE_CLUSTER\",\"extractorVersion\":\"engine/1.0.0\","
                + "\"confidence\":1.0,\"confidenceComponents\":null,"
                + "\"validationStatus\":\"NOT_VALIDATED\",\"reviewStatus\":\"NOT_REVIEWED\","
                + "\"effectiveStatus\":\"MACHINE\",\"sensitive\":false,\"evidence\":[]}]}";
    }

    @Test
    void theReviewedFieldsReadTargetsTheDocumentFieldsPathWithPlainJsonAccept() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 200, reviewedFieldsJson()));

            ReviewedFields fields =
                    new HttpDocumentEngineClient(devAuth(stub.baseUrl())).reviewedFields(DOC);

            Recorded recorded = stub.only();
            assertEquals("GET", recorded.method());
            assertEquals("/v1/documents/" + DOC + "/fields", recorded.path());
            assertEquals("application/json", recorded.header("Accept"));
            assertEquals("ADMIN", recorded.header("X-Dev-Role"));
            assertEquals(DOC, fields.documentId());
            assertEquals(1, fields.fields().size());
            assertEquals(EngineResultEnvelope.ReviewState.MACHINE,
                    fields.fields().get(0).effectiveStatus());
        }
    }

    @Test
    void aReviewedFieldsReadForADifferentDocumentIsRefused() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 200, reviewedFieldsJson()));

            DocumentEngineFailure failure = assertThrows(DocumentEngineFailure.class,
                    () -> new HttpDocumentEngineClient(devAuth(stub.baseUrl()))
                            .reviewedFields(UUID.randomUUID()));
            assertEquals(DocumentEngineFailure.Code.ENGINE_READMODEL_MALFORMED, failure.code());
        }
    }

    @Test
    void aMalformedReviewedFieldsBodyIsRefusedWithoutAValue() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 200,
                    reviewedFieldsJson().replace("\"effectiveStatus\":\"MACHINE\"",
                            "\"effectiveStatus\":\"CONFIRMED\"")));

            DocumentEngineFailure failure = assertThrows(DocumentEngineFailure.class,
                    () -> new HttpDocumentEngineClient(devAuth(stub.baseUrl())).reviewedFields(DOC));
            assertEquals(DocumentEngineFailure.Code.ENGINE_READMODEL_MALFORMED, failure.code());
            assertFalse(failure.getMessage().contains("4,670.69"));
        }
    }

    @Test
    void aNon2xxReviewedFieldsAnswerIsUnavailableWithItsStatus() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 404, "{\"error\":\"not found\"}"));

            DocumentEngineFailure failure = assertThrows(DocumentEngineFailure.class,
                    () -> new HttpDocumentEngineClient(devAuth(stub.baseUrl())).reviewedFields(DOC));
            assertEquals(DocumentEngineFailure.Code.ENGINE_READMODEL_UNAVAILABLE, failure.code());
            assertEquals(404, failure.httpStatus());
        }
    }

    @Test
    void anOversizedReviewedFieldsAnswerIsUnavailableNotAnEnvelopeFailure() throws Exception {
        // Same technique as aDeclaredLengthBeyondTheCeilingFailsWithoutDownloadingTheBody: a raw
        // socket declares an enormous Content-Length and writes no body, so the only way this call
        // can return is by refusing on the header before reading a single content byte.
        String raw =
                "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: 50000000\r\n"
                        + "\r\n";
        try (RawStub stub = new RawStub(raw)) {
            HttpDocumentEngineClient client =
                    new HttpDocumentEngineClient(withEnvelopeCeiling(stub.baseUrl(), 8_192L));

            DocumentEngineFailure failure =
                    assertThrows(DocumentEngineFailure.class, () -> client.reviewedFields(DOC));

            assertEquals(DocumentEngineFailure.Code.ENGINE_READMODEL_UNAVAILABLE, failure.code());
        }
    }

    @Test
    void aReviewedFieldsAnswerWithTheWrongMediaTypeIsUnavailable() throws Exception {
        // A 200 with a non-JSON Content-Type (a login page, a proxy error page, anything not
        // application/json) must fail the same way a non-2xx status would, before the body is
        // ever handed to the strict parser.
        String html = "<html><body>not json</body></html>";
        String raw =
                "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html\r\n"
                        + "Content-Length: "
                        + html.getBytes(StandardCharsets.ISO_8859_1).length
                        + "\r\n"
                        + "\r\n"
                        + html;
        try (RawStub stub = new RawStub(raw)) {
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            DocumentEngineFailure failure =
                    assertThrows(DocumentEngineFailure.class, () -> client.reviewedFields(DOC));

            assertEquals(DocumentEngineFailure.Code.ENGINE_READMODEL_UNAVAILABLE, failure.code());
            assertEquals(200, failure.httpStatus());
        }
    }

    private static String descriptorJson(int revision, byte[] envelope, String createdAt) {
        return "{\"revision\":"
                + revision
                + ",\"processingJobId\":\""
                + JOB
                + "\",\"parseGeneration\":"
                + revision
                + ",\"materializedJobAttempt\":1,\"envelopeSchemaVersion\":\"1.0.0\","
                + "\"sourceSetSha256\":\""
                + SET_SHA
                + "\",\"provenanceSha256\":\""
                + "c3".repeat(32)
                + "\",\"envelopeSha256\":\""
                + sha256Hex(envelope)
                + "\",\"envelopeSizeBytes\":"
                + envelope.length
                + ",\"reuseEligibility\":\"PARSE_ONCE_CURRENT_PACKAGE\",\"createdAt\":\""
                + createdAt
                + "\"}";
    }

    @Test
    void theMetadataHistoryReadsThePluralEngineResultsPathInAscendingRevisionOrder()
            throws Exception {
        byte[] first = envelopeBytes(PKG, 1);
        byte[] second = envelopeBytes(PKG, 2);
        String json =
                "["
                        + descriptorJson(1, first, "2026-08-17T09:00:00Z")
                        + ","
                        + descriptorJson(2, second, "2026-08-17T10:30:00Z")
                        + "]";
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 200, json));

            List<DocumentEngineClient.RevisionDescriptor> history =
                    new HttpDocumentEngineClient(devAuth(stub.baseUrl())).revisionHistory(PKG);

            Recorded recorded = stub.only();
            assertEquals("GET", recorded.method());
            assertEquals("/v1/packages/" + PKG + "/engine-results", recorded.path());
            assertEquals(2, history.size());
            assertEquals(1, history.get(0).revision());
            assertEquals(2, history.get(1).revision());
            assertEquals(JOB, history.get(0).processingJobId());
            assertEquals(1, history.get(0).materializedJobAttempt());
            assertEquals("1.0.0", history.get(0).envelopeSchemaVersion());
            assertEquals(SET_SHA, history.get(0).sourceSetSha256());
            assertEquals("c3".repeat(32), history.get(0).provenanceSha256());
            assertEquals(sha256Hex(first), history.get(0).envelopeSha256());
            assertEquals(first.length, history.get(0).envelopeSizeBytes());
            assertEquals("PARSE_ONCE_CURRENT_PACKAGE", history.get(0).reuseEligibility());
            assertEquals(Instant.parse("2026-08-17T09:00:00Z"), history.get(0).createdAt());
        }
    }

    @Test
    void aMetadataMemberOutsideThePinnedDescriptorFailsAsAMalformedResponse() throws Exception {
        byte[] envelope = envelopeBytes(PKG, 1);
        String json =
                "["
                        + descriptorJson(1, envelope, "2026-08-17T09:00:00Z")
                                .replace("{", "{\"storageKey\":\"engine-results/x.json\",")
                        + "]";
        try (EngineStub stub = new EngineStub()) {
            stub.answering(exchange -> respondJson(exchange, 200, json));
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            DocumentEngineFailure failure =
                    assertThrows(DocumentEngineFailure.class, () -> client.revisionHistory(PKG));

            assertEquals(DocumentEngineFailure.Code.ENGINE_RESPONSE_MALFORMED, failure.code());
        }
    }

    // ---------------------------------------------------------------- exact content reads

    @Test
    void theCurrentContentReadVerifiesTheQuotedEtagLengthAndDigestThenParses() throws Exception {
        byte[] envelope = envelopeBytes(PKG, 4);
        try (EngineStub stub = new EngineStub()) {
            stub.answering(content(envelope, CANONICAL_MEDIA_TYPE, quoted(envelope), false));

            DocumentEngineClient.VerifiedEnvelope verified =
                    new HttpDocumentEngineClient(devAuth(stub.baseUrl())).currentEnvelope(PKG);

            Recorded recorded = stub.only();
            assertEquals("GET", recorded.method());
            assertEquals("/v1/packages/" + PKG + "/engine-result", recorded.path());
            assertEquals(CANONICAL_MEDIA_TYPE, recorded.header("Accept"));
            assertEquals(sha256Hex(envelope), verified.artifact().sha256());
            assertEquals(envelope.length, verified.artifact().byteCount());
            assertArrayEquals(envelope, verified.artifact().bytes());
            assertEquals(PKG, verified.envelope().packageId());
            assertEquals(4, verified.revision());
            assertEquals(4, verified.envelope().generation().packageRevision());
        }
    }

    @Test
    void theHistoricalContentReadPinsTheRequestedRevision() throws Exception {
        byte[] envelope = envelopeBytes(PKG, 3);
        try (EngineStub stub = new EngineStub()) {
            stub.answering(content(envelope, CANONICAL_MEDIA_TYPE, quoted(envelope), false));

            DocumentEngineClient.VerifiedEnvelope verified =
                    new HttpDocumentEngineClient(devAuth(stub.baseUrl())).envelopeRevision(PKG, 3);

            assertEquals("/v1/packages/" + PKG + "/engine-results/3", stub.only().path());
            assertEquals(3, verified.revision());
        }
    }

    @Test
    void aDescriptorAndItsFetchedRevisionAgreeOnEveryPinnedIdentity() throws Exception {
        byte[] envelope = envelopeBytes(PKG, 1);
        try (EngineStub stub = new EngineStub()) {
            stub.answering(
                    exchange -> {
                        if (exchange.getRequestURI().getPath().endsWith("/engine-results")) {
                            respondJson(
                                    exchange,
                                    200,
                                    "[" + descriptorJson(1, envelope, "2026-08-17T09:00:00Z") + "]");
                        } else {
                            content(envelope, CANONICAL_MEDIA_TYPE, quoted(envelope), false)
                                    .respond(exchange);
                        }
                    });
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            DocumentEngineClient.RevisionDescriptor descriptor = client.revisionHistory(PKG).get(0);
            DocumentEngineClient.VerifiedEnvelope fetched = client.envelopeRevision(PKG, 1);

            assertTrue(descriptor.describes(fetched));
        }
    }

    @Test
    void anEnvelopeDeclaringAnotherPackageOrRevisionIsRefused() throws Exception {
        UUID otherPackage = UUID.fromString("1a1a1a1a-1a1a-4a1a-8a1a-1a1a1a1a1a1a");
        byte[] foreign = envelopeBytes(otherPackage, 1);
        try (EngineStub stub = new EngineStub()) {
            stub.answering(content(foreign, CANONICAL_MEDIA_TYPE, quoted(foreign), false));
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            assertEquals(
                    DocumentEngineFailure.Code.ENGINE_PACKAGE_MISMATCH,
                    assertThrows(DocumentEngineFailure.class, () -> client.currentEnvelope(PKG))
                            .code());
        }
        byte[] wrongRevision = envelopeBytes(PKG, 1);
        try (EngineStub stub = new EngineStub()) {
            stub.answering(content(wrongRevision, CANONICAL_MEDIA_TYPE, quoted(wrongRevision), false));
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            assertEquals(
                    DocumentEngineFailure.Code.ENGINE_REVISION_MISMATCH,
                    assertThrows(
                                    DocumentEngineFailure.class,
                                    () -> client.envelopeRevision(PKG, 7))
                            .code());
        }
    }

    // ---------------------------------------------------------------- transport failures

    @Test
    void aRedirectIsRefusedAndItsTargetIsNeverRequested() throws Exception {
        try (EngineStub stub = new EngineStub()) {
            stub.answering(
                    exchange -> {
                        exchange.getResponseHeaders().set("Location", "/v1/packages/other/engine-result");
                        exchange.sendResponseHeaders(302, -1);
                    });
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            DocumentEngineFailure failure =
                    assertThrows(DocumentEngineFailure.class, () -> client.currentEnvelope(PKG));

            assertEquals(DocumentEngineFailure.Code.ENGINE_REDIRECT_REFUSED, failure.code());
            assertEquals(302, failure.httpStatus());
            assertEquals(1, stub.received().size(), "the redirect target must never be fetched");
        }
    }

    @Test
    void aNonSuccessStatusFailsWithItsStatusAndNothingElse() throws Exception {
        for (int status : new int[] {400, 401, 403, 404, 500, 503}) {
            try (EngineStub stub = new EngineStub()) {
                stub.answering(
                        exchange ->
                                respondJson(
                                        exchange,
                                        status,
                                        "{\"code\":\"NOT_FOUND\",\"detail\":\"engine text\"}"));
                HttpDocumentEngineClient client =
                        new HttpDocumentEngineClient(bearer(stub.baseUrl()));

                DocumentEngineFailure failure =
                        assertThrows(DocumentEngineFailure.class, () -> client.currentEnvelope(PKG));

                assertEquals(DocumentEngineFailure.Code.ENGINE_STATUS_UNEXPECTED, failure.code());
                assertEquals(status, failure.httpStatus());
                assertFalse(failure.toString().contains("engine text"));
                assertFalse(failure.getMessage().contains("engine text"));
                assertFalse(failure.toString().contains(BEARER));
            }
        }
    }

    @Test
    void anUnexpectedContentTypeFailsBeforeParsing() throws Exception {
        byte[] envelope = envelopeBytes(PKG, 1);
        try (EngineStub stub = new EngineStub()) {
            stub.answering(content(envelope, "application/json", quoted(envelope), false));
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            assertEquals(
                    DocumentEngineFailure.Code.ENGINE_CONTENT_TYPE_UNEXPECTED,
                    assertThrows(DocumentEngineFailure.class, () -> client.currentEnvelope(PKG))
                            .code());
        }
    }

    @Test
    void aMissingOrMalformedEtagFailsBeforeParsing() throws Exception {
        byte[] envelope = envelopeBytes(PKG, 1);
        assertEquals(
                DocumentEngineFailure.Code.ENGINE_ETAG_MISSING, etagFailure(envelope, null).code());
        for (String etag :
                Arrays.asList(
                        sha256Hex(envelope),
                        "\"" + sha256Hex(envelope).toUpperCase(Locale.ROOT) + "\"",
                        "W/\"" + sha256Hex(envelope) + "\"",
                        "\"deadbeef\"",
                        "\"\"")) {
            assertEquals(
                    DocumentEngineFailure.Code.ENGINE_ETAG_MALFORMED,
                    etagFailure(envelope, etag).code(),
                    "must be refused: " + etag);
        }
    }

    private DocumentEngineFailure etagFailure(byte[] envelope, String etag) throws Exception {
        try (EngineStub stub = new EngineStub()) {
            stub.answering(content(envelope, CANONICAL_MEDIA_TYPE, etag, false));
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));
            return assertThrows(DocumentEngineFailure.class, () -> client.currentEnvelope(PKG));
        }
    }

    @Test
    void aMissingContentLengthFailsBeforeParsing() throws Exception {
        byte[] envelope = envelopeBytes(PKG, 1);
        try (EngineStub stub = new EngineStub()) {
            stub.answering(content(envelope, CANONICAL_MEDIA_TYPE, quoted(envelope), true));
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            assertEquals(
                    DocumentEngineFailure.Code.ENGINE_CONTENT_LENGTH_MISSING,
                    assertThrows(DocumentEngineFailure.class, () -> client.currentEnvelope(PKG))
                            .code());
        }
    }

    @Test
    void aDigestThatDisagreesWithTheEtagFailsBeforeTheParserSeesTheBytes() throws Exception {
        byte[] notEvenJson = "not-an-envelope".getBytes(StandardCharsets.UTF_8);
        try (EngineStub stub = new EngineStub()) {
            stub.answering(
                    content(notEvenJson, CANONICAL_MEDIA_TYPE, "\"" + "d4".repeat(32) + "\"", false));
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            DocumentEngineFailure failure =
                    assertThrows(DocumentEngineFailure.class, () -> client.currentEnvelope(PKG));

            assertEquals(DocumentEngineFailure.Code.ENGINE_DIGEST_MISMATCH, failure.code());
        }
    }

    @Test
    void aVerifiedResponseCarryingAMalformedEnvelopeFailsInTheStrictParserInstead() throws Exception {
        byte[] wrongVersion =
                new String(envelopeBytes(PKG, 1), StandardCharsets.UTF_8)
                        .replace("\"envelopeVersion\":\"1.0.0\"", "\"envelopeVersion\":\"2.0.0\"")
                        .getBytes(StandardCharsets.UTF_8);
        try (EngineStub stub = new EngineStub()) {
            stub.answering(content(wrongVersion, CANONICAL_MEDIA_TYPE, quoted(wrongVersion), false));
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            LabContractException failure =
                    assertThrows(LabContractException.class, () -> client.currentEnvelope(PKG));

            assertEquals(
                    LabContractException.Code.ENVELOPE_VERSION_UNSUPPORTED,
                    failure.code(),
                    "header verification passed, so the strict parser is what must reject this");
        }
    }

    @Test
    void aDeclaredLengthBeyondTheCeilingFailsWithoutDownloadingTheBody() throws Exception {
        // The raw stub declares an enormous Content-Length and writes no body at all: the only way
        // this call can return is by refusing on the header before reading a single content byte.
        String raw =
                "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: "
                        + CANONICAL_MEDIA_TYPE
                        + "\r\n"
                        + "ETag: \""
                        + "e5".repeat(32)
                        + "\"\r\n"
                        + "Content-Length: 50000000\r\n"
                        + "\r\n";
        try (RawStub stub = new RawStub(raw)) {
            HttpDocumentEngineClient client =
                    new HttpDocumentEngineClient(withEnvelopeCeiling(stub.baseUrl(), 8_192L));

            DocumentEngineFailure failure =
                    assertThrows(DocumentEngineFailure.class, () -> client.currentEnvelope(PKG));

            assertEquals(DocumentEngineFailure.Code.ENGINE_ENVELOPE_TOO_LARGE, failure.code());
        }
    }

    @Test
    void aTruncatedBodyFailsAsARequestFailureRatherThanReachingTheParser() throws Exception {
        byte[] envelope = envelopeBytes(PKG, 1);
        String raw =
                "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: "
                        + CANONICAL_MEDIA_TYPE
                        + "\r\n"
                        + "ETag: "
                        + quoted(envelope)
                        + "\r\n"
                        + "Content-Length: "
                        + envelope.length
                        + "\r\n"
                        + "Connection: close\r\n"
                        + "\r\n"
                        + new String(envelope, StandardCharsets.ISO_8859_1).substring(0, 40);
        try (RawStub stub = new RawStub(raw)) {
            HttpDocumentEngineClient client = new HttpDocumentEngineClient(devAuth(stub.baseUrl()));

            DocumentEngineFailure failure =
                    assertThrows(DocumentEngineFailure.class, () -> client.currentEnvelope(PKG));

            assertEquals(DocumentEngineFailure.Code.ENGINE_REQUEST_FAILED, failure.code());
        }
    }

    @Test
    void aReadTimeoutFailsWithTheBoundedTimeoutCode() throws Exception {
        byte[] envelope = envelopeBytes(PKG, 1);
        try (EngineStub stub = new EngineStub()) {
            stub.answering(
                    exchange -> {
                        try {
                            Thread.sleep(1_500);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        content(envelope, CANONICAL_MEDIA_TYPE, quoted(envelope), false)
                                .respond(exchange);
                    });
            HttpDocumentEngineClient client =
                    new HttpDocumentEngineClient(withReadTimeout(stub.baseUrl(), 250));

            DocumentEngineFailure failure =
                    assertThrows(DocumentEngineFailure.class, () -> client.currentEnvelope(PKG));

            assertEquals(DocumentEngineFailure.Code.ENGINE_TIMEOUT, failure.code());
        }
    }

    @Test
    void anUnreachableEngineFailsWithoutNamingItsUriOrCredential() throws Exception {
        HttpDocumentEngineClient client =
                new HttpDocumentEngineClient(bearer("http://127.0.0.1:1"));

        DocumentEngineFailure failure =
                assertThrows(DocumentEngineFailure.class, () -> client.currentEnvelope(PKG));

        assertEquals(DocumentEngineFailure.Code.ENGINE_REQUEST_FAILED, failure.code());
        assertFalse(failure.toString().contains("127.0.0.1"));
        assertFalse(failure.toString().contains(BEARER));
        assertEquals(null, failure.getCause());
    }

    // ------------------------------------------------- exact-content verification, branch by branch

    /**
     * The received-bytes-versus-declared-length branch is unreachable through a conforming HTTP
     * client (a short body ends the connection, a long one is truncated at the declared length), so
     * the invariant is pinned directly on the pure verifier the transport path delegates to.
     */
    @Test
    void theExactContentVerifierRefusesEveryDisagreementBetweenHeadersAndBytes() {
        byte[] envelope = envelopeBytes(PKG, 1);
        String etag = quoted(envelope);
        String length = Integer.toString(envelope.length);
        long ceiling = 8_388_608L;

        EngineArtifactDescriptor verified =
                HttpDocumentEngineClient.verifyExactContent(
                        CANONICAL_MEDIA_TYPE, etag, length, envelope, ceiling);
        assertEquals(sha256Hex(envelope), verified.sha256());
        assertEquals(envelope.length, verified.byteCount());

        assertEquals(
                DocumentEngineFailure.Code.ENGINE_CONTENT_LENGTH_MISMATCH,
                verifierFailure(CANONICAL_MEDIA_TYPE, etag, "7", envelope, ceiling));
        assertEquals(
                DocumentEngineFailure.Code.ENGINE_CONTENT_LENGTH_MISSING,
                verifierFailure(CANONICAL_MEDIA_TYPE, etag, null, envelope, ceiling));
        assertEquals(
                DocumentEngineFailure.Code.ENGINE_CONTENT_LENGTH_MALFORMED,
                verifierFailure(CANONICAL_MEDIA_TYPE, etag, "not-a-number", envelope, ceiling));
        assertEquals(
                DocumentEngineFailure.Code.ENGINE_CONTENT_LENGTH_MALFORMED,
                verifierFailure(CANONICAL_MEDIA_TYPE, etag, "-1", envelope, ceiling));
        assertEquals(
                DocumentEngineFailure.Code.ENGINE_ENVELOPE_TOO_LARGE,
                verifierFailure(CANONICAL_MEDIA_TYPE, etag, length, envelope, 16L));
        assertEquals(
                DocumentEngineFailure.Code.ENGINE_DIGEST_MISMATCH,
                verifierFailure(
                        CANONICAL_MEDIA_TYPE, "\"" + "f6".repeat(32) + "\"", length, envelope, ceiling));
        assertEquals(
                DocumentEngineFailure.Code.ENGINE_CONTENT_TYPE_UNEXPECTED,
                verifierFailure(null, etag, length, envelope, ceiling));
    }

    @Test
    void theExactContentVerifierAcceptsTheEngineMediaTypeRegardlessOfHeaderSpacing() {
        byte[] envelope = envelopeBytes(PKG, 1);
        for (String contentType :
                Arrays.asList(
                        "application/vnd.pragmaticds.document-engine-result+json;version=1",
                        "application/vnd.pragmaticds.document-engine-result+json; version=1",
                        "APPLICATION/VND.PRAGMATICDS.DOCUMENT-ENGINE-RESULT+JSON;VERSION=1")) {
            assertNotNull(
                    HttpDocumentEngineClient.verifyExactContent(
                            contentType,
                            quoted(envelope),
                            Integer.toString(envelope.length),
                            envelope,
                            8_388_608L),
                    "must be accepted: " + contentType);
        }
        for (String contentType :
                Arrays.asList(
                        "application/json",
                        "application/vnd.pragmaticds.document-engine-result+json",
                        "application/vnd.pragmaticds.document-engine-result+json;version=2",
                        "text/plain;version=1")) {
            assertEquals(
                    DocumentEngineFailure.Code.ENGINE_CONTENT_TYPE_UNEXPECTED,
                    verifierFailure(
                            contentType,
                            quoted(envelope),
                            Integer.toString(envelope.length),
                            envelope,
                            8_388_608L),
                    "must be refused: " + contentType);
        }
    }

    private static DocumentEngineFailure.Code verifierFailure(
            String contentType, String etag, String contentLength, byte[] body, long ceiling) {
        return assertThrows(
                        DocumentEngineFailure.class,
                        () ->
                                HttpDocumentEngineClient.verifyExactContent(
                                        contentType, etag, contentLength, body, ceiling))
                .code();
    }

    // ---------------------------------------------------------------- helpers

    private static final String CANONICAL_MEDIA_TYPE = EngineArtifactDescriptor.CANONICAL_MEDIA_TYPE;

    private static LabProperties devAuth(String baseUrl) {
        return new LabProperties(
                true,
                26_214_400L,
                new LabProperties.Engine(
                        baseUrl, ENGINE_ORG, null, null, true, 8_388_608L, 2_000, 5_000));
    }

    private static LabProperties bearer(String baseUrl) {
        return new LabProperties(
                true,
                26_214_400L,
                new LabProperties.Engine(baseUrl, null, BEARER, null, false, 8_388_608L, 2_000, 5_000));
    }

    private static LabProperties apiKey(String baseUrl) {
        return new LabProperties(
                true,
                26_214_400L,
                new LabProperties.Engine(baseUrl, null, null, API_KEY, false, 8_388_608L, 2_000, 5_000));
    }

    private static LabProperties withEnvelopeCeiling(String baseUrl, long ceiling) {
        return new LabProperties(
                true,
                26_214_400L,
                new LabProperties.Engine(baseUrl, ENGINE_ORG, null, null, true, ceiling, 2_000, 5_000));
    }

    private static LabProperties withReadTimeout(String baseUrl, int readTimeoutMs) {
        return new LabProperties(
                true,
                26_214_400L,
                new LabProperties.Engine(
                        baseUrl, ENGINE_ORG, null, null, true, 8_388_608L, 2_000, readTimeoutMs));
    }

    private static String quoted(byte[] bytes) {
        return "\"" + sha256Hex(bytes) + "\"";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void respondJson(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** {@code chunked} omits {@code Content-Length} the way a streaming response would. */
    private static Responder content(byte[] body, String contentType, String etag, boolean chunked) {
        return exchange -> {
            if (contentType != null) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
            }
            if (etag != null) {
                exchange.getResponseHeaders().set("ETag", etag);
            }
            exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
            exchange.getResponseBody().write(body);
        };
    }

    interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    record Recorded(
            String method, String path, String query, Map<String, List<String>> headers, byte[] body) {

        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    /** A loopback JDK {@link HttpServer} that records every request and replays one response. */
    static final class EngineStub implements AutoCloseable {

        private final HttpServer server;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final List<Recorded> received = Collections.synchronizedList(new ArrayList<>());
        private volatile Responder responder = exchange -> respondJson(exchange, 200, "{}");

        EngineStub() throws IOException {
            server =
                    HttpServer.create(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(pool);
            server.createContext(
                    "/",
                    exchange -> {
                        byte[] body = exchange.getRequestBody().readAllBytes();
                        Map<String, List<String>> headers = new LinkedHashMap<>();
                        exchange.getRequestHeaders()
                                .forEach(
                                        (name, values) ->
                                                headers.put(
                                                        name.toLowerCase(Locale.ROOT),
                                                        List.copyOf(values)));
                        received.add(
                                new Recorded(
                                        exchange.getRequestMethod(),
                                        exchange.getRequestURI().getPath(),
                                        exchange.getRequestURI().getQuery(),
                                        headers,
                                        body));
                        try {
                            responder.respond(exchange);
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
        }

        EngineStub answering(Responder responder) {
            this.responder = responder;
            return this;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<Recorded> received() {
            return List.copyOf(received);
        }

        Recorded only() {
            assertEquals(1, received.size(), "expected exactly one engine request");
            return received.get(0);
        }

        @Override
        public void close() {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    /**
     * A loopback socket that replays one byte-exact response. Needed only where {@link HttpServer}
     * refuses to emit the header shape under test — it always writes a truthful
     * {@code Content-Length}.
     */
    static final class RawStub implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread thread;

        RawStub(String rawResponse) throws IOException {
            socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread =
                    new Thread(
                            () -> {
                                try (Socket accepted = socket.accept()) {
                                    InputStream in = accepted.getInputStream();
                                    int previous = -1;
                                    int blankLines = 0;
                                    int current;
                                    while ((current = in.read()) != -1) {
                                        if (current == '\n') {
                                            if (previous == '\r' || previous == '\n') {
                                                blankLines++;
                                                if (blankLines >= 1) {
                                                    break;
                                                }
                                            }
                                        } else if (current != '\r') {
                                            blankLines = 0;
                                        }
                                        previous = current;
                                    }
                                    accepted
                                            .getOutputStream()
                                            .write(rawResponse.getBytes(StandardCharsets.ISO_8859_1));
                                    accepted.getOutputStream().flush();
                                } catch (IOException ignored) {
                                    // The client may hang up first; nothing here is under test.
                                }
                            });
            thread.setDaemon(true);
            thread.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
            thread.interrupt();
        }
    }

    /** A one-shot streamable source; a second stream would mean the adapter buffered or retried. */
    static final class CountingSource implements org.springframework.core.io.InputStreamSource {

        private final byte[] content;
        private int opened;

        CountingSource(byte[] content) {
            this.content = content;
        }

        @Override
        public InputStream getInputStream() {
            opened++;
            return new java.io.ByteArrayInputStream(content);
        }

        int opened() {
            return opened;
        }
    }

    /**
     * A request-scoped multipart file that fails the test if the adapter buffers it on the heap or
     * spools it to an application-owned temporary file.
     */
    static final class NoBufferingMultipartFile implements MultipartFile {

        private final byte[] content;

        NoBufferingMultipartFile(byte[] content) {
            this.content = content;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return "synthetic-browser-name.pdf";
        }

        @Override
        public String getContentType() {
            return "text/html";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            throw new AssertionError("getBytes() would buffer the whole document on the heap");
        }

        @Override
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(java.io.File destination) {
            throw new AssertionError("transferTo(File) would create an application temp file");
        }

        @Override
        public void transferTo(java.nio.file.Path destination) {
            throw new AssertionError("transferTo(Path) would create an application temp file");
        }
    }
}
