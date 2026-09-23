package com.pragmaticds.rag.lab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pragmaticds.rag.lab.analyze.InstanceRunProvenance;
import com.pragmaticds.rag.lab.domain.LabDiscussionExchange;
import com.pragmaticds.rag.lab.domain.LabDiscussionMessage;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.domain.LabRunPayload;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.model.InstanceCostEstimator;
import com.pragmaticds.rag.lab.model.InstanceTokenEstimator;
import com.pragmaticds.rag.lab.model.ModelEstimate;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabDiscussionExchangeRepository;
import com.pragmaticds.rag.lab.repository.LabDiscussionMessageRepository;
import com.pragmaticds.rag.lab.repository.LabRunPayloadRepository;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.domain.LabDiscussionModelUsage;
import com.pragmaticds.rag.lab.run.domain.UsageQuality;
import com.pragmaticds.rag.lab.run.repository.LabDiscussionModelUsageRepository;
import com.pragmaticds.rag.lab.security.LabPayloadCipher;
import com.pragmaticds.rag.provider.AiRequest;
import com.pragmaticds.rag.provider.AiResponse;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers a follow-up question from what one run already saved, and from nothing else.
 *
 * <p><b>Zero re-execution.</b> No Document Engine call, no compatibility check, no embedding, no
 * retrieval, no deterministic tool, no snapshot freeze, and no re-analysis. The parsed facts, the
 * retrieved evidence, the tool record, and the answer all come out of the run's sealed provenance
 * and output. A question about an answer has to be answered from the material that produced it,
 * or it is a new run wearing a conversation's clothes — and it would silently drift as the corpus
 * changed underneath it.
 *
 * <p><b>Pinned all the way through.</b> The turn goes to the run's own provider and model, priced
 * against the run's own catalog version, with {@link ModelRouterService.FallbackPolicy#NONE} so no
 * substitution can quietly answer as something else.
 *
 * <p><b>It fails rather than trimming.</b> A saved context that will not fit the release's
 * discussion budget is refused. Dropping evidence to make it fit would ground the follow-up in
 * less than the run was grounded in, while still presenting itself as being about that run.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstanceDiscussionService {

    private static final Logger log = LoggerFactory.getLogger(InstanceDiscussionService.class);

    /** Matches the Lab prototype's exchange lease: long enough for one turn, short enough to reclaim. */
    private static final Duration EXCHANGE_LEASE = Duration.ofMinutes(5);

    /** Why a turn cannot be taken. Stable, value-free codes; never a question or an answer. */
    public static final class DiscussionException extends RuntimeException {
        public enum Code {
            DISCUSSION_REQUEST_INVALID,
            DISCUSSION_RUN_NOT_FOUND,
            RUN_NOT_SUCCEEDED,
            RUN_PROVENANCE_ABSENT,
            RUN_OUTPUT_ABSENT,
            DISCUSSION_CONTEXT_TOO_LARGE,
            INSTANCE_RELEASE_NOT_PINNABLE,
            DISCUSSION_PROVIDER_FAILED
        }

        private final Code code;

        public DiscussionException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    /** One stored turn. Bodies are decrypted only for an authorized detail read. */
    public record Turn(int sequenceNumber, String question, String answer, String status,
                       String failureCode) {}

    /** A transcript plus what it cost. */
    public record DiscussionView(
            UUID runId, List<Turn> turns, String provider, String model,
            String usageQuality, BigDecimal estimatedCostUsd) {
        public DiscussionView {
            turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        }
    }

    private final LabRunRepository runs;
    private final LabRunPayloadRepository payloads;
    private final LabDiscussionExchangeRepository exchanges;
    private final LabDiscussionMessageRepository messages;
    private final LabDiscussionModelUsageRepository discussionUsage;
    private final LabRunTransactionService transactions;
    private final LabPayloadCipher cipher;
    private final InstanceReleaseResolver releases;
    private final ModelRouterService router;
    private final InstanceCostEstimator estimator;
    private final InstanceTokenEstimator tokens;
    private final ObjectMapper mapper;

    public InstanceDiscussionService(LabRunRepository runs,
                                     LabRunPayloadRepository payloads,
                                     LabDiscussionExchangeRepository exchanges,
                                     LabDiscussionMessageRepository messages,
                                     LabDiscussionModelUsageRepository discussionUsage,
                                     LabRunTransactionService transactions,
                                     LabPayloadCipher cipher,
                                     InstanceReleaseResolver releases,
                                     ModelRouterService router,
                                     InstanceCostEstimator estimator,
                                     InstanceTokenEstimator tokens,
                                     ObjectMapper mapper) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.discussionUsage = Objects.requireNonNull(discussionUsage, "discussionUsage");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.router = Objects.requireNonNull(router, "router");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * The transcript so far, with its bodies. Reads only.
     *
     * <p>This decrypts, and is meant to: it is a single authorized read of one run the caller
     * already named, which is the same privilege the run-group detail route exercises. The rule
     * this respects is that a <em>listing</em> never decrypts — and there is no listing here.
     * Without it {@code ask} could take a turn and never hand back the answer.
     */
    public DiscussionView read(UUID brainId, UUID runId) {
        LabRun run = requireSucceeded(brainId, runId);
        List<LabDiscussionExchange> stored = exchanges.findByRunIdOrderBySequenceNumberAsc(runId);
        List<LabDiscussionModelUsage> spend = usageRows(brainId, stored);
        return new DiscussionView(runId, transcript(brainId, runId, stored),
                run.getRequestedProvider(), run.getRequestedModel(),
                spentQuality(spend), spent(spend));
    }

    /**
     * Appends one turn, answering from the run's saved context.
     *
     * <p>The exchange claim is the serializer: the same idempotency key returns the existing turn
     * without a second provider call, and two concurrent turns cannot interleave their ordinals.
     */
    public DiscussionView ask(UUID brainId, UUID runId, String question, String idempotencyKey) {
        if (question == null || question.isBlank() || idempotencyKey == null
                || idempotencyKey.isBlank()) {
            throw new DiscussionException(DiscussionException.Code.DISCUSSION_REQUEST_INVALID);
        }
        LabRun run = requireSucceeded(brainId, runId);
        InstanceReleaseManifest manifest = manifestOf(run);
        InstanceDiscussionContext context = context(brainId, run, manifest, question);

        LabRunTransactionService.ExchangeClaim claim =
                transactions.claimExchange(brainId, runId, idempotencyKey, EXCHANGE_LEASE);
        if (!claim.fresh()) {
            // Someone already asked this exact turn. Returning the transcript costs nothing and
            // charges nothing; re-running it would bill a second provider call for one question.
            return read(brainId, runId);
        }

        UUID exchangeId = claim.exchange().getId();
        try {
            AiResponse answer = call(brainId, run, manifest, context, question);
            transactions.completeExchange(brainId, runId, exchangeId,
                    question.getBytes(StandardCharsets.UTF_8),
                    answer.content().getBytes(StandardCharsets.UTF_8));
            recordUsage(exchangeId, brainId, run, answer);
        } catch (DiscussionException refused) {
            transactions.failExchange(exchangeId, refused.code().name());
            throw refused;
        } catch (RuntimeException failure) {
            // A provider exception quotes the request URI and the provider's own response body,
            // so neither its message nor its cause is stored, logged, or returned.
            log.warn("Instance discussion turn on run {} failed ({})",
                    runId, failure.getClass().getSimpleName());
            transactions.failExchange(exchangeId,
                    DiscussionException.Code.DISCUSSION_PROVIDER_FAILED.name());
            throw new DiscussionException(DiscussionException.Code.DISCUSSION_PROVIDER_FAILED);
        }
        return read(brainId, runId);
    }

    // ================================================================ internals

    /**
     * Assembles the saved context and checks it against the release's discussion budget.
     *
     * <p>The budget covers the context AND the question, because the question is part of what the
     * model must read. Checking only the context would let a long question push the turn past a
     * ceiling the release actually set.
     */
    private InstanceDiscussionContext context(UUID brainId, LabRun run,
                                              InstanceReleaseManifest manifest, String question) {
        InstanceRunProvenance provenance = provenance(brainId, run);
        String answerJson = new String(open(brainId, run.getId(),
                LabRunPayload.PayloadType.ANALYSIS_OUTPUT,
                LabPayloadCipher.RecordType.ANALYSIS_OUTPUT,
                DiscussionException.Code.RUN_OUTPUT_ABSENT), StandardCharsets.UTF_8);

        List<String> everything = new ArrayList<>();
        everything.add(answerJson);
        everything.add(question);
        provenance.retrieved().forEach(chunk -> everything.add(chunk.content()));
        long upperBound = tokens.estimateAll(everything).max();

        if (upperBound > manifest.limits().maximumDiscussionTokens()) {
            // Refused, not trimmed: an answer grounded in less than the run was, presented as
            // being about that run, is worse than no answer.
            throw new DiscussionException(
                    DiscussionException.Code.DISCUSSION_CONTEXT_TOO_LARGE);
        }
        return InstanceDiscussionContext.of(provenance, "", answerJson, upperBound);
    }

    /**
     * One pinned provider call.
     *
     * <p>Sanitized and with fallback refused, so a turn cannot be answered by a model the run was
     * never pinned to and a provider failure cannot carry its body into a log line.
     */
    private AiResponse call(UUID brainId, LabRun run, InstanceReleaseManifest manifest,
                            InstanceDiscussionContext context, String question) {
        StringBuilder prompt = new StringBuilder()
                .append(manifest.behavior().systemPrompt()).append("\n\n")
                .append("The following analysis has already been produced. Answer the question "
                        + "using only this material; do not introduce new facts.\n\n")
                .append("ANALYSIS:\n").append(context.answerJson()).append("\n\n");
        if (!context.evidence().isEmpty()) {
            prompt.append("EVIDENCE:\n");
            for (InstanceDiscussionContext.Evidence chunk : context.evidence()) {
                prompt.append("- ").append(chunk.documentTitle()).append(": ")
                        .append(chunk.content()).append('\n');
            }
            prompt.append('\n');
        }
        prompt.append("QUESTION:\n").append(question);

        AiRequest request = AiRequest.forAnalysis(prompt.toString(), List.of(),
                manifest.limits().maximumOutputTokens(),
                run.getRequestedProvider(), run.getRequestedModel(),
                // The release's own temperature, so a follow-up answers with the same settledness
                // the run did rather than at a lane default the release never chose.
                manifest.behavior().temperature() == null
                        ? AiRequest.ANALYZE_TEMPERATURE
                        : manifest.behavior().temperature().doubleValue());
        ModelRouterService.SanitizedResponse routed = router.generateSanitized(
                request, brainId, "discussion-" + run.getId(),
                ModelRouterService.FallbackPolicy.NONE);
        return routed.response();
    }

    /** Records the turn's cost against the run's pinned catalog version. */
    private void recordUsage(UUID exchangeId, UUID brainId, LabRun run, AiResponse answer) {
        LabDiscussionModelUsage usage = new LabDiscussionModelUsage(exchangeId, brainId,
                run.getPricingVersionId(), run.getRequestedProvider(), run.getRequestedModel());
        ModelEstimate.ActualCost cost = estimator.priceActual(run.getPricingVersionId(),
                run.getRequestedProvider(), run.getRequestedModel(),
                new ModelEstimate.ProviderUsage(
                        answer.promptTokens() == null ? null : answer.promptTokens().longValue(),
                        answer.cachedPromptTokens() == null
                                ? null : answer.cachedPromptTokens().longValue(),
                        answer.completionTokens() == null
                                ? null : answer.completionTokens().longValue()));
        if (cost.quality() == UsageQuality.UNAVAILABLE) {
            // The provider told us nothing. Left unavailable rather than zeroed, exactly as a
            // run's own usage is.
            usage.unavailable();
        } else {
            usage.report(cost.quality(), null, null, null, cost.totalTokens(), cost.costUsd());
        }
        discussionUsage.saveAndFlush(usage);
    }

    private InstanceRunProvenance provenance(UUID brainId, LabRun run) {
        byte[] bytes = open(brainId, run.getId(), LabRunPayload.PayloadType.RUN_PROVENANCE,
                LabPayloadCipher.RecordType.RUN_PROVENANCE,
                DiscussionException.Code.RUN_PROVENANCE_ABSENT);
        try {
            return mapper.readValue(bytes, InstanceRunProvenance.class);
        } catch (Exception unreadable) {
            throw new DiscussionException(DiscussionException.Code.RUN_PROVENANCE_ABSENT);
        }
    }

    private byte[] open(UUID brainId, UUID runId, LabRunPayload.PayloadType payloadType,
                        LabPayloadCipher.RecordType recordType, DiscussionException.Code absent) {
        LabRunPayload payload = payloads.findByRunIdAndPayloadType(runId, payloadType)
                .orElseThrow(() -> new DiscussionException(absent));
        return cipher.open(brainId, runId, payload.getId(), recordType,
                new LabPayloadCipher.SealedPayload(payload.getNonce(), payload.getCiphertext()));
    }

    /**
     * Builds the transcript, opening each turn's two sealed bodies.
     *
     * <p>Bodies are fetched in one query for the whole transcript rather than per turn, so a long
     * discussion does not turn into one round trip per message.
     *
     * <p>A failed turn has no bodies at all — {@code failExchange} stores neither — so it appears
     * with its code and nulls rather than being hidden. A caller needs to see that the turn was
     * attempted.
     */
    private List<Turn> transcript(UUID brainId, UUID runId,
                                  List<LabDiscussionExchange> stored) {
        Map<UUID, List<LabDiscussionMessage>> byExchange = new HashMap<>();
        if (!stored.isEmpty()) {
            messages.findByExchangeIdInOrderByOrdinalAsc(
                            stored.stream().map(LabDiscussionExchange::getId).toList())
                    .forEach(message -> byExchange
                            .computeIfAbsent(message.getExchangeId(), key -> new ArrayList<>())
                            .add(message));
        }

        List<Turn> turns = new ArrayList<>(stored.size());
        for (LabDiscussionExchange exchange : stored) {
            String question = null;
            String answer = null;
            for (LabDiscussionMessage message : byExchange.getOrDefault(exchange.getId(),
                    List.of())) {
                if (message.getRole() == LabDiscussionMessage.Role.USER) {
                    question = open(brainId, runId, message);
                } else {
                    answer = open(brainId, runId, message);
                }
            }
            turns.add(new Turn(exchange.getSequenceNumber(), question, answer,
                    exchange.getStatus().name(), exchange.getFailureCode()));
        }
        return turns;
    }

    /** Opens one sealed message body under the record type its role was sealed with. */
    private String open(UUID brainId, UUID runId, LabDiscussionMessage message) {
        LabPayloadCipher.RecordType type = message.getRole() == LabDiscussionMessage.Role.USER
                ? LabPayloadCipher.RecordType.DISCUSSION_USER
                : LabPayloadCipher.RecordType.DISCUSSION_ASSISTANT;
        return new String(cipher.open(brainId, runId, message.getId(), type,
                new LabPayloadCipher.SealedPayload(message.getNonce(), message.getCiphertext())),
                StandardCharsets.UTF_8);
    }

    /**
     * What the discussion has cost so far, summed over the turns that were actually measured.
     *
     * <p>Null when nothing has been measured, rather than zero — the same absent-is-not-zero rule
     * the usage rows themselves keep. {@link #spentQuality} says whether this total is complete.
     */
    private static BigDecimal spent(List<LabDiscussionModelUsage> rows) {
        BigDecimal total = null;
        for (LabDiscussionModelUsage row : rows) {
            if (row.getActualCostUsd() != null) {
                total = total == null ? row.getActualCostUsd() : total.add(row.getActualCostUsd());
            }
        }
        return total;
    }

    /**
     * How much of that total is a measurement.
     *
     * <p>Any unmeasured turn makes the sum an understatement, so the whole total reports as
     * {@link UsageQuality#UNAVAILABLE} rather than presenting a partial figure as a complete one.
     * A turn still pending has the same effect, because its cost is not in the sum yet either.
     */
    private static String spentQuality(List<LabDiscussionModelUsage> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        for (LabDiscussionModelUsage row : rows) {
            if (row.getUsageQuality() != UsageQuality.REPORTED
                    && row.getUsageQuality() != UsageQuality.INFERRED) {
                return UsageQuality.UNAVAILABLE.name();
            }
        }
        return UsageQuality.REPORTED.name();
    }

    private List<LabDiscussionModelUsage> usageRows(UUID brainId,
                                                    List<LabDiscussionExchange> stored) {
        if (stored.isEmpty()) {
            return List.of();
        }
        return discussionUsage.findByExchangeIdInAndBrainId(
                stored.stream().map(LabDiscussionExchange::getId).toList(), brainId);
    }

    private InstanceReleaseManifest manifestOf(LabRun run) {
        var release = releases.byId(
                new InstanceKey(run.getBrainId(), run.getInstanceSlug()), run.getReleaseId());
        if (release.manifest() instanceof DecodedInstanceManifest.V2 v2) {
            return v2.manifest();
        }
        throw new DiscussionException(DiscussionException.Code.INSTANCE_RELEASE_NOT_PINNABLE);
    }

    private LabRun requireSucceeded(UUID brainId, UUID runId) {
        if (brainId == null || runId == null) {
            throw new DiscussionException(DiscussionException.Code.DISCUSSION_REQUEST_INVALID);
        }
        Optional<LabRun> found = runs.findById(runId)
                .filter(run -> brainId.equals(run.getBrainId()));
        LabRun run = found.orElseThrow(() ->
                new DiscussionException(DiscussionException.Code.DISCUSSION_RUN_NOT_FOUND));
        if (run.getStatus() != LabRun.Status.SUCCEEDED) {
            // Only a succeeded run has an answer to ask about.
            throw new DiscussionException(DiscussionException.Code.RUN_NOT_SUCCEEDED);
        }
        return run;
    }
}
