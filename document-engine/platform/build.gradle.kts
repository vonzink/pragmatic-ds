// Tenancy, security, crypto, error model, audit, storage port, PII masking.
// Depends on nothing else in the project — everything else depends on it.
dependencies {
    // api: TenantScopedEntity, GlobalExceptionHandler, and DevTenantFilter surface
    // JPA/web/servlet types to every downstream module.
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-web")
    // api: the RBAC guards (Role, AuthPrincipal, DocumentAccessGuard) and the
    // @PreAuthorize/authority types surface to every feature module. Phase 7a.
    api("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.networknt:json-schema-validator:2.0.4")

    // Phase 6: official Vertex transports. Google auth is api because the
    // injectable ADC resolver is part of the adapter construction seam used by
    // the application selector; document/request/result contracts stay local.
    api(libs.google.auth.oauth2.http) {
        exclude(group = "javax.annotation", module = "javax.annotation-api")
    }
    implementation(libs.google.genai) {
        exclude(group = "javax.annotation", module = "javax.annotation-api")
    }
    implementation(libs.anthropic.java)
    implementation(libs.anthropic.java.vertex) {
        exclude(group = "javax.annotation", module = "javax.annotation-api")
    }

    testImplementation(platform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
    testImplementation("com.squareup.okhttp3:mockwebserver")
}
