package com.pragmaticds.docengine.security;

import com.pragmaticds.docengine.platform.security.Role;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The two authentication chains and the single, central RBAC matrix.
 *
 * <p>Exactly one chain is active per profile: {@link #devSecurityChain} under {@code local}/
 * {@code test} (header-driven principal, no IdP); {@link #jwtSecurityChain} everywhere else (OIDC
 * bearer tokens). Both apply the SAME {@link #matrix} so the authorization surface cannot drift
 * between dev and prod.
 *
 * <h2>RBAC matrix (the seam — keep it simple and safe)</h2>
 *
 * <ul>
 *   <li>Public: actuator health/info, OpenAPI docs, {@code /error}, the signed-download endpoint
 *       ({@code /v1/download} — authorized by the token's HMAC signature, not a session), and every
 *       CORS preflight.
 *   <li>READONLY: read endpoints only — {@code GET /v1/**} (packages, pages, documents, fields,
 *       export, jobs, file/render content).
 *   <li>PROCESSOR: READONLY + upload ({@code POST /v1/packages}) + resume
 *       ({@code POST /v1/jobs/*&#47;resume}).
 *   <li>REVIEWER: ⊇ PROCESSOR, plus the 7b correction/review writes — {@code PATCH /v1/fields/*},
 *       {@code POST /v1/documents/*&#47;review}, {@code POST /v1/documents/*&#47;classification} — and
 *       the Spec 2 regroup writes — {@code POST /v1/packages/*&#47;regroup} (edit document grouping)
 *       and {@code POST /v1/pages/*&#47;verdict} (override a blank/duplicate signal).
 *   <li>ADMIN: everything — including any other {@code /v1/**} write, which stays reserved to
 *       ADMIN, and the three RAW-TEXT reads ({@code GET /v1/packages/*&#47;engine-result*},
 *       {@code GET /v1/pages/*&#47;spans}, {@code GET /v1/pages/*&#47;structure}, plus
 *       {@code GET /v1/documents/*&#47;body.md} and {@code GET /v1/documents/*&#47;gold}), whose
 *       bodies are unmasked borrower text.
 *   <li>{@code SCOPE_ENGINE_RESULT_READ}: the RAW-TEXT reads ONLY — the sole non-role authority in
 *       this matrix. It exists so a read-only machine consumer can reach parsed envelopes without an
 *       ADMIN key that would also open every write. Held only by an API key
 *       ({@link ApiKeyAuthFilter} grants {@code SCOPE_*} per scope); no human principal can carry
 *       it, and the key still needs a Role scope to authenticate at all.
 * </ul>
 *
 * <p>The RAW-TEXT gate is one representation deep, deliberately: the page RASTER
 * ({@code GET /v1/pages/*&#47;render} and its {@code /signed-url}) carries the same words as pixels
 * and stays at READONLY so the review UI keeps its thumbnails. See the matcher comment below and
 * {@code RawContentAdminBoundaryIT}.
 *
 * <p>Cross-tenant/existence hiding is NOT done here (a 403 would confirm an id exists in another
 * org). That is the controllers' {@code findByIdAndOrgId} → 404 job and {@code DocumentAccessGuard}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ENGINE_RESULT_MEDIA_TYPE =
            "application/vnd.pragmaticds.document-engine-result+json;version=1";

    /** The generated path key for {@code PageSpanController.spans} — its {@code @PathVariable} name. */
    private static final String PAGE_SPANS_PATH = "/v1/pages/{id}/spans";

    /** The generated path key for {@code PageStructureController.structure}. */
    private static final String PAGE_STRUCTURE_PATH = "/v1/pages/{id}/structure";

    /** Unauthenticated-safe endpoints. Everything else requires a bound principal. */
    static final String[] PUBLIC_PATHS = {
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/error",
        // 7c: the signed-download endpoint is UNAUTHENTICATED on purpose — it is authorized by the
        // HMAC signature in the token, not by a session, and it enforces the org baked into that
        // token. An expired, forged, or cross-tenant token gets an opaque 404. See
        // SignedDownloadController.
        "/v1/download"
    };

    /** The one place the endpoint→role rules live; applied identically to both chains. */
    private static void matrix(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                    auth) {
        auth.requestMatchers(PUBLIC_PATHS)
                .permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/packages")
                .hasAnyRole("PROCESSOR", "REVIEWER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/v1/jobs/*/resume", "/v1/jobs/*/cancel")
                .hasAnyRole("PROCESSOR", "REVIEWER", "ADMIN")
                // ── RAW CONTENT: unmasked borrower TEXT. ADMIN only. ──────────────────────
                // Immutable machine-result bytes include unmasked sensitive values, and the L1 span
                // layer is more revealing than /fields — it is every captured word, and masking
                // cannot apply to it in principle, because masking is defined per NAMED sensitive
                // field and a span has no name. The L2 structure read is the same text re-grouped
                // (cell contents, block text), so it sits behind the same gate: gating the flat
                // record while leaving its index open would be no gate at all. Metadata and page
                // GEOMETRY stay under the generic GET rule; only the exact TEXT shapes are
                // restricted.
                //
                // Note what this does NOT reach: /v1/pages/*/render and /v1/pages/*/signed-url serve
                // the page RASTER, which carries the same words as pixels, and they stay under the
                // generic GET rule so the review UI can show thumbnails to REVIEWER and READONLY.
                // The boundary is therefore one representation deep, by decision. Do not describe it
                // as "unmasked borrower content is ADMIN-only" anywhere.
                //
                // THESE MATCHERS MUST PRECEDE THE BROAD /v1/** GET MATCHER BELOW. Behind it they are
                // dead rules and READONLY reads everything, with no symptom — which is why
                // RawContentAdminBoundaryIT asserts a nonexistent id answers 403 to READONLY rather
                // than the controller's 404. That difference IS the ordering.
                //
                // ADMIN **or** the narrow read scope. A machine consumer that only ever GETs parsed
                // envelopes previously needed an ADMIN key, and ADMIN also opens every /v1/** write
                // below — a real over-grant for a read-only integration. SCOPE_ENGINE_RESULT_READ
                // names exactly this capability and nothing else: it appears on these five matchers
                // and nowhere else in the matrix, so it can authorize no write and widen no other
                // read. Only an api key can hold it (ApiKeyAuthFilter grants SCOPE_* per scope); the
                // human OIDC path grants role authorities only, so this line cannot change what any
                // person can reach. hasAnyAuthority, not hasAnyRole: "ROLE_ADMIN" is what
                // hasRole("ADMIN") expands to, and the scope authority carries no ROLE_ prefix.
                //
                // body.md is the document's captured text rendered as Markdown, and the body is NOT
                // masked — masking by evidence subtraction (body plan T5) has not shipped. It is L1/L2
                // text in a third shape, so it belongs on this line; from #55 until this matcher it
                // was served to READONLY through the broad read rule. When T5 lands and the body
                // carries piiCoverage, revisit whether a masked body can move back to the broad rule.
                .requestMatchers(
                        HttpMethod.GET,
                        "/v1/packages/*/engine-result",
                        "/v1/packages/*/engine-results/*",
                        "/v1/pages/*/spans",
                        "/v1/pages/*/structure",
                        "/v1/documents/*/body.md",
                        // A reviewed document's decisions UNMASKED — the gold-set label source
                        // (design 2026-09-22 §5). Raw by definition: a label that reads a mask
                        // would teach the scorer that the mask is the answer.
                        "/v1/documents/*/gold")
                .hasAnyAuthority("ROLE_ADMIN", "SCOPE_ENGINE_RESULT_READ")
                // The unknown-document triage queue is a READ that is not a READONLY read. It exists
                // to drive a RECLASSIFY decision, and the population it surfaces is precisely the
                // documents where classification was least certain — the best loser's score, the
                // shortfall against threshold, and every pack's weak anchor matches. That is the
                // reviewer's working set, not a general read.
                //
                // Anchor ids only, never matched text, so this is NOT a raw-content matcher and does
                // not belong on the ADMIN/SCOPE line above. An API key carries scopes and no role and
                // therefore never passes: a machine consumer wanting classification evidence has
                // GET /v1/packages/{id}/classification, which asks nothing of a human.
                //
                // MUST PRECEDE THE BROAD /v1/** GET MATCHER BELOW, or Spring matches /v1/** first and
                // this becomes a dead rule.
                //
                // Unlike the raw-content block above, that would NOT open a hole: UnknownTriageService
                // calls requireReviewer() before it loads anything, so a READONLY caller is refused
                // either way. The consequence of mis-ordering here is the loss of a layer, not of the
                // gate — which is also why no test can isolate this matcher the way
                // RawContentAdminBoundaryIT isolates those. The inner check fires first, so 403-vs-404
                // on a nonexistent id measures the service, not the ordering. What IS pinned:
                // reviewer_passes_the_matcher_and_reaches_the_controller in UnknownTriageIT asserts a
                // REVIEWER reaches the controller (404 on an absent id), which fails if this matcher
                // is ever narrowed to exclude REVIEWER.
                //
                // The value of the line is therefore defence in depth plus legibility: the rule lives
                // where a reader of the matrix will find it, and a future /v1/** GET change cannot
                // silently widen the queue to READONLY.
                .requestMatchers(HttpMethod.GET, "/v1/packages/*/triage")
                .hasAnyRole("REVIEWER", "ADMIN")
                // Authored extraction schemas read back at the SAME role that writes them. A
                // definition is this org's authored configuration — its regexes and the label text
                // lifted off the forms it processes — and there is no reason a read-only
                // integration principal needs it. ADMIN is also the reversible direction: widening
                // later is a line, narrowing later breaks a consumer.
                //
                // MUST PRECEDE THE BROAD /v1/** GET MATCHER BELOW, and here that ordering IS the
                // gate rather than a second layer. The POST is ADMIN by falling through to the
                // catch-all, but a GET never reaches it — the broad read rule below would grant
                // these paths to READONLY, with no other check anywhere to refuse them.
                // ExtractionSchemaListingIT measures it the way RawContentAdminBoundaryIT does: on
                // a nonexistent type, READONLY must see 403 and ADMIN must see the controller's
                // 404. That difference is the only observable proof of the ordering.
                .requestMatchers(
                        HttpMethod.GET, "/v1/extraction-schemas", "/v1/extraction-schemas/*")
                .hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/v1/**")
                .hasAnyRole("READONLY", "PROCESSOR", "REVIEWER", "ADMIN")
                // 7b: the correction/review writes. A field correction, a document sign-off, and a
                // reclassification are REVIEWER work (a human decision on machine output), not
                // PROCESSOR upload work — so they sit above PROCESSOR and below the ADMIN catch-all.
                .requestMatchers(HttpMethod.PATCH, "/v1/fields/*")
                .hasAnyRole("REVIEWER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/v1/documents/*/review")
                .hasAnyRole("REVIEWER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/v1/documents/*/classification")
                .hasAnyRole("REVIEWER", "ADMIN")
                // Spec 2: the regroup primitive and the page verdict override are the same kind of
                // work as the 7b writes above — a human decision on machine grouping/signals — so
                // they sit with them at REVIEWER+, above the ADMIN catch-all.
                .requestMatchers(HttpMethod.POST, "/v1/packages/*/regroup")
                .hasAnyRole("REVIEWER", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/v1/pages/*/verdict")
                .hasAnyRole("REVIEWER", "ADMIN")
                // #66: the per-document AI re-run is the same authority as the regroup that
                // re-extracts — a human deciding the machine should read this document again —
                // so it sits at REVIEWER+ with it. It writes only AI rows through the stage's own
                // persist (a reviewer's correction survives it), never a value of the caller's.
                .requestMatchers(HttpMethod.POST, "/v1/documents/*/ai-extract")
                .hasAnyRole("REVIEWER", "ADMIN")
                // 7c: soft delete is a DESTRUCTIVE lifecycle action, reserved to ADMIN. Spelled out
                // explicitly (though the ADMIN catch-all below would also cover it) so the RBAC
                // matrix reads the intent, and so a future non-ADMIN /v1 rule cannot widen it by
                // accident.
                .requestMatchers(HttpMethod.DELETE, "/v1/packages/*")
                .hasRole("ADMIN")
                // Any other /v1 write is reserved to ADMIN.
                .requestMatchers("/v1/**")
                .hasRole("ADMIN")
                .anyRequest()
                .authenticated();
    }

    /** Adds the authenticated result-read contract and integrity headers to generated OpenAPI. */
    @Bean
    OpenApiCustomizer engineResultOpenApiCustomizer() {
        return openApi -> {
            Components components =
                    openApi.getComponents() == null ? new Components() : openApi.getComponents();
            openApi.setComponents(components);
            components.addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT"));

            Map<String, Boolean> paths =
                    Map.of(
                            "/v1/packages/{packageId}/engine-result", true,
                            "/v1/packages/{packageId}/engine-results", false,
                            "/v1/packages/{packageId}/engine-results/{revision}", true);
            paths.forEach(
                    (path, hasContentHeaders) -> {
                        if (openApi.getPaths() == null || openApi.getPaths().get(path) == null) {
                            return;
                        }
                        Operation operation = openApi.getPaths().get(path).getGet();
                        if (operation == null) {
                            return;
                        }
                        operation.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
                        if (hasContentHeaders) {
                            ApiResponse ok = operation.getResponses().get("200");
                            if (ok != null) {
                                // The controller returns exact stored byte[] at runtime, but those
                                // bytes are the vendor JSON envelope itself, not base64/string
                                // content. Override springdoc's byte[] inference accordingly.
                                if (ok.getContent() != null
                                        && ok.getContent().get(ENGINE_RESULT_MEDIA_TYPE) != null) {
                                    ok.getContent()
                                            .get(ENGINE_RESULT_MEDIA_TYPE)
                                            .setSchema(new ObjectSchema());
                                }
                                ok.addHeaderObject(
                                                "Content-Length",
                                                new Header()
                                                        .description("Exact canonical byte length")
                                                        .schema(new StringSchema()))
                                        .addHeaderObject(
                                                "ETag",
                                                new Header()
                                                        .description("Quoted envelope SHA-256")
                                                        .schema(new StringSchema()))
                                        .addHeaderObject(
                                                "Cache-Control",
                                                new Header()
                                                        .description("private, no-store")
                                                        .schema(new StringSchema()))
                                        .addHeaderObject(
                                                "X-Content-Type-Options",
                                                new Header()
                                                        .description("nosniff")
                                                        .schema(new StringSchema()));
                            }
                        }
                    });
        };
    }

    /**
     * Documents the L1 span read the same way, and in the same place, as the engine-result bytes.
     *
     * <p>They belong together: these are the two resources whose 200 body is unmasked borrower TEXT,
     * they carry the same integrity headers for the same reason, and they share the ADMIN matcher a
     * few lines above. Keeping their API documentation adjacent to that matcher is deliberate — a
     * reader who changes one should see the other.
     *
     * <p>They are not, however, everything a caller can read unmasked. {@code GET
     * /v1/pages/*&#47;render} serves the page RASTER — the same words, as pixels, plus the ones a
     * span cannot represent — under the broad {@code GET /v1/**} rule, so READONLY reaches it, and it
     * writes no audit event. That is deliberate and pre-existing (the review UI shows thumbnails to
     * reviewers), and it is stated here so nobody reads the ADMIN matcher as "unmasked borrower
     * content is ADMIN-only". It is not; unmasked borrower TEXT is.
     * {@code RawContentAdminBoundaryIT} measures both halves of that sentence.
     *
     * <p>The customizer lives here rather than as annotations on the controller because
     * {@code :parsing} does not depend on springdoc and should not start to for the sake of
     * documentation strings.
     */
    @Bean
    OpenApiCustomizer pageSpansOpenApiCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null || openApi.getPaths().get(PAGE_SPANS_PATH) == null) {
                return;
            }
            Operation operation = openApi.getPaths().get(PAGE_SPANS_PATH).getGet();
            if (operation == null) {
                return;
            }
            operation.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
            operation.setDescription(
                    "L1 RAW: one window of a page's captured text spans, in the page's total order"
                        + " (source, ordinal, id). ADMIN only — the body is unmasked borrower text"
                        + " and masking cannot apply, because masking is per named sensitive field"
                        + " and a span has no name. Filters AND together and never reorder; follow"
                        + " nextCursor to exhaustion. limit defaults to 1000 and is refused above"
                        + " 5000 rather than clamped. An ETag is issued, and honoured, ONLY for the"
                        + " unfiltered uncursored whole-page window — it is the one representation it"
                        + " describes, and it is omitted rather than fabricated on a page with no"
                        + " content_hash. To tell whether a reparse happened mid-walk, compare the"
                        + " contentHash member, which every window carries.");
            ApiResponse ok = operation.getResponses() == null ? null : operation.getResponses().get("200");
            if (ok != null) {
                ok.addHeaderObject(
                                "ETag",
                                new Header()
                                        .description(
                                                "Quoted \"<pageId>.<content_hash>\"; issued only for"
                                                    + " the whole-page window, and absent when the"
                                                    + " page has no content_hash")
                                        .schema(new StringSchema()))
                        .addHeaderObject(
                                "Cache-Control",
                                new Header().description("private, no-store").schema(new StringSchema()))
                        .addHeaderObject(
                                "X-Content-Type-Options",
                                new Header().description("nosniff").schema(new StringSchema()));
            }
        };
    }

    /**
     * Documents the L2 structure read beside the other two raw-text surfaces it shares the ADMIN
     * matcher with. Same placement argument as {@link #pageSpansOpenApiCustomizer()}: these
     * customizers live next to the matcher that guards them, and {@code :parsing} does not depend
     * on springdoc.
     */
    @Bean
    OpenApiCustomizer pageStructureOpenApiCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null || openApi.getPaths().get(PAGE_STRUCTURE_PATH) == null) {
                return;
            }
            Operation operation = openApi.getPaths().get(PAGE_STRUCTURE_PATH).getGet();
            if (operation == null) {
                return;
            }
            operation.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
            operation.setDescription(
                    "L2 STRUCTURE: one page's geometric structure exactly as persisted — tables"
                        + " with (row, col)-addressed cells, visual blocks, pixel marks — each"
                        + " carrying the span ids that lead down to L1. ADMIN only: the body is the"
                        + " page's unmasked text, re-grouped. structureConfidence and"
                        + " textConfidence are separate numbers and never multiplied; a structure"
                        + " with no member spans has null textConfidence. detectorCoverage says"
                        + " whether each detector family looked (RAN / NOT_IMPLEMENTED / UNKNOWN)."
                        + " ?containsSpan= keeps the structures owning that span (the upward walk);"
                        + " ?kind=TABLE|BLOCK|MARK narrows. An ETag is issued only for the"
                        + " unfiltered whole-page window and carries the structure contract"
                        + " version; compare body.contentHash to detect a re-parse mid-walk.");
            ApiResponse ok =
                    operation.getResponses() == null ? null : operation.getResponses().get("200");
            if (ok != null) {
                ok.addHeaderObject(
                                "ETag",
                                new Header()
                                        .description(
                                                "Quoted"
                                                    + " \"<pageId>.<content_hash>-l2/<structureContract>\";"
                                                    + " issued only for the whole-page window, and"
                                                    + " absent when the page has no content_hash")
                                        .schema(new StringSchema()))
                        .addHeaderObject(
                                "Cache-Control",
                                new Header().description("private, no-store").schema(new StringSchema()))
                        .addHeaderObject(
                                "X-Content-Type-Options",
                                new Header().description("nosniff").schema(new StringSchema()));
            }
        };
    }

    @Bean
    @Profile({"local", "test"})
    public SecurityFilterChain devSecurityChain(
            HttpSecurity http,
            @Value("${docengine.dev.role:ADMIN}") Role devRole,
            CorsConfigurationSource devCorsConfigurationSource)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(devCorsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(
                        new DevAuthFilter(devRole), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(SecurityConfig::matrix);
        return http.build();
    }

    @Bean
    @Profile("!local & !test")
    public SecurityFilterChain jwtSecurityChain(
            HttpSecurity http,
            JwtAuthPrincipalConverter jwtAuthPrincipalConverter,
            ApiKeyHasher apiKeyHasher,
            ApiKeyRepository apiKeyRepository,
            com.pragmaticds.docengine.platform.security.AppUserRepository appUserRepository,
            @org.springframework.beans.factory.annotation.Value(
                            "${docengine.delegation.auto-provision-role:}")
                    String autoProvisionRole)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Outermost: clears TenantContext/AuthContext in a finally on every outcome. It must
                // sit AHEAD of the api-key filter too, so its finally covers the contexts that filter
                // binds — hence the api-key filter is added AFTER it and BEFORE the bearer filter.
                .addFilterBefore(new ContextClearingFilter(), BearerTokenAuthenticationFilter.class)
                // Service auth (Spec 6 §6a.2): an X-DocEngine-Api-Key authenticates the LOS; a bearer
                // token authenticates a human. Ordered ContextClearingFilter → ApiKeyAuthFilter →
                // BearerTokenAuthenticationFilter. With no api-key header this filter is a pass-through
                // and the bearer path runs as before.
                .addFilterBefore(
                        new ApiKeyAuthFilter(
                                apiKeyHasher,
                                apiKeyRepository,
                                appUserRepository,
                                // Blank = require pre-provisioning; a role name = create a
                                // first-sight delegated user with THAT role. An unrecognized value
                                // fails the context at boot rather than silently disabling
                                // provisioning and 401ing every reviewer in production.
                                autoProvisionRole == null || autoProvisionRole.isBlank()
                                        ? null
                                        : com.pragmaticds.docengine.platform.security.Role.valueOf(
                                                autoProvisionRole.trim())),
                        BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(SecurityConfig::matrix)
                .oauth2ResourceServer(
                        oauth ->
                                oauth.jwt(
                                        jwt ->
                                                jwt.jwtAuthenticationConverter(
                                                        jwtAuthPrincipalConverter)));
        return http.build();
    }

    /**
     * CORS for the review UI's Vite dev server (Phase 6). Bound to {@code local}/{@code test} for
     * the same reason the dev auth filter is: a production origin policy belongs with real auth,
     * configured per-deployment, not baked in here.
     */
    @Bean
    @Profile({"local", "test"})
    public CorsConfigurationSource devCorsConfigurationSource(
            @Value("${docengine.dev.cors-origins:http://localhost:6173,http://127.0.0.1:6173}")
                    List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(1800L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", config);
        return source;
    }
}
