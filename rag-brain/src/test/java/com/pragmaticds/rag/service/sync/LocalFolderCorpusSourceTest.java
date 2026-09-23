package com.pragmaticds.rag.service.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalFolderCorpusSourceTest {

    @Test
    void listsSupportedFilesAndReadsBytes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("guide.txt"), "hello");
        Files.write(dir.resolve("doc.pdf"), new byte[]{1, 2, 3});
        Files.write(dir.resolve("image.png"), new byte[]{9});
        Files.writeString(dir.resolve("_manifest.json"), "{\"defaults\":{}}");

        LocalFolderCorpusSource src = new LocalFolderCorpusSource(dir.toString());

        assertEquals(List.of("doc.pdf", "guide.txt"), src.listFiles());     // sorted, no png, no manifest
        assertArrayEquals(new byte[]{1, 2, 3}, src.fetch("doc.pdf"));
        assertTrue(src.fetchManifest().isPresent());
    }

    @Test
    void missingManifestIsEmpty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.md"), "x");
        assertTrue(new LocalFolderCorpusSource(dir.toString()).fetchManifest().isEmpty());
        assertEquals(List.of("a.md"), new LocalFolderCorpusSource(dir.toString()).listFiles());
    }
}
