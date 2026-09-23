package com.pragmaticds.docengine.review.regroup;

import java.util.List;
import java.util.UUID;

/** The membership delta a reviewer submits. {@code intent} is audit metadata, not behaviour. */
public record RegroupRequest(
        String intent,
        List<Move> moves,
        List<NewDocument> newDocuments,
        List<UUID> deletedDocumentIds,
        String reason) {

    /** toDocumentId null = unassign; a value may be an existing doc id OR a newDocuments tempId. */
    public record Move(UUID pageId, String toDocumentId) {}

    public record NewDocument(String tempId, String documentTypeCode, List<UUID> pageIds) {}
}
