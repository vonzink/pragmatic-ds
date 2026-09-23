package com.pragmaticds.docengine.parsing.client;

import java.util.Map;

/**
 * The {@code "worker"} version block every success response carries (contract invariant 5) —
 * persisted into {@code processing_stage.worker_version} / {@code parser_versions}. Library
 * versions may be null (the worker reports null for absent libraries, never a fabricated string).
 */
public record WorkerBlock(String version, Map<String, String> libraries) {

    /** Library version by name, or the worker version when the library is not reported. */
    public String libraryOr(String name, String fallback) {
        String v = libraries == null ? null : libraries.get(name);
        return v != null ? v : fallback;
    }
}
