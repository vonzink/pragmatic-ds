package com.pragmaticds.rag.pack;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Generates a slug-matched neutral starter pack by cloning the bundled
 * {@code packs/_template} into the generated-packs directory, writing a YAML-safe
 * {@code pack.yaml}, and validating the result with {@link DomainPackLoader}.
 * Used when a brain is created with no explicit packRef, so "connect a folder"
 * needs no hand-authored pack.
 *
 * <p>Generated packs are written to a configurable, <em>persistent</em> directory
 * ({@code ragbrain.rag.packs.generated-path}, default {@code ./data/packs}) — NOT
 * the image-baked {@code packs/} tree. That directory must be backed by a mounted
 * volume; otherwise a dashboard-created brain loses its pack on redeploy and every
 * request to it 500s ({@link DomainPackRegistry} cannot load a missing pack dir).
 * The returned packRef points at the generated location so the registry resolves it.
 */
@Service
public class PackTemplateService {

    private static final List<String> COPY_FILES =
            List.of("prompt.yaml", "guardrails.yaml", "classifier.yaml", "retrieval.yaml");

    private final Path generatedPacksRoot;
    private final Path templateDir;
    private final YAMLMapper yaml = new YAMLMapper();

    /**
     * Production: generated packs -> persistent {@code generated-path}; template ->
     * the read-only bundled {@code packs/_template} baked into the image.
     * {@code @Autowired} marks this as the constructor Spring uses — the class also
     * has a test-seam constructor, and without this Spring cannot disambiguate.
     */
    @Autowired
    public PackTemplateService(@Value("${ragbrain.rag.packs.generated-path:./data/packs}") String generatedPacksPath) {
        this(Path.of(generatedPacksPath), Path.of("packs", "_template"));
    }

    /** Test/seam ctor: explicit roots so unit tests never touch the real packs/. */
    public PackTemplateService(Path generatedPacksRoot, Path templateDir) {
        this.generatedPacksRoot = generatedPacksRoot;
        this.templateDir = templateDir;
    }

    /** @return the packRef string (path to the generated pack) that the registry can resolve. */
    public String generate(String slug, String companyName, String disclaimer) {
        Path target = generatedPacksRoot.resolve(slug);
        if (Files.exists(target)) {
            throw new IllegalArgumentException("pack already exists at " + target);
        }
        try {
            Files.createDirectories(target);
            for (String f : COPY_FILES) {
                Files.copy(templateDir.resolve(f), target.resolve(f));
            }
            writePackYaml(target.resolve("pack.yaml"), slug, companyName, disclaimer);
            // Fail-fast: a generated pack that the loader rejects must not survive.
            new DomainPackLoader().load(target.toAbsolutePath().normalize());
        } catch (IOException e) {
            deleteQuietly(target);
            throw new IllegalArgumentException("could not generate pack for slug '" + slug + "': " + e.getMessage(), e);
        } catch (DomainPackLoader.PackValidationException e) {
            deleteQuietly(target);
            throw new IllegalArgumentException("generated pack for slug '" + slug + "' is invalid: " + e.getMessage(), e);
        }
        return target.toString();
    }

    private void writePackYaml(Path file, String slug, String companyName, String disclaimer) throws IOException {
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put("slug", slug);
        identity.put("company-name", companyName);   // YAMLMapper quotes/escapes ':' and quotes safely
        identity.put("disclaimer", disclaimer);
        yaml.writeValue(file.toFile(), identity);
    }

    private void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {
            // best-effort cleanup of the partial dir
        }
    }
}
