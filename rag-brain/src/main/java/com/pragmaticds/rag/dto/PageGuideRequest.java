package com.pragmaticds.rag.dto;

import java.util.List;

/**
 * Create/update body for a page guide. surface is a String (not the enum) so an
 * unknown value yields a clean 400 via valueOf(...) in the service rather than a
 * Jackson 500. sourceLinkIds are Strings converted with UUID.fromString in the
 * service (a malformed UUID → IllegalArgumentException → 400). internalLinks is a
 * list of the nested LinkRefRequest {label,url}. route is nullable. List fields
 * default-empty in the service. No jakarta.validation annotations — the service
 * validates manually.
 */
public record PageGuideRequest(
        String route,
        String title,
        String purpose,
        String surface,
        List<String> userIntents,
        List<String> allowedGuidance,
        List<LinkRefRequest> internalLinks,
        List<String> sourceLinkIds,
        List<String> topics
) {

    /** Inline {label,url} pair on a create/update body. */
    public record LinkRefRequest(String label, String url) {}
}
