package com.pragmaticds.rag.service.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Corpus from a local folder (a brain's local_path). Top-level supported files
 * only; an optional _manifest.json in the folder supplies metadata, otherwise
 * SyncManifest defaults apply (title derived from the filename).
 */
public class LocalFolderCorpusSource implements CorpusSource {

    static final String MANIFEST_NAME = "_manifest.json";
    private static final Set<String> SUPPORTED =
            Set.of("pdf", "docx", "txt", "md", "markdown", "html", "htm");

    private final Path root;

    public LocalFolderCorpusSource(String localPath) {
        this.root = Path.of(localPath).toAbsolutePath().normalize();
    }

    @Override
    public List<String> listFiles() {
        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(LocalFolderCorpusSource::isSupported)
                    .filter(name -> !MANIFEST_NAME.equals(name))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] fetch(String fileName) {
        try {
            return Files.readAllBytes(root.resolve(fileName));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> fetchManifest() {
        Path manifest = root.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(manifest));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static boolean isSupported(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SUPPORTED.contains(name.substring(dot + 1).toLowerCase(Locale.US));
    }
}
