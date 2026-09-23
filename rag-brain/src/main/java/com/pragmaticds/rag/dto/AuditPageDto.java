package com.pragmaticds.rag.dto;

import java.util.List;

/** Stable page envelope (Spring's Page serialization is not a public contract). */
public record AuditPageDto(List<AuditLogListDto> items, int page, int size, long total) {
}
