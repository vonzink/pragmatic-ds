plugins {
    java
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.pragmaticds"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "1.1.7"

dependencies {
    // --- Spring core ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Prometheus scrape endpoint for production metrics (exposed on the internal
    // management port only). micrometer-core (the MeterRegistry API) comes with actuator.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // --- Spring AI: model libraries, Tika document reader ---
    // Providers are wired conditionally in app code so the admin dashboard can
    // boot without cloud API keys. pgvector retrieval is implemented with SQL.
    implementation("org.springframework.ai:spring-ai-anthropic")
    implementation("org.springframework.ai:spring-ai-openai")
    implementation("org.springframework.ai:spring-ai-tika-document-reader")

    // --- PDF text-extraction fallback for vision document-blocks + page counting ---
    // Must match the pdfbox family Tika 3.3.0 pulls (fontbox/pdfbox-io 3.0.7); an older
    // pin (3.0.3) splits the family -> Tika's PDF2XHTML hits NoSuchMethodError on
    // PDFTextStripper.setIgnoreContentStreamSpaceGlyphs at parse time.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")

    // --- Database ---
    // Maps pgvector VECTOR columns to float[] in entities.
    // Version intentionally tracks Boot's managed Hibernate version (see below).
    implementation("org.hibernate.orm:hibernate-vector")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- Domain pack loading (YAML -> records) ---
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // --- Findings envelope v2 contract (JSON Schema validation) ---
    implementation("com.networknt:json-schema-validator:1.5.6")

    // --- Rate limiting (public endpoint protection) ---
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // --- Token counting for chunk sizing ---
    implementation("com.knuddels:jtokkit:1.1.0")

    // --- S3 corpus sync ---
    implementation("software.amazon.awssdk:s3")

    // --- Testing ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
        mavenBom("software.amazon.awssdk:bom:2.55.1")
    }
    dependencies {
        // hibernate-vector is not in Boot's BOM; pin it to Boot's Hibernate version.
        dependency("org.hibernate.orm:hibernate-vector:${importedProperties["hibernate.version"]}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Gradle's default test JVM heap is 512 MB. The suite now caches more than one full
    // @SpringBootTest context alongside Testcontainers clients (SpendGuardServiceTxIT and
    // IncomeLabIT), and 512 MB is no longer enough — the symptom was an OutOfMemoryError while
    // instantiating an unrelated bean in whichever context happened to load second.
    maxHeapSize = "2g"

    // Name what failed, in the build log.
    //
    // Gradle's default logging prints "N tests completed, M failed" and writes the detail to an
    // HTML report — which is fine locally and useless in CI, where the report is discarded with
    // the runner. Reading a failure then means re-running the suite locally or guessing, and
    // guessing is what it sounds like. The cost is a stack trace per failure on an already
    // failing build.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        // Assertion messages are the point; a cause chain forty frames deep is not.
        stackTraceFilters(org.gradle.api.tasks.testing.logging.TestStackTraceFilter.ENTRY_POINT)
    }

    // ...and repeat the names at the end, where a tail can actually reach them.
    //
    // testLogging prints each failure at the moment it happens, which in a suite that boots
    // several Spring contexts means it is thousands of lines of Hikari shutdown chatter above
    // the end of the log. A CI log is usually read from the bottom — often only the bottom is
    // retrievable — so a failure printed in the middle is only marginally better than a count.
    // This repeats just the names, last, so "what broke" survives a thirty-line tail.
    val failedTests = mutableListOf<String>()
    afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
        if (result.resultType == TestResult.ResultType.FAILURE) {
            failedTests += "${descriptor.className?.substringAfterLast('.')}.${descriptor.name}"
        }
    }))
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, _ ->
        // The root suite, which fires once after everything.
        if (descriptor.parent == null && failedTests.isNotEmpty()) {
            logger.lifecycle("")
            logger.lifecycle("FAILED TESTS (${failedTests.size}):")
            failedTests.sorted().forEach { logger.lifecycle("  $it") }
        }
    }))
}
