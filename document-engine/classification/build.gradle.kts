// Rule packs, page classification, package splitting. Phase 4.
// One of two modules allowed to contain mortgage domain rules.
dependencies {
    implementation(project(":platform"))
    implementation(project(":parsing"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // PackageDocumentsController (classification/web) — same starter as ingestion's
    // controller; the servlet container itself still comes only from :app.
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Rule-pack definitions and classification evidence are jsonb documents.
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
