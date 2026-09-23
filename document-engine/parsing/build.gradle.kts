// Pages, text spans, layout elements, parser-output persistence, and the typed
// worker HTTP client. Phase 2/3. Knows about geometry and glyphs. Knows nothing
// about mortgages. Depends on :platform only — never on another feature module.
dependencies {
    implementation(project(":platform"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // WorkerClient: RestClient + multipart building (spring-web, no servlet
    // container) and contract JSON parsing (jackson). Both BOM-managed.
    implementation("org.springframework:spring-web")

    // PageController (parsing/web) serves page geometry and rasters to the review UI —
    // same starter as the other feature modules' controllers; the servlet container
    // itself still comes only from :app.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
