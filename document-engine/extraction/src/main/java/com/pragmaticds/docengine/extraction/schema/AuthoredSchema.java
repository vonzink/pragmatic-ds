package com.pragmaticds.docengine.extraction.schema;

import java.util.List;
import java.util.UUID;

/**
 * What one accepted authoring write did: the row it created, and the versions it retired to make
 * room for it.
 *
 * <p>{@code retiredVersions} is part of the result rather than a detail of the implementation
 * because supersession is the one visible side effect on schemas the author did not name. An
 * author who posts {@code 2.0.0} for PAYSTUB has just stopped {@code 1.0.0} from applying, and the
 * response is where they find that out — not the next extraction run.
 */
public record AuthoredSchema(
        UUID id,
        String documentTypeCode,
        String version,
        boolean active,
        List<String> retiredVersions) {}
