// Extraction schemas, field extraction, evidence chains. Phase 5.
// The other module allowed to contain mortgage domain rules.
dependencies {
    implementation(project(":platform"))
    implementation(project(":parsing"))
    implementation(project(":classification"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Fields/export controllers (extraction/web) — same starter as classification's
    // controller; the servlet container itself still comes only from :app.
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Extraction schema definitions and confidence components are jsonb documents.
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
