package com.pragmaticds.rag.service.sync;

import java.util.List;
import java.util.Optional;

/** Where the corpus lives. Abstracted so SyncService is testable without S3. */
public interface CorpusSource {

    /** Object basenames under the corpus prefix — manifest and folder keys excluded. */
    List<String> listFiles();

    /** Raw bytes of one corpus file (basename, not full key). */
    byte[] fetch(String fileName);

    /** The manifest JSON bytes, if present. */
    Optional<byte[]> fetchManifest();
}
