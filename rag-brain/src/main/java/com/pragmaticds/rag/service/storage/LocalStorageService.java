package com.pragmaticds.rag.service.storage;

import com.pragmaticds.rag.config.RagProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Local-disk storage for the MVP. Files land under ragbrain.rag.storage.path.
 * Replace with an S3StorageService for AWS deployment — same interface.
 */
@Service
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(RagProperties properties) {
        this.root = Path.of(properties.storage().path()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create document storage directory: " + root, e);
        }
    }

    @Override
    public String store(String fileName, InputStream content) {
        // Prefix with a UUID so duplicate filenames never collide.
        String key = UUID.randomUUID() + "_" + sanitize(fileName);
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Invalid file name: " + fileName);
        }
        try {
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file: " + fileName, e);
        }
        return key;
    }

    @Override
    public InputStream retrieve(String storageKey) {
        Path source = root.resolve(storageKey).normalize();
        if (!source.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key: " + storageKey);
        }
        try {
            return Files.newInputStream(source);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read stored file: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key: " + storageKey);
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete stored file: " + storageKey, e);
        }
    }

    private String sanitize(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
