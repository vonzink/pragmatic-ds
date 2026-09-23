package com.pragmaticds.docengine.extraction.schema;

/**
 * Where SIGNATURE_PRESENCE looks: the label anchor that locates the signature block (e.g.
 * "Buyer's Signature") and the window that grows the label's box into the search region.
 */
public record RegionSpec(LabelSpec label, Window windowPt) {}
