package com.pragmaticds.rag.lab.web;

import com.pragmaticds.rag.lab.analyze.InstanceOutputSchemaRegistry;
import com.pragmaticds.rag.lab.analyze.InstanceToolRegistry;
import com.pragmaticds.rag.lab.engine.EngineEnvelopeParser;
import com.pragmaticds.rag.lab.eval.InstanceScenarioSetRegistry;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Objects;

/**
 * Everything a client needs to author a release manifest, and nothing more.
 *
 * <p>A manifest must name an envelope version, a canonicalization version, an output schema with
 * its digest, and a scenario set with its version. All four are server-owned: the parser versions
 * are constants this build compares by equality, and the schema and scenario allowlists are fixed
 * maps whose entries resolve to classpath resources. None of them is derivable by a client, and the
 * schema digest in particular is taken over the resource's bytes — so a client that remembered one
 * would keep working until the day that file was edited, and then fail with a digest mismatch that
 * points at nothing.
 *
 * <p>That is the whole reason this route exists. Without it the only way to build a wizard is to
 * hardcode values the server owns, which is the same mistake as hardcoding a model id and fails in
 * a less obvious way.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>Identifiers, versions, digests and counts. No schema text, no scenario contents, no prompts,
 * no credentials, and nothing brain-scoped — this is a description of what the deployment ships,
 * so it takes no brain and reveals nothing about any brain's data. Admin-gated like every route
 * under {@code /api/ai/admin}, and feature-gated with the rest of the instance control plane.
 */
@RestController
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
@RequestMapping("/api/ai/admin/instances")
public final class InstanceWizardOptionsController {

    private final InstanceOutputSchemaRegistry schemas;
    private final InstanceScenarioSetRegistry scenarios;
    private final InstanceToolRegistry tools;

    public InstanceWizardOptionsController(InstanceOutputSchemaRegistry schemas,
                                           InstanceScenarioSetRegistry scenarios,
                                           InstanceToolRegistry tools) {
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    /**
     * The authoring options this build supports.
     *
     * <p>{@code envelopeVersion} and {@code canonicalizationVersion} are the exact strings the
     * constraint validator compares against. They are served rather than documented because a
     * documented constant is one somebody copies, and a copied constant outlives the build it was
     * copied from.
     */
    public record WizardOptionsView(
            String envelopeVersion,
            String canonicalizationVersion,
            List<OutputSchemaOption> outputSchemas,
            List<ScenarioSetOption> scenarioSets,
            List<ToolOption> tools) {}

    /** An output schema a release may pin. Both fields are required together. */
    public record OutputSchemaOption(String schemaId, String sha256) {}

    /** A scenario set a release may be evaluated against. */
    public record ScenarioSetOption(
            String scenarioSetId, int version, String sha256, int scenarioCount) {}

    /** A tool a release may pin. All four fields form the identity a contract must match. */
    public record ToolOption(
            String name, String version, String inputSchemaSha256, String outputSchemaSha256) {}

    @GetMapping(value = "/wizard-options", produces = MediaType.APPLICATION_JSON_VALUE)
    public WizardOptionsView options() {
        return new WizardOptionsView(
                EngineEnvelopeParser.SUPPORTED_ENVELOPE_VERSION,
                EngineEnvelopeParser.SUPPORTED_CANONICALIZATION_VERSION,
                schemas.list().stream()
                        .map(schema -> new OutputSchemaOption(schema.schemaId(), schema.sha256()))
                        .toList(),
                scenarios.list().stream()
                        .map(set -> new ScenarioSetOption(
                                set.id(), set.version(), set.sha256(), set.scenarioCount()))
                        .toList(),
                tools.registered().stream()
                        .map(tool -> new ToolOption(tool.name(), tool.version(),
                                tool.inputSchemaSha256(), tool.outputSchemaSha256()))
                        .toList());
    }
}

/** Payload-free failures, on the same terms as every other instance route. */
@RestControllerAdvice(assignableTypes = InstanceWizardOptionsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
final class InstanceWizardOptionsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InstanceWizardOptionsExceptionHandler.class);
    record ErrorResponse(String code) {}

    /**
     * An allowlisted resource that will not load is a broken build.
     *
     * <p>Answering 500 with the registry's own code says which allowlist failed without naming a
     * classpath location. The alternative — omitting the entry — would present a broken deployment
     * as a shorter list of options, which is the failure nobody notices.
     */
    @ExceptionHandler(InstanceOutputSchemaRegistry.OutputSchemaException.class)
    ResponseEntity<ErrorResponse> handleSchema(
            InstanceOutputSchemaRegistry.OutputSchemaException failure) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(failure.code().name()));
    }

    @ExceptionHandler(InstanceScenarioSetRegistry.ScenarioSetException.class)
    ResponseEntity<ErrorResponse> handleScenarioSet(
            InstanceScenarioSetRegistry.ScenarioSetException failure) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(failure.code().name()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception failure) {
        // Class name and correlation id only. Not the message — that is the payload.
        log.error("Unexpected wizard options request failure ({}) [{}]",
                failure.getClass().getSimpleName(), LabAuditService.correlationId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("WIZARD_OPTIONS_REQUEST_FAILED"));
    }
}
