package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainDailyUsage;
import com.pragmaticds.rag.pack.DomainPack;
import com.pragmaticds.rag.pack.DomainPackLoader;
import com.pragmaticds.rag.pack.DomainPackRegistry;
import com.pragmaticds.rag.pack.PackTemplateService;
import com.pragmaticds.rag.repository.BrainDailyUsageRepository;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.ai.ModelRouterService;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import com.pragmaticds.rag.service.retrieval.RetrievalService;
import com.pragmaticds.rag.service.sync.SyncReport;
import com.pragmaticds.rag.service.sync.SyncService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Admin CRUD for brains: list / create / configure / activate / sync (and a
 * soft-delete). Lives under /api/ai/admin so AdminApiKeyFilter gates it
 * (X-Admin-Api-Key). Never exposes local_api_key_ref / local_base_url — the
 * DTO has no field for them. Validation failures the admin can fix are thrown
 * as IllegalArgumentException so GlobalExceptionHandler returns a clean 400.
 */
@RestController
@RequestMapping("/api/ai/admin/brains")
public class BrainAdminController {

    /** Non-secret admin view of a brain. NO local_api_key_ref / local_base_url. */
    public record BrainDto(
            UUID id, String slug, String displayName, String packRef,
            String sourceType, String s3Bucket, String s3Prefix, String s3Region,
            String localPath, String answerProvider, String answerModel,
            String utilityProvider, String utilityModel,
            boolean isDefault, boolean isActive, boolean learningEnabled,
            BigDecimal dailyCostBudgetUsd) {

        static BrainDto from(Brain b) {
            return new BrainDto(
                    b.getId(), b.getSlug(), b.getDisplayName(), b.getPackRef(),
                    b.getSourceType(), b.getS3Bucket(), b.getS3Prefix(), b.getS3Region(),
                    b.getLocalPath(), b.getAnswerProvider(), b.getAnswerModel(),
                    b.getUtilityProvider(), b.getUtilityModel(),
                    b.isDefault(), b.isActive(), b.isLearningEnabled(),
                    b.getDailyCostBudgetUsd());
        }
    }

    /**
     * Today's accumulated usage for one brain (design: spend-cap circuit
     * breaker admin surface), plus the resolved budget and whether the brain
     * has tripped the breaker right now. Zeroed request/token/cost fields when
     * no usage row exists yet for today (not an error — just no spend so far).
     */
    public record BrainUsageDto(
            UUID brainId, LocalDate usageDate, int requestCount,
            long promptTokens, long completionTokens, BigDecimal costEstimateUsd,
            BigDecimal dailyBudgetUsd, boolean overBudget) {
    }

    private final BrainRepository brains;
    private final SyncService syncService;
    private final DomainPackRegistry packRegistry;
    private final ModelRouterService router;
    private final PackTemplateService packTemplate;
    private final RetrievalService retrievalService;
    private final BrainDailyUsageRepository usageRepository;
    private final SpendGuardService spendGuardService;

    public BrainAdminController(BrainRepository brains, SyncService syncService,
                               DomainPackRegistry packRegistry, ModelRouterService router,
                               PackTemplateService packTemplate, RetrievalService retrievalService,
                               BrainDailyUsageRepository usageRepository, SpendGuardService spendGuardService) {
        this.brains = brains;
        this.syncService = syncService;
        this.packRegistry = packRegistry;
        this.router = router;
        this.packTemplate = packTemplate;
        this.retrievalService = retrievalService;
        this.usageRepository = usageRepository;
        this.spendGuardService = spendGuardService;
    }

    @GetMapping
    public List<BrainDto> list() {
        return brains.findAll().stream().map(BrainDto::from).toList();
    }

