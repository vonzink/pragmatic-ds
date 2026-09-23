package com.pragmaticds.docengine.results.service;

import java.util.UUID;

/** One derivation for the tenant-bound, content-addressed engine-result object key. */
final class EngineResultStorageKey {

    private EngineResultStorageKey() {}

    static String forEnvelope(UUID orgId, String envelopeSha256) {
        if (orgId == null
                || envelopeSha256 == null
                || !envelopeSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid engine result digest");
        }
        return "org/"
                + orgId
                + "/engine-results/sha256/"
                + envelopeSha256
                + ".json";
    }
}
