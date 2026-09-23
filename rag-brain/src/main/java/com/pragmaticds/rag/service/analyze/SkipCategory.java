package com.pragmaticds.rag.service.analyze;

/** Why a document was excluded from an analyze run. Surfaced to the suite so it can
 *  prompt an analyst to key in the missing figures. */
public enum SkipCategory {
    OVER_DOC_CAP,
    OVER_SIZE_CAP,
    UNSUPPORTED_TYPE,
    UNREADABLE_PDF,
    OVER_PAGE_CAP
}
