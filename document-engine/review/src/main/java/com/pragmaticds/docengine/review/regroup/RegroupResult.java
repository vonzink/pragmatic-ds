package com.pragmaticds.docengine.review.regroup;

import com.pragmaticds.docengine.classification.web.PackageDocumentsView;

/** The regrouped package, in the same shape {@code GET /v1/packages/{id}/documents} returns. */
public record RegroupResult(PackageDocumentsView documents) {}
