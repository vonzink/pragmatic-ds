// Review decisions, corrections, audit projection. Phase 7.
// Corrections are append-only: a review_decision row, never an in-place
// overwrite of extracted_field.
dependencies {
    implementation(project(":platform"))
    implementation(project(":extraction"))
    // MARK_REVIEWED / RECLASSIFY touch logical_document, which :classification owns.
    // :extraction depends on :classification with `implementation` (non-transitive), so
    // :review must declare it directly to see LogicalDocument + its repository.
    implementation(project(":classification"))
    // RegroupService lives here (with the other human-decision services): it edits
    // logical_document_page membership AND writes a review_decision. :classification cannot host it
    // because :classification would then depend on :review — a cycle (:review already depends on
    // :classification). Regroup validates pages against :parsing's Page (blank/duplicate signals)
    // and re-kicks the EXTRACTING stage via :orchestration's JobService.reExtract. Neither adds a
    // cycle: :parsing and :orchestration depend only on :platform.
    implementation(project(":parsing"))
    implementation(project(":orchestration"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // review_decision previous/new values and audit metadata are jsonb documents.
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-starter-validation")
}
