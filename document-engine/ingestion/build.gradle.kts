// Upload, validation, MIME sniffing, hashing, dedupe, normalization. Phase 1.
dependencies {
    implementation(project(":platform"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Ingestion-time validation only (corrupt/encrypted/page-count) — parsing
    // belongs to the Python worker. Version + licence rationale in the catalog.
    implementation(libs.pdfbox)
}
