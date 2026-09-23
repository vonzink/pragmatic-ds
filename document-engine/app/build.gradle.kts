plugins { alias(libs.plugins.spring.boot) }

// build-info.properties → Spring BuildProperties → the parse-once behavior
// fingerprint's engineRelease input (version + build time). Every image rebuild
// therefore changes the fingerprint and disables stale reuse — over-trigger by
// construction. Deployments wanting stable reuse across provably identical
// rebuilds set docengine.reuse.engine-release instead (operator contract).
springBoot { buildInfo() }

dependencies {
    implementation(project(":platform"))
    implementation(project(":ingestion"))
    implementation(project(":orchestration"))
    implementation(project(":parsing"))
    implementation(project(":classification"))
    implementation(project(":extraction"))
    implementation(project(":results"))
    implementation(project(":review"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The JWT resource-server chain lives here (non-local profiles). The base
    // security starter arrives transitively via :platform (api). Phase 7a.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation(libs.springdoc.openapi)

    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // App ITs generate fixture PDFs in-test; :ingestion keeps PDFBox as
    // implementation (not exposed transitively), so the test source set
    // declares its own — the same pattern host-app documents.
    testImplementation(libs.pdfbox)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.postgresql:postgresql")
    testImplementation(libs.archunit.junit5)

    // Phase 2: the worker wire contract is pinned on the Java side with
    // MockWebServer fixtures (docs/WORKER_CONTRACT.md). The Spring Boot BOM does
    // not manage okhttp, so the okhttp BOM pins the family (Apache-2.0,
    // test-only).
    testImplementation(platform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
    testImplementation("com.squareup.okhttp3:mockwebserver")
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("live-eval", "corpus-eval") }
}

// The deterministic extraction harness over REAL documents: the gitignored corpus/eval tree,
// scored by the same loader/scorer/gate that scores the committed synthetic corpus on every
// build. Manual-only, mirroring liveEval: never wired into `test` or `build`, so CI never looks
// at corpus/. Two refusals before a single test runs — the opt-in property, and any file under
// corpus/ that git tracks, because a tracked file there is the NPI rule already broken.
tasks.register<Test>("corpusEval") {
    group = "verification"
    description = "Scores the gitignored real-document corpus (corpus/eval) through the extraction harness"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("corpus-eval") }
    outputs.upToDateWhen { false }
    val corpusRoot = rootProject.layout.projectDirectory.dir("corpus")
    val trackedUnderCorpus =
        providers.exec {
            workingDir = rootProject.projectDir
            commandLine("git", "ls-files", "--", "corpus")
        }.standardOutput.asText
    doFirst {
        if (providers.gradleProperty("corpusEval").orNull != "true") {
            throw GradleException(
                "Corpus evaluation is disabled. Re-run with -PcorpusEval=true; it reads only the " +
                    "gitignored corpus/eval tree and never runs in CI."
            )
        }
        val leaked =
            trackedUnderCorpus.get().lines().map { it.trim() }
                .filter { it.isNotEmpty() && it != "corpus/README.md" }
        if (leaked.isNotEmpty()) {
            throw GradleException(
                "Refusing to run: git tracks files under corpus/, which must hold only README.md " +
                    "(real documents carry NPI and are never committed): " + leaked.joinToString(", ")
            )
        }
    }
    // -PcorpusDir=<path> scores a tree OUTSIDE the repo (the gold-set mirror, ~/pds-gold-set/v1).
    // Default is unchanged: the gitignored corpus/eval. Relative paths resolve from the repo root.
    val corpusDir =
        providers.gradleProperty("corpusDir")
            .map { rootProject.projectDir.resolve(it) }
            .orElse(corpusRoot.dir("eval").asFile)
    systemProperty("docengine.eval.corpus-dir", corpusDir.get().absolutePath)
}

tasks.register<Test>("liveEval") {
    group = "verification"
    description = "Runs the synthetic AI corpus against an explicitly configured live provider"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live-eval") }
    outputs.upToDateWhen { false }
    doFirst {
        if (providers.gradleProperty("liveEval").orNull != "true") {
            throw GradleException(
                "Live evaluation is disabled. Re-run with -PliveEval=true " +
                    "and synthetic-only provider credentials."
            )
        }
    }
    systemProperty("docengine.live-eval.enabled", "true")
}
