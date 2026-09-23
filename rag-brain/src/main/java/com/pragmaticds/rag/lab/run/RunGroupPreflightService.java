package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.domain.BrainDailyUsage;
import com.pragmaticds.rag.lab.analyze.InstanceOutputSchemaRegistry;
import com.pragmaticds.rag.lab.corpus.CorpusSnapshotService;
import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.instance.InstanceKey;
import com.pragmaticds.rag.lab.instance.InstanceRegistryService;
import com.pragmaticds.rag.lab.instance.InstanceReleaseResolver;
import com.pragmaticds.rag.lab.instance.ResolvedInstanceRelease;
import com.pragmaticds.rag.lab.model.InstanceCostEstimator;
import com.pragmaticds.rag.lab.model.ModelEstimate;
import com.pragmaticds.rag.lab.parsed.ParsedDataResolver;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.repository.LabInstanceReleaseRepository;
import com.pragmaticds.rag.lab.run.RunGroupCommand.MemberPreflight;
import com.pragmaticds.rag.lab.run.RunGroupCommand.RunGroupPreflight;
import com.pragmaticds.rag.lab.run.RunGroupCommand.RunMemberCommand;
import com.pragmaticds.rag.lab.run.RunGroupRequestCodec.MemberBasis;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import com.pragmaticds.rag.lab.run.repository.LabSpendReservationRepository;
import com.pragmaticds.rag.repository.BrainDailyUsageRepository;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves a submission and prices it, without writing a row or calling a provider.
 *
 * <p><b>Nothing here has a side effect.</b> A preflight can be run from a form on every keystroke
 * if a client wants to: it reads releases, re-verifies parsed inputs against the Document Engine,
 * and estimates cost from the pinned catalog. It creates no group, no run, no reservation, and no
 * receipt, so a caller exploring options leaves nothing behind.
 *
 * <p><b>A preflight is advice, not permission.</b> {@link RunGroupService} runs this same
 * resolution again inside its own transaction, because everything checked here can change between
 * the preview and the submission. Treating a preflight as authorization would let a caller hold a
 * stale acceptable result and use it to bypass a constraint that has since started applying.
 *
 * <p><b>Budget arithmetic is the whole point of the maximum.</b> Today's committed spend, plus
 * everything currently reserved by in-flight runs, plus this group's conservative maximum, must
 * fit the brain's daily budget. Summing maxima rather than midpoints is what makes the check
 * meaningful: a group approved on its expected cost could still overshoot on its upper bound.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class RunGroupPreflightService {

    /** A group with no members runs nothing; a huge one is a mistake, not an intention. */
    static final int MAXIMUM_MEMBERS = 16;

    private final InstanceRegistryService registry;
    private final InstanceReleaseResolver releases;
    private final LabInstanceReleaseRepository releaseRows;
    private final ParsedDataResolver parsedInputs;
    private final CorpusSnapshotService snapshots;
    private final InstanceCostEstimator estimator;
    private final InstanceOutputSchemaRegistry schemas;
    private final SpendGuardService budgets;
    private final BrainDailyUsageRepository dailyUsage;
    private final LabSpendReservationRepository reservations;
    private final Clock clock;

    public RunGroupPreflightService(InstanceRegistryService registry,
                                    InstanceReleaseResolver releases,
                                    LabInstanceReleaseRepository releaseRows,
                                    ParsedDataResolver parsedInputs,
                                    CorpusSnapshotService snapshots,
                                    InstanceCostEstimator estimator,
                                    InstanceOutputSchemaRegistry schemas,
                                    SpendGuardService budgets,
                                    BrainDailyUsageRepository dailyUsage,
                                    LabSpendReservationRepository reservations,
                                    Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.releases = Objects.requireNonNull(releases, "releases");
        this.releaseRows = Objects.requireNonNull(releaseRows, "releaseRows");
        this.parsedInputs = Objects.requireNonNull(parsedInputs, "parsedInputs");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
        this.dailyUsage = Objects.requireNonNull(dailyUsage, "dailyUsage");
        this.reservations = Objects.requireNonNull(reservations, "reservations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Resolves, prices, and checks the budget. Writes nothing. */
    public RunGroupPreflight preflight(RunGroupCommand command) {
        List<String> groupBlockers = shapeBlockers(command);
        if (!groupBlockers.isEmpty()) {
            return refused(groupBlockers);
        }

        List<Resolved> resolved = new ArrayList<>(command.members().size());
        for (int index = 0; index < command.members().size(); index++) {
            resolved.add(resolve(command, index));
        }

        List<String> blockers = new ArrayList<>(comparisonBlockers(command, resolved));
        String basis = comparisonBasis(command, resolved);

        BigDecimal reserved = resolved.stream()
                .map(member -> member.preflight().costUsdMax())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal committed = committedToday(command.brainId());
        BigDecimal held = Objects.requireNonNullElse(
                reservations.reservedTotalFor(command.brainId()), BigDecimal.ZERO);
        BigDecimal budget = budgets.resolveBudget(command.brainId());

        // A budget of zero or less is this deployment's "unlimited", so there is no ceiling to
        // exceed; anything else must fit committed + held + this group's maximum.
        boolean withinBudget = budget.signum() <= 0
                || committed.add(held).add(reserved).compareTo(budget) <= 0;
        if (!withinBudget) {
            blockers.add("GROUP_EXCEEDS_DAILY_BUDGET");
        }

        return new RunGroupPreflight(
                RunGroupRequestCodec.requestSha256(command), basis,
                resolved.stream().map(Resolved::preflight).toList(),
                reserved, committed, held, budget, withinBudget, List.copyOf(blockers));
    }

    // ================================================================ resolution

    /** One member, resolved as far as it can be, carrying its own blockers rather than throwing. */
    record Resolved(MemberPreflight preflight, MemberBasis basis) {}

    private Resolved resolve(RunGroupCommand command, int index) {
        RunMemberCommand member = command.members().get(index);
        List<String> blockers = new ArrayList<>();

        if (member == null || member.instanceSlug() == null || member.instanceSlug().isBlank()
                || member.releaseId() == null || member.registrationId() == null) {
            return unresolved(index, member, List.of("MEMBER_REQUEST_INVALID"));
        }
        InstanceKey key = new InstanceKey(command.brainId(), member.instanceSlug());
        try {
            LabInstance instance = registry.require(key);
            if (instance.getState() != LabInstance.State.ACTIVE) {
                blockers.add("INSTANCE_DISABLED");
            }
        } catch (InstanceRegistryService.InstanceException absent) {
            return unresolved(index, member, List.of(absent.code().name()));
        }

        // Scoped by brain and instance, so a release id from elsewhere resolves to nothing rather
        // than to another brain's contract.
        var releaseRow = releaseRows.findByIdAndBrainIdAndInstanceSlug(
                member.releaseId(), command.brainId(), member.instanceSlug());
        if (releaseRow.isEmpty()) {
            return unresolved(index, member, List.of("RELEASE_NOT_FOUND"));
        }
        ResolvedInstanceRelease release;
        try {
            release = releases.byId(key, member.releaseId());
        } catch (InstanceReleaseResolver.ReleaseResolutionException unresolvable) {
            return unresolved(index, member, List.of(unresolvable.code().name()));
        }
        if (!(release.manifest() instanceof DecodedInstanceManifest.V2 v2)) {
            return unresolved(index, member, List.of("INSTANCE_RELEASE_NOT_PINNABLE"));
        }
        InstanceReleaseManifest manifest = v2.manifest();

        if (member.corpusSnapshotId() == null) {
            blockers.add("CORPUS_SNAPSHOT_REQUIRED");
        } else {
            try {
                snapshots.require(command.brainId(), member.corpusSnapshotId());
            } catch (CorpusSnapshotService.SnapshotException unreadable) {
                blockers.add(unreadable.code().name());
            }
        }

        // Re-verified against the engine, not trusted from the row: a run must fail closed if the
        // parse it would be pinned to has moved, and finding that out now is free.
        ParsedDataResolver.VerifiedParsedInput parsed = null;
        try {
            parsed = parsedInputs.review(command.brainId(), member.instanceSlug(),
                    member.registrationId(), manifest.parsedData());
            if (!parsed.compatibility().compatible()) {
                blockers.add(parsed.compatibility().rejection() == null
                        ? "PARSE_INCOMPATIBLE" : parsed.compatibility().rejection().name());
            }
        } catch (ParsedDataResolver.ParsedDataException unverifiable) {
            blockers.add(unverifiable.code().name());
        }

        ModelEstimate estimate = null;
        try {
            estimate = estimator.estimate(manifest.model().provider(), manifest.model().model(),
                    estimateInput(manifest, parsed));
        } catch (RuntimeException unpriceable) {
            blockers.add(safeCode(unpriceable));
        }

        MemberBasis basis = RunGroupRequestCodec.basisOf(member.instanceSlug(),
                parsed == null ? "" : parsed.packageId().toString(),
                parsed == null ? 0 : parsed.revision(),
                parsed == null ? List.of() : parsed.selectedSourceIds().stream()
                        .map(UUID::toString).toList(),
                member.corpusSnapshotId() == null ? "" : member.corpusSnapshotId().toString(),
                manifest);

        return new Resolved(preflight(index, member, manifest, estimate, blockers), basis);
    }

    /**
     * What the run will put in front of the model.
     *
     * <p>The corpus allowance is the release's retrieval ceiling rather than what retrieval will
     * actually return, because an estimate is computed before retrieval runs and the allowance is
     * the only honest bound on it. The parsed fact block is not rendered here — rendering it feeds
     * {@code prompt_sha256} and belongs to execution — so its size is bounded by the release's own
     * input ceiling instead.
     */
    private ModelEstimate.EstimateInput estimateInput(
            InstanceReleaseManifest manifest, ParsedDataResolver.VerifiedParsedInput parsed) {
        String outputSchema;
        try {
            outputSchema = schemas.requireText(
                    manifest.output().schemaId(), manifest.output().schemaSha256());
        } catch (RuntimeException unresolvable) {
            outputSchema = "";
        }
        return new ModelEstimate.EstimateInput(
                manifest.behavior().systemPrompt(),
                manifest.behavior().taskPrompt(),
                // Not rendered here: rendering feeds prompt_sha256 and belongs to execution. The
                // bound travels as a token count instead, through parsedFactTokenAllowance below.
                "",
                manifest.limits().maximumRetrievedTokens(),
                manifest.tools().stream()
                        .map(tool -> tool.name() + tool.version() + tool.inputSchemaSha256())
                        .toList(),
                outputSchema,
                0L,
                // The release's own input ceiling is the honest upper bound on a block nobody has
                // rendered yet, and over-bounding is the safe side of a budget reservation.
                parsed == null ? 0L : manifest.limits().maximumInputTokens(),
                manifest.limits().maximumOutputTokens());
    }

    // ================================================================ comparison

    /**
     * Whether every member really holds the declared basis equal.
     *
     * <p>Compared as digests rather than field by field, so adding a field to the basis cannot
     * accidentally leave a comparison unchecked: whatever the basis covers, it covers for every
     * member at once.
     */
    private static List<String> comparisonBlockers(RunGroupCommand command,
                                                   List<Resolved> resolved) {
        if (command.mode() != LabRunGroup.Mode.COMPARISON) {
            return List.of();
        }
        Set<String> bases = new LinkedHashSet<>();
        for (Resolved member : resolved) {
            bases.add(RunGroupRequestCodec.comparisonBasisSha256(
                    command.comparisonDimension(), List.of(member.basis())));
        }
        if (bases.size() > 1) {
            return List.of("COMPARISON_BASIS_MISMATCH");
        }
        // Varying nothing is not a comparison. Two members identical in the declared dimension
        // would produce two runs whose difference nobody could attribute to anything.
        Set<String> varied = new LinkedHashSet<>();
        for (Resolved member : resolved) {
            varied.add(switch (command.comparisonDimension()) {
                case MODEL -> member.basis().modelSha256();
                case RELEASE -> member.preflight().releaseId().toString();
                case INSTANCE -> member.basis().instanceSlug();
            });
        }
        if (varied.size() != resolved.size()) {
            return List.of("COMPARISON_DIMENSION_NOT_VARIED");
        }
        return List.of();
    }

    private static String comparisonBasis(RunGroupCommand command, List<Resolved> resolved) {
        if (command.mode() != LabRunGroup.Mode.COMPARISON || resolved.isEmpty()) {
            return null;
        }
        return RunGroupRequestCodec.comparisonBasisSha256(command.comparisonDimension(),
                resolved.stream().map(Resolved::basis).toList());
    }

    // ================================================================ internals

    private static List<String> shapeBlockers(RunGroupCommand command) {
        if (command == null || command.brainId() == null || command.mode() == null) {
            return List.of("GROUP_REQUEST_INVALID");
        }
        List<String> blockers = new ArrayList<>();
        if (command.members().isEmpty() || command.members().size() > MAXIMUM_MEMBERS) {
            blockers.add("GROUP_MEMBER_COUNT_INVALID");
        }
        boolean comparison = command.mode() == LabRunGroup.Mode.COMPARISON;
        if (comparison && command.comparisonDimension() == null) {
            blockers.add("COMPARISON_DIMENSION_REQUIRED");
        }
        if (!comparison && command.comparisonDimension() != null) {
            blockers.add("COMPARISON_DIMENSION_NOT_PERMITTED");
        }
        if (comparison && command.members().size() < 2) {
            blockers.add("COMPARISON_NEEDS_TWO_MEMBERS");
        }
        return blockers;
    }

    private BigDecimal committedToday(UUID brainId) {
        return dailyUsage.findByBrainIdAndUsageDate(brainId, LocalDate.now(clock))
                .map(BrainDailyUsage::getCostEstimateUsd)
                .orElse(BigDecimal.ZERO);
    }

    private static MemberPreflight preflight(int index, RunMemberCommand member,
                                             InstanceReleaseManifest manifest,
                                             ModelEstimate estimate, List<String> blockers) {
        return new MemberPreflight(index, member.instanceSlug(), member.releaseId(),
                member.registrationId(), member.corpusSnapshotId(),
                manifest.model().provider(), manifest.model().model(),
                estimate == null ? null : estimate.pricingVersionId(),
                estimate == null ? 0 : estimate.inputTokensMin(),
                estimate == null ? 0 : estimate.inputTokensMax(),
                estimate == null ? 0 : estimate.outputTokensMin(),
                estimate == null ? 0 : estimate.outputTokensMax(),
                estimate == null ? BigDecimal.ZERO : estimate.costUsdMin(),
                estimate == null ? BigDecimal.ZERO : estimate.costUsdMax(),
                estimate == null ? null : estimate.quality().name(),
                List.copyOf(blockers));
    }

    private static Resolved unresolved(int index, RunMemberCommand member, List<String> blockers) {
        // An unresolved member still costs nothing and still reports why, so a five-member group
        // shows all five problems rather than one per submission.
        return new Resolved(new MemberPreflight(index,
                member == null ? null : member.instanceSlug(),
                member == null ? null : member.releaseId(),
                member == null ? null : member.registrationId(),
                member == null ? null : member.corpusSnapshotId(),
                null, null, null, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null, blockers),
                new MemberBasis(member == null ? "" : member.instanceSlug(),
                        "", 0, List.of(), "", "", "", "", ""));
    }

    private static RunGroupPreflight refused(List<String> blockers) {
        return new RunGroupPreflight(null, null, List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, true, blockers);
    }

    /** Each layer publishes its own value-free vocabulary; this only selects between them. */
    private static String safeCode(RuntimeException failure) {
        return switch (failure) {
            case com.pragmaticds.rag.lab.model.InstanceModelCatalogService
                    .ModelCatalogException e -> e.code().name();
            case InstanceOutputSchemaRegistry.OutputSchemaException e -> e.code().name();
            default -> "MEMBER_ESTIMATE_FAILED";
        };
    }
}
