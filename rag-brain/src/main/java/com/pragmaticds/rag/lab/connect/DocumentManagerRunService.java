package com.pragmaticds.rag.lab.connect;

import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceRegistryService;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.parsed.RegistrationLoanFactsService;
import com.pragmaticds.rag.lab.parsed.RegistrationSubjectScopeService;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabRunRepository;
import com.pragmaticds.rag.lab.run.RunGroupCommand;
import com.pragmaticds.rag.lab.run.RunGroupService;
import com.pragmaticds.rag.lab.run.RunGroupService.CreatedRunGroup;
import com.pragmaticds.rag.lab.run.RunGroupService.RunGroupException;
import com.pragmaticds.rag.lab.run.RunOrigin;
import com.pragmaticds.rag.lab.run.domain.LabConnectorRunGroupContext;
import com.pragmaticds.rag.lab.domain.LabRun;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.repository.LabConnectorRunGroupContextRepository;
import com.pragmaticds.rag.lab.run.repository.LabRunGroupRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Composes a live instance run for Document Manager, resolving everything the caller may not say.
 *
 * <p>The command names a package, a revision and a source selection. Everything else — which
 * release, which model, which prompts, which corpus — is read from the instance's current live
 * pointer at submission time, because the connector surface exists to exercise <em>what
 * production answers with</em>, and a caller that could name a release could quietly run a
 * candidate against real tenant traffic.
 *
 * <h2>Replay is decided by what the caller sent</h2>
 *
 * <p>The group's own request hash covers the resolved members, live release id included. A
 * connector retry after a promotion would resolve a different release, hash differently, and be
 * refused as a key reuse — punishing the caller for a deployment change it cannot see. So replay
 * here is decided by a second digest over the connector-visible request, stored on the ownership
 * context row: same key and same visible request replays the original group without touching the
 * Document Engine or the live pointer; same key and a different visible request is refused; a
 * <em>new</em> key resolves whatever is live now. Retries are safe and deployment changes are
 * never hidden.
 *
 * <h2>Order of operations</h2>
 *
 * <p>Replay, then instance state, then live resolution, then source verification and
 * compatibility, then the corpus snapshot, then group creation (which prices and re-prices under
 * the budget lock). Compatibility runs before the snapshot on purpose: an incompatible parse
 * creates nothing, not even a frozen corpus nobody will use. The snapshot pins the exact
 * collection ids and versions the live release's manifest names — a collection that moved since
 * the release pinned it fails the freeze, which is the same refusal promotion would give.
 */
@Service
// Both flags, deliberately: every service this depends on — the registry, the resolvers, the
// run-group machinery — is gated on `enabled`, so `connector-enabled` alone would fail bean
// wiring at boot. The two-name condition keeps that from ever being a wiring stacktrace, and
// InstanceControlStartupValidator turns the misconfiguration into a startup refusal that names
// the property keys.
@ConditionalOnProperty(prefix = "ragbrain.instances", name = {"enabled", "connector-enabled"},
        havingValue = "true")
public class DocumentManagerRunService {

    private final InstanceRegistryService registry;
    private final InstanceReleaseResolver releases;
    private final ParsedDataResolver parsedInputs;
    private final CorpusSnapshotService snapshots;
    private final RunGroupService runGroups;
    private final LabRunGroupRepository groups;
    private final LabRunRepository runs;
    private final LabConnectorRunGroupContextRepository contexts;
    private final RegistrationLoanFactsService loanFacts;
    private final RegistrationSubjectScopeService subjectScopes;

