package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.analyze.InstanceOutputSchemaRegistry;
import com.pragmaticds.rag.lab.analyze.InstanceToolRegistry;
import com.pragmaticds.rag.lab.corpus.CorpusCollectionService;
import com.pragmaticds.rag.lab.corpus.CorpusCommands.CollectionView;
import com.pragmaticds.rag.lab.engine.EngineEnvelopeParser;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry;
import com.pragmaticds.rag.lab.model.InstanceModelCatalogService;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.service.cost.SpendGuardService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checks a complete instance definition against everything this deployment can actually honour.
 *
 * <p><b>Stateless, and complete-command only.</b> There is no server-side wizard draft to keep in
 * sync, expire, or leak between administrators: the client holds the form and sends the whole
 * thing, and this answers about that whole thing. Validating one section is a filter on the
 * answer, not a different code path, so a section that passes on its own passes identically inside
 * a complete check.
 *
 * <p><b>Every violation is a code.</b> A wizard needs to say which field is wrong, and it can, from
 * the section plus the code. What it must not do is echo a configured value back through an error
 * message, because these forms carry prompts and a prompt is content.
 *
 * <p>The same checks run again at promotion. That is not redundancy: a release is immutable but the
 * world around it is not, and a collection disabled or a credential removed between authoring and
 * promotion has to stop the promotion, not the authoring.
 */
public interface InstanceConstraintValidator {

    /** Validates the whole command, or one wizard section of it. */
    ConstraintResult validate(WizardValidationScope scope, CreateInstanceCommand command);

    /** The six authoring sections, plus the whole form. */
    enum WizardValidationScope {
        PARSED_DATA, CORPUS, MODEL, BEHAVIOR, OUTPUT, LIMITS, COMPLETE
    }

    /** One refusal: which section, and a stable code. Never a configured value. */
    record Violation(WizardValidationScope section, String code) {}

    /** The verdict. Violations are reported in section order so the answer is deterministic. */
    record ConstraintResult(List<Violation> violations) {

        public ConstraintResult {
            violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
        }

        public boolean valid() {
            return violations.isEmpty();
        }

        /** Codes alone, for a caller that already knows the section. */
        public List<String> codes() {
            return violations.stream().map(Violation::code).toList();
        }
    }

    /** A complete instance definition. There is no partial form on the server. */
    record CreateInstanceCommand(
            UUID brainId,
            String slug,
            String displayName,
            String purpose,
            InstanceReleaseManifest manifest) {}
}

