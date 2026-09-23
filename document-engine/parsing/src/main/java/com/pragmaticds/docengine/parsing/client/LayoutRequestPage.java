package com.pragmaticds.docengine.parsing.client;

import java.math.BigDecimal;
import java.util.List;

/** One page of a {@code /v1/layout} request: rotation-0 geometry plus the page's spans. */
public record LayoutRequestPage(
        int pageIndex, BigDecimal widthPt, BigDecimal heightPt, List<LayoutRequestSpan> spans) {}
