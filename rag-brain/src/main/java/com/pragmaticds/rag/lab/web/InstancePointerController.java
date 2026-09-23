package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstancePromotionService;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabInstancePointerRepository;
import com.pragmaticds.rag.lab.run.repository.LabInstancePointerEventRepository;
import com.pragmaticds.rag.lab.service.LabAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What is live, how it got there, and what a release is configured to do.
 *
 * <p>Two reads that promotion and configuration cannot work without.
 *
 * <h2>The pointer</h2>
 *
 * <p>Moving the live pointer is a compare-and-set: the caller states which release it believes is
 * live <em>and at which pointer version</em>, and the move applies only if both still hold. Without
 * the version, a rollback followed by re-promoting the same release is indistinguishable from no
 * change at all and the check wrongly succeeds. Until now the version was only ever returned <em>by
 * a move</em>, so a client could satisfy the check exactly once per session and never after a
 * colleague moved the pointer — which is precisely the case the check exists for.
 *
 * <p>The history alongside it is the pointer's own append-only event log: what moved, in which
 * direction, by whom, and why. It is the record of every change to what customers were answered
 * with, which is worth reading in one place rather than reconstructing from release numbers.
 *
 * <h2>The configuration</h2>
 *
 * <p>A release is immutable, and editing one means authoring a new candidate carrying the whole
 * definition — which a client can only do if it can see the definition it is starting from.
 *
 * <p><b>Except the prompts.</b> {@code behavior} is omitted from this view and served nowhere.
 * Prompt text is the one part of a manifest that is genuinely sensitive to disclose, and a route
 * returning it would put it in reach of every reader for the sake of a convenience. The cost is
 * real and accepted: re-authoring a release means writing its prompts again. Everything else — the
 * parser contract, the model, the pinned collections, the tools, the output schema, the limits and
 * the evaluation gate — is identifiers, versions, digests and numbers, and is served.
 */
@RestController
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
@RequestMapping("/api/ai/admin/instances")
public final class InstancePointerController {

    private final LabInstancePointerRepository pointers;
    private final LabInstancePointerEventRepository pointerEvents;
    private final InstanceReleaseResolver releases;
    /**
     * Asked whether a move is permitted, never asked to make one.
     *
     * <p>Read from the service that enforces the switch rather than from the property a second
     * time, so what this route reports and what a move actually does are the same fact.
     */
    private final InstancePromotionService promotions;

    public InstancePointerController(LabInstancePointerRepository pointers,
                                     LabInstancePointerEventRepository pointerEvents,
                                     InstanceReleaseResolver releases,
                                     InstancePromotionService promotions) {
        this.pointers = Objects.requireNonNull(pointers, "pointers");
        this.pointerEvents = Objects.requireNonNull(pointerEvents, "pointerEvents");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.promotions = Objects.requireNonNull(promotions, "promotions");
    }

    /** One movement, as it was recorded. Never rewritten and never reordered. */
    public record PointerEventView(
            String action, UUID fromReleaseId, UUID toReleaseId, long pointerVersion,
            String actorId, String changeReason, OffsetDateTime occurredAt) {}

    /**
     * The live pointer and everything that moved it.
     *
     * <p>{@code liveReleaseId} is null and {@code pointerVersion} is zero when nothing has ever
     * been promoted. A caller promoting into that state asserts the same thing by sending a null
     * expected release, so the two views agree without a special case.
     *
     * <p>{@code promotionEnabled} rides along because there is no point offering an operator a
     * move on a deployment where the switch is off; without it the only way to learn that is to
     * compose a move and read {@code INSTANCE_PROMOTION_DISABLED} off the failure.
     */
    public record PointerHistoryView(
            UUID liveReleaseId, long pointerVersion, boolean promotionEnabled,
            List<PointerEventView> events) {}

    @GetMapping(value = "/{instance}/pointer", produces = MediaType.APPLICATION_JSON_VALUE)
    public PointerHistoryView pointer(@RequestParam UUID brain, @PathVariable String instance) {
        var current = pointers.findByBrainIdAndInstanceSlug(brain, instance);
        List<PointerEventView> events = pointerEvents
                .findByBrainIdAndInstanceSlugOrderByPointerVersionDesc(brain, instance)
                .stream()
                .map(event -> new PointerEventView(
                        event.getAction().name(), event.getFromReleaseId(), event.getToReleaseId(),
                        event.getPointerVersion(), event.getActorId(), event.getChangeReason(),
                        event.getCreatedAt()))
                .toList();

        return new PointerHistoryView(
                current.map(pointer -> pointer.getProductionReleaseId()).orElse(null),
                current.map(pointer -> pointer.getPointerVersion()).orElse(0L),
                promotions.promotionEnabled(),
                events);
    }

    // ================================================================ configuration

    public record ParsedDataView(
            String envelopeVersion, String canonicalizationVersion,
            List<String> allowedDocumentTypes, List<String> requireAnyDocumentTypes,
            int minimumSupportedDocuments, String reviewRequired, String missingFields) {}

