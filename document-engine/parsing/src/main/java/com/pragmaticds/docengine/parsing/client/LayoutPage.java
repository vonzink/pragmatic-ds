package com.pragmaticds.docengine.parsing.client;

import java.util.List;

/**
 * One page of the {@code /v1/layout} response. {@code notImplemented} lists the element types no
 * detector exists for (Phase 3: CHECKBOX, SIGNATURE) — an empty element list is distinguishable
 * from "looked and found none" only because of it. NULL means the worker never sent the key (the
 * contract requires it; an older or loosely conforming build may omit it): no declaration was
 * made, which is a different statement from the empty list's "looked for everything".
 */
public record LayoutPage(
        int pageIndex, List<LayoutWireElement> elements, List<String> notImplemented) {}