    public DocumentManagerRunService(InstanceRegistryService registry,
                                     InstanceReleaseResolver releases,
                                     ParsedDataResolver parsedInputs,
                                     CorpusSnapshotService snapshots,
                                     RunGroupService runGroups,
                                     LabRunGroupRepository groups,
                                     LabRunRepository runs,
                                     LabConnectorRunGroupContextRepository contexts,
                                     RegistrationLoanFactsService loanFacts,
                                     RegistrationSubjectScopeService subjectScopes) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.parsedInputs = Objects.requireNonNull(parsedInputs, "parsedInputs");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.runGroups = Objects.requireNonNull(runGroups, "runGroups");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.loanFacts = Objects.requireNonNull(loanFacts, "loanFacts");
        this.subjectScopes = Objects.requireNonNull(subjectScopes, "subjectScopes");
    }

    public CreatedRunGroup start(DocumentManagerRunCommand command, String idempotencyKey) {
        if (command == null || command.connectorClientId() == null || command.brainId() == null
                || blank(command.instanceSlug()) || blank(command.tenantId())
                || command.packageId() == null || command.revision() < 1
                || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 200) {
            throw new RunGroupException(RunGroupException.Code.RUN_GROUP_REQUEST_INVALID);
        }

        String externalSha256 = externalRequestSha256(command);

        // A true replay returns before any resolution: no Document Engine read, no live-pointer
        // read. That is what makes a retry as cheap as it should be, and what keeps a retry after
        // a promotion from being refused for a change the caller cannot see.
        Optional<CreatedRunGroup> replayed = replay(command, idempotencyKey, externalSha256);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        InstanceKey key = new InstanceKey(command.brainId(), command.instanceSlug());
        LabInstance instance = registry.require(key);
        if (instance.getState() != LabInstance.State.ACTIVE) {
            throw new InstanceRegistryService.InstanceException(
                    InstanceRegistryService.InstanceException.Code.INSTANCE_DISABLED);
        }

        // The live pointer, resolved server-side, at this moment. Throws LIVE_RELEASE_NOT_FOUND
        // when nothing has been promoted; a legacy manifest this build cannot pin refuses below.
        ResolvedInstanceRelease live = releases.live(key);
        if (!(live.manifest() instanceof DecodedInstanceManifest.V2 v2)) {
            throw new InstanceReleaseResolver.ReleaseResolutionException(
                    InstanceReleaseResolver.ReleaseResolutionException.Code.RELEASE_NOT_FOUND);
        }
        InstanceReleaseManifest manifest = v2.manifest();

        // Exact revision, exact sources, verified against the engine's own bytes — and checked
        // for compatibility before anything durable exists, so an incompatible parse creates
        // nothing, not even a snapshot.
        ParsedDataResolver.VerifiedSelection verified = parsedInputs.verifyExisting(
                new ParsedDataResolver.ExistingParseRequest(
                        command.brainId(), command.instanceSlug(), command.packageId(),
                        command.revision(), command.selectedSourceIds()),
                manifest.parsedData());
        if (!verified.compatibility().compatible()) {
            throw new ParsedDataResolver.ParsedDataException(
                    ParsedDataResolver.ParsedDataException.Code.PARSE_INCOMPATIBLE);
        }
        ParsedDataResolver.VerifiedParsedInput pinned = parsedInputs.pin(verified);

        // Attached to the registration, not to the run group, so every member pinned to this
        // loan's package reads the same figures. A true replay returned above and never reaches
        // here, so facts must travel on the first request; sending different ones for a package
        // already registered is refused rather than silently changing a queued run's threshold.
        loanFacts.store(command.brainId(), pinned.registrationId(), command.loanFacts());

        // The scope travels the same road and for the same reason: it belongs to the loan, so it
        // attaches to the registration every member of a comparison group shares rather than to
        // any one run. Without it every finding this package ever produces publishes with a null
        // subject key, and no waiver an officer takes can carry to the next upload.
        subjectScopes.store(command.brainId(), pinned.registrationId(), command.subjectScope());

        // The release's own collection pins, frozen as-is. A collection that moved since the
        // release pinned it fails here with the version conflict — the same refusal promotion
        // gives, arriving before any money is reserved.
        List<CorpusSnapshotService.CollectionVersionRef> refs = manifest.corpus().collections()
                .stream()
                .map(ref -> new CorpusSnapshotService.CollectionVersionRef(
                        ref.collectionId(), ref.collectionVersion()))
                .toList();
        CorpusSnapshotService.FrozenCorpusSnapshot frozen =
                snapshots.freeze(new CorpusSnapshotService.SnapshotRequest(
                        command.brainId(), refs));

        RunGroupCommand group = new RunGroupCommand(
                command.brainId(),
                LabRunGroup.Mode.INDEPENDENT,
                null,
                List.of(new RunGroupCommand.RunMemberCommand(
                        command.instanceSlug(),
                        live.release().getId(),
                        pinned.registrationId(),
                        frozen.id())));

        // Budget preflight, the advisory lock, and the ownership context row all live inside
        // create(): the context commits with the group or not at all.
        return runGroups.create(group, idempotencyKey, new RunOrigin.Connector(
                command.connectorClientId(), command.tenantId(),
                trimmedOrNull(command.externalRequestId()), externalSha256));
    }

    /**
     * The group this connector and key already created, if the visible request matches.
     *
     * <p>Three refusals hide behind one code on purpose. A key that belongs to an admin
     * submission, a key that belongs to another connector or tenant, and a key reused with a
     * different request all answer {@code IDEMPOTENCY_KEY_REUSED} — anything more specific would
     * confirm to a caller what somebody else's key is attached to.
     */
    private Optional<CreatedRunGroup> replay(DocumentManagerRunCommand command,
                                             String idempotencyKey, String externalSha256) {
        return groups.findByBrainIdAndIdempotencyKey(command.brainId(), idempotencyKey)
                .map(existing -> {
                    LabConnectorRunGroupContext context = contexts
                            .findById(existing.getId()).orElse(null);
                    if (context == null
                            || !context.getConnectorClientId().equals(command.connectorClientId())
                            || !context.getTenantId().equals(command.tenantId())
                            || !context.getExternalRequestSha256().equals(externalSha256)) {
                        throw new RunGroupException(
                                RunGroupException.Code.IDEMPOTENCY_KEY_REUSED);
                    }
                    return new CreatedRunGroup(existing.getId(), false,
                            runs.findByRunGroupIdOrderByMemberIndexAsc(existing.getId()).stream()
                                    .map(LabRun::getId).toList());
                });
    }

    /**
     * The digest of what the caller actually sent.
     *
     * <p>Length-prefixed fields, sources sorted: the same selection expressed in two orders is
     * the same request. The external request id participates — two requests differing only in
     * correlation id are different requests, because the correlation id is the caller's own
     * dedup handle and merging them would replay an answer the caller filed elsewhere.
     */
    static String externalRequestSha256(DocumentManagerRunCommand command) {
        StringBuilder canonical = new StringBuilder("dm-run");
        appendField(canonical, command.brainId().toString());
        appendField(canonical, command.instanceSlug());
        appendField(canonical, command.tenantId());
        appendField(canonical, command.packageId().toString());
        appendField(canonical, Integer.toString(command.revision()));
        List<UUID> sorted = command.selectedSourceIds().stream().sorted().toList();
        appendField(canonical, Integer.toString(sorted.size()));
        for (UUID source : sorted) {
            appendField(canonical, source.toString());
        }
        String external = trimmedOrNull(command.externalRequestId());
        appendField(canonical, external == null ? "" : external);
        return sha256Hex(canonical.toString());
    }

    private static void appendField(StringBuilder canonical, String field) {
        // Length prefixes: without them ["ab","c"] and ["a","bc"] would concatenate identically.
        canonical.append('|').append(field.length()).append(':').append(field);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    private static String trimmedOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
