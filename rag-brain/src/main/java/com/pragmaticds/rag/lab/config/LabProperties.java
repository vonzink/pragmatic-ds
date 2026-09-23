package com.pragmaticds.rag.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Typed configuration for the Income Lab prototype, bound from {@code ragbrain.lab.*}.
 *
 * <p>Everything here is deployment-owned. No value on this record is reachable from a browser
 * request: the engine organization and the authentication mode are chosen once by configuration,
 * so no caller can select another tenant or downgrade authentication by sending a parameter.
 *
 * <p>Validation runs only while {@link #enabled()} is true. With the Lab off, a half-written or
 * absent {@code ragbrain.lab} section binds without complaint and ordinary RAG Brain startup is
 * unaffected — which is what the prototype's default-off posture requires.
 */
@ConfigurationProperties(prefix = "ragbrain.lab")
public record LabProperties(boolean enabled, long maxUploadBytes, Engine engine) {

    /** The existing {@code spring.servlet.multipart.max-file-size} (25 MB) in bytes. */
    public static final long SERVLET_MULTIPART_MAX_FILE_BYTES = 26_214_400L;

    /** The existing {@code spring.servlet.multipart.max-request-size} (26 MB) in bytes. */
    public static final long SERVLET_MULTIPART_MAX_REQUEST_BYTES = 27_262_976L;

    /** Upper bound on a configured envelope ceiling, so a typo cannot admit an unbounded read. */
    public static final long MAX_ENVELOPE_CEILING_BYTES = 67_108_864L;

    /** Upper bound on a configured timeout, so "bounded" is a fact rather than an intention. */
    public static final int MAX_TIMEOUT_MS = 120_000;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public LabProperties {
        if (enabled) {
            if (maxUploadBytes <= 0 || maxUploadBytes > SERVLET_MULTIPART_MAX_FILE_BYTES) {
                throw new IllegalStateException(
                        "ragbrain.lab.max-upload-bytes must be in (0, "
                                + SERVLET_MULTIPART_MAX_FILE_BYTES
                                + "]");
            }
            requireUsable(engine);
        }
    }

    /**
     * Document Engine connection settings. Exactly one authentication mode is configured — never
     * two, and never none:
     *
     * <ul>
     *   <li><b>api-key</b>: the engine's service-to-service protocol for machine consumers, an
     *       opaque key presented in {@code X-DocEngine-Api-Key}. The engine hashes it, resolves
     *       the organization <em>from</em> the key, and binds the scopes the key holds
     *       ({@code app/.../security/ApiKeyAuthFilter.java}). This is the production mode.
     *   <li><b>bearer-token</b>: an OIDC bearer for the engine's human-operator path. Usable only
     *       where a token can actually be kept fresh — this record holds one static string and
     *       nothing refreshes it, so a short-lived token silently expires into 401s.
     *   <li><b>dev-auth</b>: the engine's local development headers. Never a deployment mode.
     * </ul>
     *
     * <p>Do not add a convenience constructor to this record. A second constructor on a
     * {@code @ConfigurationProperties} record makes Spring's binding ambiguous, and the whole
     * group then binds to null instead of failing — the defect that took retrieval down on
     * 2026-08-02 (see {@code RagProperties} and {@code RagPropertiesBindingTest}).
     */
    public record Engine(
            String baseUrl,
            UUID orgId,
            String bearerToken,
            String apiKey,
            boolean devAuth,
            long maxEnvelopeBytes,
            int connectTimeoutMs,
            int readTimeoutMs) {

        /** True when a production bearer token is configured. */
        public boolean hasBearerToken() {
            return bearerToken != null && !bearerToken.isBlank();
        }

        /** True when the engine's service-to-service API key is configured. */
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** The base URL with any trailing slash removed, so path concatenation stays exact. */
        public String normalizedBaseUrl() {
            String trimmed = baseUrl == null ? "" : baseUrl.trim();
            return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        }

        /** Redacted by construction: a record's generated toString would print the token. */
        @Override
        public String toString() {
            return "Engine[baseUrl="
                    + normalizedBaseUrl()
                    + ", orgId="
                    + orgId
                    + ", bearerToken=<redacted>"
                    + ", apiKey=<redacted>"
                    + ", devAuth="
                    + devAuth
                    + ", maxEnvelopeBytes="
                    + maxEnvelopeBytes
                    + ", connectTimeoutMs="
                    + connectTimeoutMs
                    + ", readTimeoutMs="
                    + readTimeoutMs
                    + "]";
        }
    }

    /**
     * Fails closed on any engine setting the adapter must not run with. Called from this record's
     * constructor while the Lab is enabled, and again by the adapter's constructor so a
     * programmatically assembled configuration cannot skip the guard.
     *
     * @throws IllegalStateException naming the offending property key only — never its value
     */
    public static void requireUsable(Engine engine) {
        if (engine == null) {
            throw new IllegalStateException("ragbrain.lab.engine is required while the Lab is enabled");
        }
        requireSafeBaseUrl(engine.baseUrl());

        // Counted rather than XOR'd: with three modes a pairwise comparison silently admits the
        // combination it does not name, and "two credentials configured" must fail as loudly as
        // none — the adapter would otherwise pick one by the order of an if-chain.
        int modes = (engine.devAuth() ? 1 : 0)
                + (engine.hasBearerToken() ? 1 : 0)
                + (engine.hasApiKey() ? 1 : 0);
        if (modes != 1) {
            throw new IllegalStateException(
                    "exactly one of ragbrain.lab.engine.api-key, "
                            + "ragbrain.lab.engine.bearer-token or ragbrain.lab.engine.dev-auth "
                            + "must be configured");
        }
        if (!engine.devAuth() && engine.orgId() != null) {
            throw new IllegalStateException(
                    "ragbrain.lab.engine.org-id applies only to local dev-auth mode; a deployment "
                            + "takes its organization from the credential — the engine resolves it "
                            + "from the api key, or from the bearer token's claims");
        }
        if (engine.maxEnvelopeBytes() <= 0
                || engine.maxEnvelopeBytes() > MAX_ENVELOPE_CEILING_BYTES) {
            throw new IllegalStateException(
                    "ragbrain.lab.engine.max-envelope-bytes must be in (0, "
                            + MAX_ENVELOPE_CEILING_BYTES
                            + "]");
        }
        requireBoundedTimeout("connect-timeout-ms", engine.connectTimeoutMs());
        requireBoundedTimeout("read-timeout-ms", engine.readTimeoutMs());
    }

    private static void requireBoundedTimeout(String key, int millis) {
        if (millis <= 0 || millis > MAX_TIMEOUT_MS) {
            throw new IllegalStateException(
                    "ragbrain.lab.engine." + key + " must be in (0, " + MAX_TIMEOUT_MS + "]");
        }
    }

    /**
     * A base URL may name only a scheme, host, port and path prefix. User info would place a
     * credential in every request line; a query or fragment would let configuration smuggle
     * parameters onto endpoints the adapter builds by concatenation.
     */
    private static void requireSafeBaseUrl(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("ragbrain.lab.engine.base-url is required");
        }
        URI uri;
        try {
            uri = new URI(configured.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("ragbrain.lab.engine.base-url is not a valid URI");
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new IllegalStateException("ragbrain.lab.engine.base-url must be absolute");
        }
        if (!ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("ragbrain.lab.engine.base-url must be http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException("ragbrain.lab.engine.base-url must name a host");
        }
        if (uri.getUserInfo() != null || uri.getRawUserInfo() != null) {
            throw new IllegalStateException("ragbrain.lab.engine.base-url must not carry user info");
        }
        if (uri.getRawQuery() != null) {
            throw new IllegalStateException("ragbrain.lab.engine.base-url must not carry a query");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalStateException("ragbrain.lab.engine.base-url must not carry a fragment");
        }
    }
}
