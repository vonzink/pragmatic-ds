package com.pragmaticds.docengine.parsing.client;

import java.util.List;

/**
 * The {@code /v1/layout} request JSON part: spans the caller already extracted, riding IN so the
 * worker stays stateless and layout works identically for native and OCR'd pages.
 */
public record LayoutRequest(List<LayoutRequestPage> pages) {}
