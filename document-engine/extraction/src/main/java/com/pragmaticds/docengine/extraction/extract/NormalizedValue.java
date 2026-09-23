package com.pragmaticds.docengine.extraction.extract;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A normalizer's output, exactly one arm populated per {@code DataType}: {@code text} for
 * STRING/ENUM, {@code number} for NUMBER/MONEY, {@code date} for DATE. {@code certainty} is the
 * normalizer-certainty confidence component (1.0 = strict-format parse, lower = lenient parse,
 * 0 = unparseable).
 */
public record NormalizedValue(
        String text, BigDecimal number, LocalDate date, BigDecimal certainty) {}
