plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.license.report)
}

allprojects {
    group = "com.pragmaticds.docengine"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")

    repositories { mavenCentral() }

    // Temurin 21 (GPLv2 + Classpath Exception — see docs/LICENSING.md 4.1).
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.springBoot.get()}"))
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.springBoot.get()}"))
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        // Aligns the JUnit platform launcher with the BOM-managed engine version.
        // Without it, Gradle injects its own launcher and discovery fails with
        // "OutputDirectoryProvider not available".
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Gradle forks test JVMs at a 512 MB default, which a Spring Boot integration suite has
        // been clearing on luck rather than on headroom. CI went red on an OutOfMemoryError inside
        // JwtSecurityIT's application context — ten "failures" that were one heap exhaustion
        // wearing ten names, in tests the provoking change never touched. A build whose green
        // depends on how many contexts happen to be live at once teaches everyone to re-run red
        // until it passes, and that habit is the expensive part, not the memory. The sibling
        // host-app repo settled on 2g for exactly this symptom.
        maxHeapSize = "2g"
        // FAILED events, not just the format. Without `events` Gradle prints nothing per test, so a
        // red CI run says only "719 tests completed, 5 failed" and the names live in an HTML artifact
        // nobody can read from a terminal or a log API. That turned one failure into a round of
        // guessing; naming them costs nothing on a green run, which prints no test events at all.
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
        }

        // ...and again as a block at the very END of the task's output.
        //
        // events("failed") prints a failure at the moment it happens, which on this build means it
        // lands thousands of lines up, interleaved with the Spring shutdown logs of every
        // integration test that ran afterwards. A CI log read through an API is a TAIL, so those
        // lines are effectively unreachable and a red run reports only "N tests completed, M
        // failed". Reprinting the roll-call last makes the tail self-describing.
        val failed = mutableListOf<String>()
        afterTest(
            org.gradle.kotlin.dsl.KotlinClosure2<
                org.gradle.api.tasks.testing.TestDescriptor,
                org.gradle.api.tasks.testing.TestResult,
                Unit,
            >({ descriptor, result ->
                if (result.resultType == org.gradle.api.tasks.testing.TestResult.ResultType.FAILURE) {
                    val cause = result.exceptions.firstOrNull()
                    failed +=
                        "${descriptor.className?.substringAfterLast('.')}.${descriptor.name}" +
                            (cause?.let { "\n      ${it::class.simpleName}: ${it.message?.lineSequence()?.firstOrNull()}" } ?: "")
                }
            })
        )
        afterSuite(
            org.gradle.kotlin.dsl.KotlinClosure2<
                org.gradle.api.tasks.testing.TestDescriptor,
                org.gradle.api.tasks.testing.TestResult,
                Unit,
            >({ descriptor, _ ->
                if (descriptor.parent == null && failed.isNotEmpty()) {
                    logger.lifecycle("")
                    logger.lifecycle("=== FAILED TESTS (${failed.size}) ===")
                    failed.forEach { logger.lifecycle("  $it") }
                    logger.lifecycle("=== END FAILED TESTS ===")
                }
            })
        )

        // Docker Desktop on macOS puts its socket under $HOME, and Testcontainers'
        // discovery does not reliably read the active `docker context`. Point it at
        // the socket when the environment has not already configured one, so
        // integration tests run on a stock Docker Desktop install with no per-machine
        // setup. CI (Linux, /var/run/docker.sock) takes the untouched path.
        if (System.getenv("DOCKER_HOST") == null) {
            val desktopSocket = File(System.getProperty("user.home"), ".docker/run/docker.sock")
            if (desktopSocket.exists()) {
                environment("DOCKER_HOST", "unix://${desktopSocket.absolutePath}")
                environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
            }
        }

        // Opt-in test flags reach the GRADLE JVM when passed as `-Dkey=value` on the
        // command line, and tests run in a FORKED JVM that does not inherit them. Without
        // this forwarding a documented flag is silently ignored: the run looks successful,
        // does the default thing, and produces no clue that the flag went nowhere — which
        // is exactly how -Ddocengine.eval.calibrate=true wrote a report but no baseline.
        // Forward by name rather than wholesale: `systemProperties(System.getProperties())`
        // would also copy the JVM's own (user.dir, java.home) into the test process.
        listOf("docengine.eval.calibrate").forEach { key ->
            System.getProperty(key)?.let { systemProperty(key, it) }
        }

        // Docker Engine 29 reports MinAPIVersion 1.40 and rejects anything older
        // with HTTP 400. docker-java (via Testcontainers) otherwise negotiates down
        // to v1.32, which fails every discovery strategy with a misleading
        // "Could not find a valid Docker environment". Pin to 1.44 — comfortably
        // inside the engine's 1.40–1.54 window and old enough for CI runners.
        // docker-java reads the property key `api.version` (system property or
        // ~/.docker-java.properties), not a DOCKER_API_VERSION env var.
        if (System.getenv("DOCKER_API_VERSION") == null) {
            systemProperty("api.version", "1.44")
        } else {
            systemProperty("api.version", System.getenv("DOCKER_API_VERSION"))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }
}

// The Gradle half of the licence gate. `./gradlew generateLicenseReport`
// produces build/reports/dependency-license/index.json, which CI feeds to
// tools/license_gate_cli.py --format gradle. Policy: docs/LICENSING.md.
licenseReport {
    outputDir = layout.buildDirectory.dir("reports/dependency-license").get().asFile.absolutePath
    renderers = arrayOf(com.github.jk1.license.render.JsonReportRenderer("index.json", false))
}
