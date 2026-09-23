// Immutable machine-result persistence, canonical envelope, finalization, and reads.
// This module assembles existing persisted domain rows; it does not own parser behavior.
dependencies {
    implementation(project(":platform"))
    implementation(project(":ingestion"))
    implementation(project(":orchestration"))
    implementation(project(":parsing"))
    implementation(project(":classification"))
    implementation(project(":extraction"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
