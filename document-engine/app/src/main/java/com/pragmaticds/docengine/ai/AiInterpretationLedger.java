package com.pragmaticds.docengine.ai;

import com.pragmaticds.docengine.platform.ai.AiExtractionResult;
import com.pragmaticds.docengine.platform.ai.AiTokenCounts;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only provider-call and proposal ledger over the table created by V8. */
@Repository
public class AiInterpretationLedger {

    private final JdbcTemplate jdbc;

    public AiInterpretationLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void record(
            UUID logicalDocumentId,
            AiExtractionResult result,
            String promptVersion,
            String interpretationJson) {
        AiTokenCounts tokens =
                result == null || result.tokenCounts() == null
                        ? AiTokenCounts.ZERO
                        : result.tokenCounts();
        long allInputTokens =
                Math.addExact(
                        Math.addExact(tokens.inputTokens(), tokens.cacheReadInputTokens()),
                        tokens.cacheWriteInputTokens());
        jdbc.update(
                """
                INSERT INTO ai_interpretation
                    (id, org_id, subject_type, subject_id, provider, model, prompt_version,
                     interpretation, tokens_in, tokens_out, cost_usd)
                VALUES (?, ?, 'LOGICAL_DOCUMENT', ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, NULL)
                """,
                UUID.randomUUID(),
                TenantContext.require(),
                logicalDocumentId,
                safe(result == null ? null : result.provider()),
                safe(result == null ? null : result.model()),
                promptVersion,
                interpretationJson,
                Math.toIntExact(allInputTokens),
                Math.toIntExact(tokens.outputTokens()));
    }

    /** Retention-only removal of polymorphic rows whose subjects belong to one purged package. */
    public int deleteBySubjectIdsAndOrgId(Set<UUID> subjectIds, UUID orgId) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return 0;
        }
        String placeholders =
                String.join(
                        ", ", java.util.Collections.nCopies(subjectIds.size(), "?"));
        ArrayList<Object> arguments = new ArrayList<>(subjectIds.size() + 1);
        arguments.add(orgId);
        arguments.addAll(subjectIds);
        return jdbc.update(
                "DELETE FROM ai_interpretation WHERE org_id = ? AND subject_id IN ("
                        + placeholders
                        + ")",
                arguments.toArray());
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
