package com.pragmaticds.docengine.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Drift guard between the two Dockerfiles that describe the SAME runtime.
 *
 * <p>{@code Dockerfile} is a multi-stage source build, used locally and for dev. {@code
 * Dockerfile.runtime} is FROM the JRE + COPY of the laptop-built jar, and since #28 it is <b>the one
 * compose builds</b> — so it alone reaches production.
 *
 * <p>#28 pointed compose at {@code Dockerfile.runtime} without carrying its hardening across, and
 * the engine ran as root with a quarter-sized heap until it was caught by hand. Nothing failed: the
 * containers came up and {@code /actuator/health} answered UP. That is the whole problem — two files
 * describing one runtime, where only the quieter one is real, and a divergence that no test, no
 * health check and no deploy step notices.
 *
 * <p>This pins the properties that must hold in the file that ships, and — because a fix that only
 * touches one file is how the divergence recurs — that the source build still asserts them too.
 */
class RuntimeImageParityTest {

    @Test
    void the_shipped_image_runs_as_a_non_root_user() {
        // The API holds borrower NPI. Serving HTTP needs no root, and an image that silently
        // acquires it is a downgrade nobody reviews, because nothing about it fails.
        assertThat(dockerfile("Dockerfile.runtime"))
                .as("Dockerfile.runtime is what compose builds — it must drop root")
                .contains("USER docengine");
        assertThat(dockerfile("Dockerfile"))
                .as("the source build must not diverge from it")
                .contains("USER docengine");
    }

    @Test
    void the_shipped_image_sizes_the_heap_to_the_container() {
        // Without this the JVM takes its 25% default: the container IS the memory budget, so a
        // quarter-sized heap OOMs on large documents long before the box is under pressure.
        assertThat(dockerfile("Dockerfile.runtime"))
                .as("heap flag must survive in the file that ships")
                .contains("-XX:MaxRAMPercentage=75");
        assertThat(dockerfile("Dockerfile")).contains("-XX:MaxRAMPercentage=75");
    }

    @Test
    void the_shipped_image_owns_the_blob_directory_it_will_write_to() {
        // /data/blobs is the durable document store (a compose volume mounts over it). Created and
        // chowned here so the non-root user can write; without it the mount lands root-owned and
        // every upload fails at runtime rather than at build.
        assertThat(dockerfile("Dockerfile.runtime"))
                .contains("mkdir -p /data/blobs")
                .contains("chown -R docengine:docengine");
    }

    /**
     * The jar must be owned by the user that runs it. A root-owned jar under {@code USER docengine}
     * still executes — this is about the image being coherent, not about a failure mode.
     */
    @Test
    void the_shipped_image_copies_the_jar_to_the_user_that_runs_it() {
        assertThat(dockerfile("Dockerfile.runtime"))
                .contains("COPY --chown=docengine:docengine app.jar");
    }

    private static String dockerfile(String name) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && current != null; i++, current = current.getParent()) {
            Path candidate = current.resolve(name);
            // settings.gradle.kts disambiguates the repo root from any parent that happens to hold
            // a file of the same name.
            if (Files.isRegularFile(candidate)
                    && Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                try {
                    return Files.readString(candidate);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException(name + " not found above " + System.getProperty("user.dir"));
    }
}