    public record ModelView(String provider, String model, String fallbackPolicy) {}

    public record CollectionRefView(UUID collectionId, long collectionVersion) {}

    public record ToolView(
            String name, String version, String inputSchemaSha256, String outputSchemaSha256) {}

    public record OutputView(String schemaId, String schemaSha256) {}

    public record LimitView(
            long maximumInputTokens, int maximumRetrievedTokens, int maximumOutputTokens,
            int maximumDiscussionTokens, int maximumConcurrentRuns,
            BigDecimal maximumExpectedCostUsd) {}

    public record EvaluationView(
            String scenarioSetId, int scenarioSetVersion, BigDecimal minimumScore) {}

    /**
     * One release's definition, minus its prompts.
     *
     * <p>{@code behaviorPresent} says a behavior contract exists without saying what it contains,
     * so a client re-authoring this release knows it owes prompts rather than inferring it from an
     * absence that might mean anything.
     */
    public record ConfigurationView(
            UUID releaseId, int releaseNumber, boolean live, int manifestVersion,
            ParsedDataView parsedData, ModelView model, List<CollectionRefView> corpus,
            List<ToolView> tools, OutputView output, LimitView limits, EvaluationView evaluations,
            boolean behaviorPresent) {}

    @GetMapping(value = "/{instance}/releases/{releaseId}/configuration",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ConfigurationView configuration(@RequestParam UUID brain,
                                           @PathVariable String instance,
                                           @PathVariable UUID releaseId) {
        ResolvedInstanceRelease resolved = releases.byId(new InstanceKey(brain, instance), releaseId);
        if (!(resolved.manifest() instanceof DecodedInstanceManifest.V2 v2)) {
            // A manifest this build cannot decode has no configuration to describe. Refusing is
            // better than describing the parts that happened to survive decoding.
            throw new InstanceReleaseResolver.ReleaseResolutionException(
                    InstanceReleaseResolver.ReleaseResolutionException.Code.RELEASE_NOT_FOUND);
        }
        InstanceReleaseManifest manifest = v2.manifest();

        return new ConfigurationView(
                resolved.release().getId(),
                resolved.release().getReleaseNumber(),
                resolved.live(),
                manifest.manifestVersion(),
                new ParsedDataView(
                        manifest.parsedData().envelopeVersion(),
                        manifest.parsedData().canonicalizationVersion(),
                        // Sorted so two reads of one release agree; a Set has no order of its own.
                        manifest.parsedData().allowedDocumentTypes().stream().sorted().toList(),
                        manifest.parsedData().requireAnyDocumentTypes().stream().sorted().toList(),
                        manifest.parsedData().minimumSupportedDocuments(),
                        manifest.parsedData().reviewRequired().name(),
                        manifest.parsedData().missingFields().name()),
                new ModelView(manifest.model().provider(), manifest.model().model(),
                        manifest.model().fallbackPolicy().name()),
                manifest.corpus().collections().stream()
                        .map(ref -> new CollectionRefView(ref.collectionId(), ref.collectionVersion()))
                        .toList(),
                manifest.tools().stream()
                        .map(tool -> new ToolView(tool.name(), tool.version(),
                                tool.inputSchemaSha256(), tool.outputSchemaSha256()))
                        .toList(),
                new OutputView(manifest.output().schemaId(), manifest.output().schemaSha256()),
                new LimitView(
                        manifest.limits().maximumInputTokens(),
                        manifest.limits().maximumRetrievedTokens(),
                        manifest.limits().maximumOutputTokens(),
                        manifest.limits().maximumDiscussionTokens(),
                        manifest.limits().maximumConcurrentRuns(),
                        manifest.limits().maximumExpectedCostUsd()),
                new EvaluationView(
                        manifest.evaluations().scenarioSetId(),
                        manifest.evaluations().scenarioSetVersion(),
                        manifest.evaluations().minimumScore()),
                manifest.behavior() != null);
    }
}

/** Payload-free failures, on the same terms as every other instance route. */
@RestControllerAdvice(assignableTypes = InstancePointerController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
final class InstancePointerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InstancePointerExceptionHandler.class);
    record ErrorResponse(String code) {}

    @ExceptionHandler(InstanceReleaseResolver.ReleaseResolutionException.class)
    ResponseEntity<ErrorResponse> handleRelease(
            InstanceReleaseResolver.ReleaseResolutionException failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(failure.code().name()));
    }

    @ExceptionHandler({
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorResponse> handleUnreadable(Exception failure) {
        return ResponseEntity.badRequest().body(new ErrorResponse("POINTER_REQUEST_INVALID"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception failure) {
        // Class name and correlation id only. Not the message — that is the payload.
        log.error("Unexpected pointer request failure ({}) [{}]",
                failure.getClass().getSimpleName(), LabAuditService.correlationId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("POINTER_REQUEST_FAILED"));
    }
}
