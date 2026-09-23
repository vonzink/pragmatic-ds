package com.pragmaticds.rag.service.sync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S3CorpusSourceTest {

    @Test
    void filterKeysStripsPrefixAndExcludesManifestAndFolders() {
        List<String> files = S3CorpusSource.filterKeys("rag-brain/", List.of(
                "rag-brain/",                       // the prefix itself
                "rag-brain/_manifest.json",         // manifest
                "rag-brain/guides/",                // folder marker
                "rag-brain/selling-guide.pdf",
                "rag-brain/guides/fha-handbook.pdf"));

        assertEquals(List.of("selling-guide.pdf", "guides/fha-handbook.pdf"), files);
    }
}
