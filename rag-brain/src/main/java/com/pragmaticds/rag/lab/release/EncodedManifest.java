package com.pragmaticds.rag.lab.release;

import java.util.Map;
import java.util.Objects;

/** Canonical typed-manifest JSON tree together with the lowercase SHA-256 of its canonical bytes. */
public record EncodedManifest(Map<String, Object> json, String sha256) {
    public EncodedManifest {
        json = InstanceManifestValues.immutableMap(Objects.requireNonNull(json, "json"));
        sha256 = Objects.requireNonNull(sha256, "sha256");
    }
}
