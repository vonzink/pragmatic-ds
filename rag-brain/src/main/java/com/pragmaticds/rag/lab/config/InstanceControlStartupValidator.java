package com.pragmaticds.rag.lab.config;

import com.pragmaticds.rag.lab.model.InstanceModelProperties;
import com.pragmaticds.rag.lab.run.InstanceExecutionProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Refuses to start a deployment whose instance-control flags contradict each other or promise a
 * capability the configuration cannot deliver.
 *
 * <p><b>Loud exactly when a flag is on, silent otherwise.</b> The properties records in this
 * package deliberately do not validate in their constructors, because a malformed value must
 * never stop a deployment where the feature is off. This bean is the other half of that bargain:
 * once an operator explicitly turns a switch <em>on</em>, a configuration that cannot honour it
 * should fail the deployment at startup — where the rollout runbook is looking — rather than
 * surface later as a wall of missing-bean wiring errors, silently absent routes, or the first
 * live request discovering an unpriceable model.
 *
 * <p>Before this validator, the misconfigurations it now names had three different shapes:
 * {@code execution.enabled} without its parent died in bean wiring (the dispatcher exists but
 * nothing it needs does), {@code connector-enabled} without its parent yielded silently absent
 * routes, and promotion without its parent was indistinguishable from promotion off. All three
 * now refuse with the property keys spelled out.
 *
 * <p><b>Messages name property keys only, never values.</b> A key name cannot leak a credential,
 * a host, or a tenant; a value can. Nothing configured is echoed.
 */
@Component
public class InstanceControlStartupValidator {

    private static final String ENABLED = "ragbrain.instances.enabled";
    private static final String PROMOTION = "ragbrain.instances.promotion-enabled";
    private static final String CONNECTOR = "ragbrain.instances.connector-enabled";
    private static final String EXECUTION = "ragbrain.instances.execution.enabled";
    private static final String MODELS = "ragbrain.instances.models";
    private static final String RETENTION_DAYS = "ragbrain.instances.retention.terminal-run-days";
    private static final String PAYLOAD_KEY = "ragbrain.lab.payload-key";
    /** Matches {@code LabPayloadCipher.KEY_BYTES}; AES-256 takes nothing else. */
    private static final int AES_256_KEY_BYTES = 32;
    private static final String ENGINE_BASE_URL = "ragbrain.lab.engine.base-url";
    private static final String ENGINE_AUTH =
            "one of ragbrain.lab.engine.api-key, ragbrain.lab.engine.bearer-token or "
                    + "ragbrain.lab.engine.dev-auth";

