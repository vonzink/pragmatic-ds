package com.pragmaticds.rag.lab.connect;

import com.pragmaticds.rag.service.analyze.calc.LoanBasis;

import java.util.List;
import java.util.UUID;

/**
 * Everything a connector may say about a run, which is deliberately almost nothing.
 *
 * <p>A package, a revision, a source selection, and who is asking. There is no field for a
 * release, a candidate, a provider, a model, a prompt, a tool, a schema, a corpus, a price, a
 * fallback, or a budget — not validated away, but structurally absent, so no future caller can
 * discover a hidden override because there is nothing to discover. Everything those fields would
 * have named is resolved server-side from the current live release.
 *
 * @param loanFacts the loan-level facts an assets run's large-deposit rule is selected from, or
 *     null. Deliberately absent from {@code externalRequestSha256}: what a registration IS — the
 *     package, the revision, the reconciled source set — decides replay, and the loan it belongs
 *     to does not. Sending facts must never turn a retry into a second run group.
 * @param subjectScope the host app's opaque per-loan scope, or null. Deliberately absent from
 *     {@code externalRequestSha256} on the same rule as {@code loanFacts}: what a registration IS
 *     decides replay, and the loan it belongs to does not. In the digest, a retry that attached a
 *     scope would fork a second run group and bill twice for one registration. Opaque by
 *     contract — it is derived by the Suite from a loan id and is never reversed here; this
 *     process does not learn which loan a run belongs to, only that two runs belong to the same
 *     one.
 */
public record DocumentManagerRunCommand(
        UUID connectorClientId,
        UUID brainId,
        String instanceSlug,
        String tenantId,
        String externalRequestId,
        UUID packageId,
        int revision,
        List<UUID> selectedSourceIds,
        LoanBasis loanFacts,
        String subjectScope) {

    public DocumentManagerRunCommand {
        selectedSourceIds = selectedSourceIds == null ? List.of() : List.copyOf(selectedSourceIds);
    }

    /** The pre-loan-facts signature, for callers that supply none. */
    public DocumentManagerRunCommand(UUID connectorClientId, UUID brainId, String instanceSlug,
                                     String tenantId, String externalRequestId, UUID packageId,
                                     int revision, List<UUID> selectedSourceIds) {
        this(connectorClientId, brainId, instanceSlug, tenantId, externalRequestId, packageId,
                revision, selectedSourceIds, null, null);
    }
}
