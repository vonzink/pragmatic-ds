package com.pragmaticds.docengine.parsing.client;

import java.math.BigDecimal;
import java.util.List;

/**
 * One layout element from {@code /v1/layout}. {@code elementId}/{@code parentElementId} are
 * REQUEST-SCOPED strings expressing table → row → cell nesting — persistence resolves them to row
 * UUIDs. {@code spanOrdinals} refer to the caller's span ordinals from the request.
 * {@code attributesJson} is the worker's attributes object re-serialized with exact decimals —
 * stored verbatim into {@code layout_element.attributes}.
 */
public record LayoutWireElement(
        String elementId,
        String parentElementId,
        String elementType,
        int ordinal,
        BigDecimal x,
        BigDecimal y,
        BigDecimal width,
        BigDecimal height,
        BigDecimal confidence,
        String detector,
        String detectorVersion,
        String attributesJson,
        List<Integer> spanOrdinals) {}
