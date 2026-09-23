package com.pragmaticds.rag.service.sync;

import java.util.UUID;

/** One planned step of a corpus sync. Reason strings surface in the report. */
public record SyncAction(String fileName, Type type, String reason,
                         UUID documentId, SyncManifest.Entry meta) {

    public enum Type { UPLOAD, UPDATE, REACTIVATE, DEACTIVATE, SKIP }

    static SyncAction of(String fileName, Type type, String reason,
                         UUID documentId, SyncManifest.Entry meta) {
        return new SyncAction(fileName, type, reason, documentId, meta);
    }
}
