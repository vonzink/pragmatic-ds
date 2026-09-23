package com.pragmaticds.rag.domain;

/**
 * One inline internal link on a page guide (spec §6.1, internal_links
 * [{label,url}]). Stored inside BrainPageGuide as a jsonb List&lt;LinkRef&gt; via
 * @JdbcTypeCode(SqlTypes.JSON) — Hibernate's Jackson serializer round-trips a
 * List of this record the same way it round-trips List&lt;String&gt; and the
 * AuditLog's List&lt;Map&gt;. Internal links are inline (NOT registry rows);
 * external sources are referenced by id via source_link_ids instead.
 */
public record LinkRef(String label, String url) {
}