@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class DefaultInstanceConstraintValidator implements InstanceConstraintValidator {

    /** A slug is an identifier in URLs and in the pointer table, so it is bounded there too. */
    private static final String SLUG_PATTERN = "^[a-z][a-z0-9-]{0,31}$";

    private final CorpusCollectionService collections;
    private final InstanceModelCatalogService catalog;
    private final InstanceToolRegistry tools;
    private final InstanceOutputSchemaRegistry schemas;
    private final InstanceScenarioSetRegistry scenarios;
    private final SpendGuardService budgets;

    DefaultInstanceConstraintValidator(CorpusCollectionService collections,
                                       InstanceModelCatalogService catalog,
                                       InstanceToolRegistry tools,
                                       InstanceOutputSchemaRegistry schemas,
                                       InstanceScenarioSetRegistry scenarios,
                                       SpendGuardService budgets) {
        this.collections = Objects.requireNonNull(collections, "collections");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.budgets = Objects.requireNonNull(budgets, "budgets");
    }

    @Override
    public ConstraintResult validate(WizardValidationScope scope, CreateInstanceCommand command) {
        Objects.requireNonNull(scope, "scope");
        List<Violation> violations = new ArrayList<>();

        // A command that is not even shaped like one cannot be checked section by section, and
        // reporting six downstream failures for one missing manifest would bury the real answer.
        if (command == null || command.brainId() == null || command.manifest() == null) {
            return new ConstraintResult(List.of(
                    new Violation(WizardValidationScope.COMPLETE, "INSTANCE_COMMAND_INCOMPLETE")));
        }
        if (blank(command.slug()) || !command.slug().matches(SLUG_PATTERN)
                || blank(command.displayName())) {
            violations.add(new Violation(
                    WizardValidationScope.COMPLETE, "INSTANCE_IDENTITY_INVALID"));
        }

        InstanceReleaseManifest manifest = command.manifest();
        if (covers(scope, WizardValidationScope.PARSED_DATA)) {
            parsedData(manifest.parsedData(), violations);
        }
        if (covers(scope, WizardValidationScope.CORPUS)) {
            corpus(command.brainId(), manifest.corpus(), violations);
        }
        if (covers(scope, WizardValidationScope.MODEL)) {
            model(manifest.model(), violations);
        }
        if (covers(scope, WizardValidationScope.BEHAVIOR)) {
            behavior(manifest, violations);
        }
        if (covers(scope, WizardValidationScope.OUTPUT)) {
            output(manifest.output(), violations);
        }
        if (covers(scope, WizardValidationScope.LIMITS)) {
            limits(command.brainId(), manifest, violations);
        }
        return new ConstraintResult(violations);
    }

    // ================================================================ sections

    /**
     * The parser this build actually ships, not the one the form would like.
     *
     * <p>A release naming an envelope version this process cannot read would author cleanly and
     * then reject every parse it was ever given, which is the worst place to find out.
     */
    private static void parsedData(InstanceReleaseManifest.ParsedDataContract contract,
                                   List<Violation> violations) {
        if (contract == null) {
            add(violations, WizardValidationScope.PARSED_DATA, "PARSER_CONTRACT_MISSING");
            return;
        }
        if (!EngineEnvelopeParser.SUPPORTED_ENVELOPE_VERSION.equals(contract.envelopeVersion())) {
            add(violations, WizardValidationScope.PARSED_DATA, "PARSER_ENVELOPE_VERSION_UNSUPPORTED");
        }
        if (!EngineEnvelopeParser.SUPPORTED_CANONICALIZATION_VERSION
                .equals(contract.canonicalizationVersion())) {
            add(violations, WizardValidationScope.PARSED_DATA,
                    "PARSER_CANONICALIZATION_VERSION_UNSUPPORTED");
        }
        if (contract.allowedDocumentTypes().isEmpty()) {
            add(violations, WizardValidationScope.PARSED_DATA, "PARSER_NO_ALLOWED_DOCUMENT_TYPE");
        }
        // Requiring a type the release does not allow is unsatisfiable by construction: the
        // document would be filtered out before the requirement could ever be met.
        if (!contract.allowedDocumentTypes().containsAll(contract.requireAnyDocumentTypes())) {
            add(violations, WizardValidationScope.PARSED_DATA, "PARSER_REQUIRED_TYPE_NOT_ALLOWED");
        }
        if (contract.minimumSupportedDocuments() < 1) {
            add(violations, WizardValidationScope.PARSED_DATA, "PARSER_MINIMUM_DOCUMENTS_INVALID");
        }
    }

    /**
     * Exact collection versions that still exist, are still enabled, and have not moved.
     *
     * <p>Pinning a version rather than a collection is what makes a release reproducible; checking
     * that the pinned version is still the current one is what stops a release being authored
     * against a corpus that has already moved on.
     */
    private void corpus(UUID brainId, InstanceReleaseManifest.CorpusContract contract,
                        List<Violation> violations) {
        if (contract == null || contract.collections().isEmpty()) {
            add(violations, WizardValidationScope.CORPUS, "CORPUS_NO_COLLECTION");
            return;
        }
        Map<UUID, CollectionView> present = collections.list(brainId).stream()
                .collect(Collectors.toMap(CollectionView::id, Function.identity(),
                        (first, second) -> first));
        Set<UUID> seen = new HashSet<>();
        for (InstanceReleaseManifest.CollectionRef ref : contract.collections()) {
            if (ref == null || ref.collectionId() == null) {
                add(violations, WizardValidationScope.CORPUS, "CORPUS_COLLECTION_NOT_FOUND");
                continue;
            }
            if (!seen.add(ref.collectionId())) {
                add(violations, WizardValidationScope.CORPUS, "CORPUS_DUPLICATE_COLLECTION");
                continue;
            }
            CollectionView view = present.get(ref.collectionId());
            if (view == null) {
                add(violations, WizardValidationScope.CORPUS, "CORPUS_COLLECTION_NOT_FOUND");
            } else if (!"ACTIVE".equals(view.state())) {
                add(violations, WizardValidationScope.CORPUS, "CORPUS_COLLECTION_DISABLED");
            } else if (view.version() != ref.collectionVersion()) {
                add(violations, WizardValidationScope.CORPUS, "CORPUS_COLLECTION_VERSION_STALE");
            }
        }
    }

    /** A provider and model this deployment has both configured and credentials for. */
    private void model(InstanceReleaseManifest.ModelContract contract, List<Violation> violations) {
        if (contract == null || blank(contract.provider()) || blank(contract.model())
                || contract.fallbackPolicy() == null) {
            add(violations, WizardValidationScope.MODEL, "MODEL_CONTRACT_INCOMPLETE");
            return;
        }
        try {
            if (catalog.find(contract.provider(), contract.model()).isEmpty()) {
                add(violations, WizardValidationScope.MODEL, "MODEL_NOT_CONFIGURED");
            }
        } catch (InstanceModelCatalogService.ModelCatalogException unavailable) {
            // A deployment with no usable catalog cannot honour any model, which is a violation
            // of this section rather than an error the wizard should surface as a crash.
            add(violations, WizardValidationScope.MODEL, unavailable.code().name());
        }
    }

    /** Prompts that exist, a temperature that means something, and tools this build ships. */
    private void behavior(InstanceReleaseManifest manifest, List<Violation> violations) {
        InstanceReleaseManifest.BehaviorContract behavior = manifest.behavior();
        if (behavior == null || blank(behavior.systemPrompt()) || blank(behavior.taskPrompt())) {
            add(violations, WizardValidationScope.BEHAVIOR, "BEHAVIOR_PROMPT_MISSING");
        } else if (behavior.temperature() == null
                || behavior.temperature().signum() < 0
                || behavior.temperature().compareTo(BigDecimal.ONE) > 0) {
            add(violations, WizardValidationScope.BEHAVIOR, "BEHAVIOR_TEMPERATURE_OUT_OF_RANGE");
        }
        try {
            // Resolves every contract without running one: a release naming a tool this build
            // does not ship should fail while its author is still looking at the form.
            tools.verifyRegistered(manifest.tools());
        } catch (InstanceToolRegistry.ToolException unresolvable) {
            add(violations, WizardValidationScope.BEHAVIOR, unresolvable.code().name());
        }
    }

    /** The output schema is resolved from the server-side allowlist, digest and all. */
    private void output(InstanceReleaseManifest.OutputContract contract,
                        List<Violation> violations) {
        if (contract == null || blank(contract.schemaId()) || blank(contract.schemaSha256())) {
            add(violations, WizardValidationScope.OUTPUT, "OUTPUT_CONTRACT_INCOMPLETE");
            return;
        }
        try {
            schemas.requireText(contract.schemaId(), contract.schemaSha256());
        } catch (InstanceOutputSchemaRegistry.OutputSchemaException unresolvable) {
            add(violations, WizardValidationScope.OUTPUT, unresolvable.code().name());
        }
    }

    /**
     * Ceilings that are positive, a scenario set that exists, and a cost the brain can afford.
     *
     * <p>The budget check is the reason a release carries an expected maximum at all. Comparing it
     * to the brain's daily budget here means an instance that could never run a single job within
     * its own budget is refused at authoring rather than discovered at the first dispatch.
     */
    private void limits(UUID brainId, InstanceReleaseManifest manifest,
                        List<Violation> violations) {
        InstanceReleaseManifest.LimitContract limits = manifest.limits();
        if (limits == null) {
            add(violations, WizardValidationScope.LIMITS, "LIMIT_CONTRACT_MISSING");
        } else {
            if (limits.maximumInputTokens() <= 0 || limits.maximumRetrievedTokens() <= 0
                    || limits.maximumOutputTokens() <= 0 || limits.maximumDiscussionTokens() < 0) {
                add(violations, WizardValidationScope.LIMITS, "LIMIT_TOKENS_INVALID");
            }
            if (limits.maximumConcurrentRuns() <= 0) {
                add(violations, WizardValidationScope.LIMITS, "LIMIT_CONCURRENCY_INVALID");
            }
            if (limits.maximumExpectedCostUsd() == null
                    || limits.maximumExpectedCostUsd().signum() <= 0) {
                add(violations, WizardValidationScope.LIMITS, "LIMIT_MAX_COST_INVALID");
            } else {
                BigDecimal budget = budgets.resolveBudget(brainId);
                // A budget of zero or less is this deployment's "unlimited", so it is not a
                // ceiling to compare against.
                if (budget.signum() > 0 && limits.maximumExpectedCostUsd().compareTo(budget) > 0) {
                    add(violations, WizardValidationScope.LIMITS, "LIMIT_MAX_COST_EXCEEDS_BUDGET");
                }
            }
        }

        InstanceReleaseManifest.EvaluationContract evaluations = manifest.evaluations();
        if (evaluations == null || blank(evaluations.scenarioSetId())
                || evaluations.scenarioSetVersion() < 1 || evaluations.minimumScore() == null
                || evaluations.minimumScore().signum() < 0
                || evaluations.minimumScore().compareTo(BigDecimal.ONE) > 0) {
            add(violations, WizardValidationScope.LIMITS, "EVALUATION_CONTRACT_INVALID");
        } else if (scenarios.find(evaluations.scenarioSetId(), evaluations.scenarioSetVersion())
                .isEmpty()) {
            add(violations, WizardValidationScope.LIMITS, "EVALUATION_SCENARIO_SET_UNKNOWN");
        }
    }

    // ================================================================ internals

    private static boolean covers(WizardValidationScope scope, WizardValidationScope section) {
        return scope == WizardValidationScope.COMPLETE || scope == section;
    }

    private static void add(List<Violation> violations, WizardValidationScope section, String code) {
        violations.add(new Violation(section, code));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
