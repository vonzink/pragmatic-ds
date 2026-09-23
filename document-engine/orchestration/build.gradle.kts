// Job/stage state machine, retry, resume, ParserPort. Phase 1.
// Owns every processing_job and processing_stage row: the worker holds no state,
// so resume lives here (docs/ARCHITECTURE.md section 10).
dependencies {
    implementation(project(":platform"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
}