    public InstanceControlStartupValidator(
            InstanceControlProperties control,
            InstanceExecutionProperties execution,
            InstanceModelProperties models,
            @Value("${ragbrain.instances.promotion-enabled:false}") boolean promotionEnabled,
            @Value("${ragbrain.instances.connector-enabled:false}") boolean connectorEnabled,
            @Value("${ragbrain.instances.retention.terminal-run-days:0}") int terminalRunDays,
            @Value("${ragbrain.lab.payload-key:}") String payloadKey,
            @Value("${ragbrain.lab.engine.base-url:}") String engineBaseUrl,
            @Value("${ragbrain.lab.engine.api-key:}") String engineApiKey,
            @Value("${ragbrain.lab.engine.bearer-token:}") String engineBearerToken,
            @Value("${ragbrain.lab.engine.dev-auth:false}") boolean engineDevAuth) {
        List<String> refused = new ArrayList<>();
        // A base URL says where the engine is, not that this deployment may talk to it. Checking
        // only the URL is why a deployment could pass every gate and then fail every dispatched
        // run at parse re-verification: the engine answers 401 to an unauthenticated caller.
        boolean engineAuthenticated =
                engineDevAuth
                        || (engineApiKey != null && !engineApiKey.isBlank())
                        || (engineBearerToken != null && !engineBearerToken.isBlank());

        if (promotionEnabled && !control.enabled()) {
            refused.add(PROMOTION + " requires " + ENABLED);
        }
        if (connectorEnabled && !control.enabled()) {
            refused.add(CONNECTOR + " requires " + ENABLED);
        }
        if (execution.enabled() && !control.enabled()) {
            refused.add(EXECUTION + " requires " + ENABLED);
        }

        if (execution.enabled()) {
            requirePositive(refused, execution.maxConcurrentRuns(),
                    "ragbrain.instances.execution.max-concurrent-runs");
            requirePositive(refused, execution.maxConcurrentRunsPerBrain(),
                    "ragbrain.instances.execution.max-concurrent-runs-per-brain");
            requirePositive(refused, execution.maxConcurrentRunsPerProvider(),
                    "ragbrain.instances.execution.max-concurrent-runs-per-provider");
            requirePositive(refused, execution.maxConcurrentRunsPerInstance(),
                    "ragbrain.instances.execution.max-concurrent-runs-per-instance");
            if (execution.pollInterval().isNegative() || execution.pollInterval().isZero()) {
                refused.add("ragbrain.instances.execution.poll-interval must be positive");
            }
            if (execution.leaseDuration().isNegative() || execution.leaseDuration().isZero()) {
                refused.add("ragbrain.instances.execution.lease-duration must be positive");
            }
            if (models.models().isEmpty()) {
                refused.add(MODELS + " must declare at least one model before "
                        + EXECUTION + " is turned on");
            }
            // Execution writes encrypted result payloads; without a key every run would reach a
            // provider, spend money, and then fail to store what it produced.
            if (payloadKey == null || payloadKey.isBlank()) {
                refused.add(PAYLOAD_KEY + " must be set before " + EXECUTION + " is turned on");
            } else if (!usableAesKey(payloadKey)) {
                refused.add(PAYLOAD_KEY + " must be base64 for exactly " + AES_256_KEY_BYTES
                        + " bytes before " + EXECUTION + " is turned on");
            }
            if (engineBaseUrl == null || engineBaseUrl.isBlank()) {
                refused.add(ENGINE_BASE_URL + " must be set before " + EXECUTION + " is turned on");
            }
            if (!engineAuthenticated) {
                refused.add(ENGINE_AUTH + " must be configured before " + EXECUTION
                        + " is turned on");
            }
            // The no-indefinite-default rule: enabling production execution requires an explicit
            // positive retention policy for terminal run history. Zero means "never decided",
            // which is exactly the state this refuses to launch with.
            if (terminalRunDays < 1) {
                refused.add(RETENTION_DAYS + " must be a positive number of days before "
                        + EXECUTION + " is turned on");
            }
        }

        if (connectorEnabled) {
            // The connector verifies every selected parse against the engine before creating
            // anything, and prices every launch through the catalog. Without either, the surface
            // would exist only to refuse each request after authentication.
            if (engineBaseUrl == null || engineBaseUrl.isBlank()) {
                refused.add(ENGINE_BASE_URL + " must be set before " + CONNECTOR + " is turned on");
            }
            if (!engineAuthenticated) {
                refused.add(ENGINE_AUTH + " must be configured before " + CONNECTOR
                        + " is turned on");
            }
            if (models.models().isEmpty()) {
                refused.add(MODELS + " must declare at least one model before "
                        + CONNECTOR + " is turned on");
            }
        }

        if (!refused.isEmpty()) {
            throw new IllegalStateException(
                    "instance control configuration refused: " + String.join("; ", refused));
        }
    }

    /**
     * Whether the payload key is not merely present but usable. {@code LabPayloadCipher} decodes
     * it once at startup and, on anything it cannot use, holds a null key and reports itself
     * unavailable — it does not throw. A truncated paste or a base64url variant therefore
     * satisfies a presence check, starts a dispatcher, and fails every dispatched run at sealing,
     * after the provider has already been paid. That is the same shape as an engine base URL with
     * no credential, and it is refused here for the same reason.
     *
     * <p>The key itself never reaches a message: the caller names the property, as every other
     * refusal here does.
     */
    private static boolean usableAesKey(String base64Key) {
        byte[] material = null;
        try {
            material = Base64.getDecoder().decode(base64Key.trim());
            return material.length == AES_256_KEY_BYTES;
        } catch (IllegalArgumentException notBase64) {
            return false;
        } finally {
            if (material != null) {
                Arrays.fill(material, (byte) 0);
            }
        }
    }

    private static void requirePositive(List<String> refused, Integer limit, String key) {
        if (limit == null || limit < 1) {
            refused.add(key + " must be positive");
        }
    }
}