    @GetMapping("/{id}")
    public BrainDto get(@PathVariable UUID id) {
        Brain brain = brains.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + id));
        return BrainDto.from(brain);
    }

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]+$");

    /** Create payload — configurable fields only. NO secret fields (no local key / base url). */
    public record CreateBrainRequest(
            String slug, String displayName, String packRef, String sourceType,
            String s3Bucket, String s3Prefix, String s3Region, String localPath,
            String answerProvider, String answerModel,
            String utilityProvider, String utilityModel,
            String disclaimer) {}

    @PostMapping
    public BrainDto create(@RequestBody CreateBrainRequest req) {
        String slug = req.slug() == null ? "" : req.slug().trim();
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug must match ^[a-z0-9-]+$ (got '" + req.slug() + "')");
        }
        if (brains.findBySlug(slug).isPresent()) {
            throw new IllegalArgumentException("A brain with slug '" + slug + "' already exists");
        }
        requireText("displayName", req.displayName());
        requireSourceBinding(req.sourceType(), req.localPath(), req.s3Bucket());

        String packRef;
        if (isBlank(req.packRef())) {
            // Frictionless path: auto-generate a slug-matched neutral starter pack.
            // generate() validates the result via DomainPackLoader, so no second validatePack.
            packRef = packTemplate.generate(slug, req.displayName().trim(), effectiveDisclaimer(req.disclaimer()));
        } else {
            packRef = req.packRef().trim();
            validatePack(packRef, slug);   // unchanged explicit-pack path (load + slug-match)
        }

        Brain brain = new Brain(UUID.randomUUID(), slug, req.displayName().trim());
        apply(brain, packRef, req.sourceType(), req.s3Bucket(), req.s3Prefix(), req.s3Region(),
                req.localPath(), req.answerProvider(), req.answerModel(),
                req.utilityProvider(), req.utilityModel());
        brain.setDefault(false);   // activation is a separate, explicit action
        brain.setActive(true);
        return BrainDto.from(brains.save(brain));
    }

    /** Update payload — same configurable fields as create. NO id, NO is_default, NO secrets. */
    public record UpdateBrainRequest(
            String slug, String displayName, String packRef, String sourceType,
            String s3Bucket, String s3Prefix, String s3Region, String localPath,
            String answerProvider, String answerModel,
            String utilityProvider, String utilityModel,
            /** Per-brain override of the global daily LLM spend cap; null clears the override. */
            BigDecimal dailyCostBudgetUsd) {}

    @PutMapping("/{id}")
    public BrainDto update(@PathVariable UUID id, @RequestBody UpdateBrainRequest req) {
        Brain brain = brains.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + id));

        String slug = req.slug() == null ? "" : req.slug().trim();
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("slug must match ^[a-z0-9-]+$ (got '" + req.slug() + "')");
        }
        brains.findBySlug(slug).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new IllegalArgumentException("A brain with slug '" + slug + "' already exists");
            }
        });
        requireText("displayName", req.displayName());
        requireText("packRef", req.packRef());
        requireSourceBinding(req.sourceType(), req.localPath(), req.s3Bucket());
        validatePack(req.packRef().trim(), slug);

        boolean packChanged = !Objects.equals(brain.getPackRef(), trimToNull(req.packRef()));
        brain.setSlug(slug);
        brain.setDisplayName(req.displayName().trim());
        apply(brain, req.packRef(), req.sourceType(), req.s3Bucket(), req.s3Prefix(), req.s3Region(),
                req.localPath(), req.answerProvider(), req.answerModel(),
                req.utilityProvider(), req.utilityModel());
        brain.setDailyCostBudgetUsd(req.dailyCostBudgetUsd());
        Brain saved = brains.save(brain);
        if (packChanged) {
            packRegistry.reload(id);   // next request reloads + re-validates the new pack
        }
        return BrainDto.from(saved);
    }

    @PostMapping("/{id}/activate")
    @Transactional
    public BrainDto activate(@PathVariable UUID id) {
        Brain target = brains.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + id));
        if (!target.isActive()) {
            throw new IllegalArgumentException("Cannot activate an inactive brain: " + id);
        }
        // Clear the existing default FIRST and flush — the partial unique index
        // ux_brains_single_default rejects two is_default=true rows at statement time.
        brains.findDefaultBrain().ifPresent(current -> {
            if (!current.getId().equals(id)) {
                current.setDefault(false);
                brains.saveAndFlush(current);
            }
        });
        target.setDefault(true);
        return BrainDto.from(brains.save(target));
    }

    /**
     * Per-brain master switch for the adaptive learning loop (spec §6). Mirrors
     * /activate: look up, mutate, save. `enabled` is required so omitting it can
     * never silently clear the flag. OFF => retrieval ignores learned weights and
     * the aggregation job skips the brain; capture continues regardless.
     */
    @PostMapping("/{id}/learning")
    public BrainDto learning(@PathVariable UUID id,
                             @RequestParam("enabled") boolean enabled) {
        Brain brain = brains.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + id));
        brain.setLearningEnabled(enabled);
        BrainDto dto = BrainDto.from(brains.save(brain));
        // RetrievalService caches learningEnabled per brainId to avoid a DB read on
        // every retrieve() call; invalidate it now so the very next retrieval sees
        // this toggle instead of a stale cached value.
        retrievalService.invalidateLearningEnabledCache(id);
        return dto;
    }

    /**
     * Today's accumulated spend/usage for a brain (design: spend-cap circuit
     * breaker admin surface) plus its resolved budget and whether it is
     * currently over. Read-only observability endpoint; enforcement itself
     * lives in {@link SpendGuardService}.
     */
    @GetMapping("/{id}/usage")
    public BrainUsageDto usage(@PathVariable UUID id) {
        if (brains.findById(id).isEmpty()) {
            throw new IllegalArgumentException("Unknown brain: " + id);
        }
        BrainDailyUsage today = usageRepository.findByBrainIdAndUsageDate(id, LocalDate.now()).orElse(null);
        return new BrainUsageDto(
                id,
                LocalDate.now(),
                today == null ? 0 : today.getRequestCount(),
                today == null ? 0L : today.getPromptTokens(),
                today == null ? 0L : today.getCompletionTokens(),
                today == null ? BigDecimal.ZERO : today.getCostEstimateUsd(),
                spendGuardService.resolveBudget(id),
                spendGuardService.isOverBudget(id));
    }

    /**
     * Sync this brain's corpus. scope narrows to one analyzer scope (slug, or
     * "shared" for unscoped/root files); force=true overrides the
     * mass-deactivation guard, which otherwise refuses suspicious plans —
     * including the whole-corpus (no-scope) sync, deliberately.
     */
    @PostMapping("/{id}/sync")
    public SyncReport sync(@PathVariable UUID id,
                           @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
                           @RequestParam(value = "scope", required = false) String scope,
                           @RequestParam(value = "force", defaultValue = "false") boolean force) {
        SyncService.validateScope(scope);
        if (brains.findById(id).isEmpty()) {
            throw new IllegalArgumentException("Unknown brain: " + id);
        }
        return syncService.sync(dryRun, id, scope, force);
    }

    @DeleteMapping("/{id}")
    public BrainDto softDelete(@PathVariable UUID id) {
        Brain brain = brains.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + id));
        if (brain.isDefault()) {
            throw new IllegalArgumentException("Cannot delete the default brain; activate another brain first");
        }
        brain.setActive(false);          // soft delete — never hard-delete
        return BrainDto.from(brains.save(brain));
    }

    /**
     * Undo a soft delete: re-enable a disabled brain so it resolves again.
     * Does NOT touch is_default — making it the default stays a separate,
     * explicit /activate call.
     */
    @PostMapping("/{id}/restore")
    public BrainDto restore(@PathVariable UUID id) {
        Brain brain = brains.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + id));
        brain.setActive(true);
        return BrainDto.from(brains.save(brain));
    }

    /** Loads the pack at packRef and asserts its slug equals the brain slug — clean 400 on any problem. */
    void validatePack(String packRef, String slug) {
        DomainPack pack;
        try {
            pack = new DomainPackLoader().load(Path.of(packRef).toAbsolutePath().normalize());
        } catch (DomainPackLoader.PackValidationException e) {
            throw new IllegalArgumentException("Invalid pack at '" + packRef + "': " + e.getMessage());
        }
        if (!pack.slug().equals(slug)) {
            throw new IllegalArgumentException("Pack at '" + packRef + "' has slug '" + pack.slug()
                    + "' but the brain slug is '" + slug + "' — they must match");
        }
    }

    private void apply(Brain brain, String packRef, String sourceType, String s3Bucket,
                       String s3Prefix, String s3Region, String localPath,
                       String answerProvider, String answerModel,
                       String utilityProvider, String utilityModel) {
        brain.setPackRef(trimToNull(packRef));
        brain.setSourceType(sourceType == null ? null : sourceType.trim().toLowerCase(Locale.US));
        if ("s3".equalsIgnoreCase(sourceType)) {
            brain.setS3Bucket(trimToNull(s3Bucket));
            brain.setS3Prefix(trimToNull(s3Prefix));
            brain.setS3Region(trimToNull(s3Region));
            brain.setLocalPath(null);
        } else { // local
            brain.setLocalPath(trimToNull(localPath));
            brain.setS3Bucket(null);
            brain.setS3Prefix(null);
            brain.setS3Region(null);
        }
        validateProvider("answerProvider", answerProvider);
        validateProvider("utilityProvider", utilityProvider);
        brain.setAnswerProvider(trimToNull(answerProvider));
        brain.setAnswerModel(trimToNull(answerModel));
        brain.setUtilityProvider(trimToNull(utilityProvider));
        brain.setUtilityModel(trimToNull(utilityModel));
    }

    /**
     * Reject a brain configured to use a provider that isn't registered — otherwise
     * the misconfiguration only surfaces as a runtime failure on the first question.
     * A null/blank provider means "inherit the global default", which is allowed.
     * When no providers are registered at all (keyless admin-only boot) we skip the
     * check so operators can still configure brains before keys are wired in.
     */
    private void validateProvider(String field, String provider) {
        String p = trimToNull(provider);
        if (p == null) {
            return;
        }
        java.util.Set<String> known = router.providerNames();
        if (known == null || known.isEmpty()) {
            return;
        }
        if (!known.contains(p)) {
            throw new IllegalArgumentException(
                    field + " '" + p + "' is not a configured provider. Configured: " + known);
        }
    }

    private void requireSourceBinding(String sourceType, String localPath, String s3Bucket) {
        String t = sourceType == null ? "" : sourceType.trim().toLowerCase(Locale.US);
        if (t.equals("local")) {
            if (isBlank(localPath)) throw new IllegalArgumentException("localPath is required for a local source");
        } else if (t.equals("s3")) {
            if (isBlank(s3Bucket)) throw new IllegalArgumentException("s3Bucket is required for an s3 source");
        } else {
            throw new IllegalArgumentException("sourceType must be 'local' or 's3' (got '" + sourceType + "')");
        }
    }

    private static final String DEFAULT_DISCLAIMER =
            "Educational use only — verify against the source documents.";
    private static String effectiveDisclaimer(String d) {
        return isBlank(d) ? DEFAULT_DISCLAIMER : d.trim();
    }

    private static void requireText(String field, String v) {
        if (isBlank(v)) throw new IllegalArgumentException(field + " is required");
    }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String trimToNull(String s) { return isBlank(s) ? null : s.trim(); }
}
