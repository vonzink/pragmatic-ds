package com.pragmaticds.docengine.parsing.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/**
 * One span of a {@code /v1/layout} request page. {@code ordinal} is the caller's identifier —
 * element→span links in the response refer to it. {@code fontSize}/{@code fontName} are optional
 * (OCR spans have neither) and are OMITTED when null rather than sent as JSON null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LayoutRequestSpan(
        int ordinal,
        String text,
        BigDecimal x,
        BigDecimal y,
        BigDecimal width,
        BigDecimal height,
        BigDecimal fontSize,
        String fontName) {}
