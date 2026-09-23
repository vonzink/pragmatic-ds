package com.pragmaticds.rag.dto;

import java.util.List;

/**
 * Create/update body for a source link. authority and surface are Strings (not
 * enums) so an unknown value yields a clean 400 via valueOf(...) in the service
 * rather than a Jackson 500. List fields default-empty in the service. No
 * jakarta.validation annotations — the service validates manually.
 */
public record SourceLinkRequest(
        String name,
        String url,
        String domain,
        String authority,
        List<String> topics,
        boolean freshnessRequired,
        List<String> allowedUse,
        List<String> doNotUseFor,
        String surface
) {}
