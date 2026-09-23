package com.pragmaticds.rag.lab.config;

import com.pragmaticds.rag.lab.model.InstanceModelProperties;
import com.pragmaticds.rag.lab.model.InstanceModelProperties.ModelEntry;
import com.pragmaticds.rag.lab.run.InstanceExecutionProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every dependent flag combination, and what a refusal is allowed to say.
 *
 * <p>The validator is deliberately a plain constructor, so the whole matrix is provable here
 * without booting a context per combination — a context that refuses to start cannot be asserted
 * from inside itself. {@code InstanceControlFeatureFlagIT} proves the other half: the everything-
 * off deployment boots with the instance-control beans and routes absent and the legacy surface
 * intact.
 */
class InstanceControlStartupValidatorTest {

    /**
     * Synthetic, and deliberately a <em>valid</em> AES-256 key: base64 for exactly 32 bytes. The
     * previous fixture decoded to 24, which the validator accepted because it only checked the
     * value was non-blank — the gap these tests now close.
     */
    private static final String SECRET_KEY_MATERIAL =
            "c3ludGhldGljLWxhYi1rZXktbm90LWEtcmVhbC1rZXk=";
    /** Base64, decodes cleanly, wrong width: 21 bytes where AES-256 needs 32. */
    private static final String SHORT_KEY_MATERIAL = "dG9vLXNob3J0LWZvci1hZXMtMjU2";
    private static final String INTERNAL_ENGINE = "http://engine.internal:9090";
    private static final String ENGINE_API_KEY = "synthetic-engine-api-key-never-shown";

    // ============================================================ off is always quiet

    @Test
    void everythingOffConstructsQuietly() {
        assertDoesNotThrow(() -> validator(false, false, false,
                executionOff(), List.of(), 0, "", ""));
    }

    @Test
    void parentAloneConstructsQuietlyWithNothingElseConfigured() {
        // Generalized reads only — rollout stage two. No engine, no key, no models, no
        // retention: reads need none of them, and demanding them here would couple the first
        // safe stage to the last one's prerequisites.
        assertDoesNotThrow(() -> validator(true, false, false,
                executionOff(), List.of(), 0, "", ""));
    }

    // ============================================================ children require the parent

    @Test
    void promotionWithoutTheParentRefusesNamingBothKeys() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(false, true, false, executionOff(), List.of(), 0, "", ""));
        assertTrue(refused.getMessage().contains("ragbrain.instances.promotion-enabled"));
        assertTrue(refused.getMessage().contains("ragbrain.instances.enabled"));
    }

    @Test
    void connectorWithoutTheParentRefusesNamingBothKeys() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(false, false, true, executionOff(),
                        List.of(syntheticModel()), 0, "", INTERNAL_ENGINE));
        assertTrue(refused.getMessage().contains("ragbrain.instances.connector-enabled"));
        assertTrue(refused.getMessage().contains("ragbrain.instances.enabled"));
    }

    @Test
    void executionWithoutTheParentRefusesNamingBothKeys() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(false, false, false, executionOn(),
                        List.of(syntheticModel()), 30, SECRET_KEY_MATERIAL, INTERNAL_ENGINE));
        assertTrue(refused.getMessage().contains("ragbrain.instances.execution.enabled"));
        assertTrue(refused.getMessage().contains("ragbrain.instances.enabled"));
    }

    // ============================================================ execution's prerequisites

    @Test
    void executionOnNamesEveryMissingPrerequisiteAtOnce() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(true, false, false,
                        new InstanceExecutionProperties(true, 0, 0, 0, 0,
                                Duration.ZERO, Duration.ZERO),
                        List.of(), 0, "", ""));
        String message = refused.getMessage();
        assertTrue(message.contains("ragbrain.instances.execution.max-concurrent-runs"));
        assertTrue(message.contains("ragbrain.instances.execution.max-concurrent-runs-per-brain"));
        assertTrue(message.contains(
                "ragbrain.instances.execution.max-concurrent-runs-per-provider"));
        assertTrue(message.contains(
                "ragbrain.instances.execution.max-concurrent-runs-per-instance"));
        assertTrue(message.contains("ragbrain.instances.execution.poll-interval"));
        assertTrue(message.contains("ragbrain.instances.execution.lease-duration"));
        assertTrue(message.contains("ragbrain.instances.models"));
        assertTrue(message.contains("ragbrain.lab.payload-key"));
        assertTrue(message.contains("ragbrain.lab.engine.base-url"));
        assertTrue(message.contains("ragbrain.instances.retention.terminal-run-days"));
    }

    @Test
    void executionOnWithEveryPrerequisiteConstructsQuietly() {
        assertDoesNotThrow(() -> validator(true, false, false, executionOn(),
                List.of(syntheticModel()), 30, SECRET_KEY_MATERIAL, INTERNAL_ENGINE));
    }

    @Test
    void executionRefusesAPayloadKeyThatIsSetButUnusable() {
        // The failure this catches: LabPayloadCipher does not throw on a key it cannot use — it
        // holds null and reports itself unavailable. A key that is present but the wrong width
        // therefore satisfies a presence check, starts a dispatcher, and fails every dispatched
        // run at sealing, after the provider has already been paid.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(true, false, false, executionOn(), List.of(syntheticModel()),
                        30, SHORT_KEY_MATERIAL, INTERNAL_ENGINE));
        assertTrue(refused.getMessage().contains("ragbrain.lab.payload-key"));
        assertFalse(refused.getMessage().contains(SHORT_KEY_MATERIAL),
                "the refusal must name the property, never echo the key");
    }

    @Test
    void executionRefusesAPayloadKeyThatIsNotBase64() {
        // A base64url variant (- and _ for + and /) decodes to nothing under the standard decoder
        // the cipher uses, which is a realistic way to paste a key that looks right.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(true, false, false, executionOn(), List.of(syntheticModel()),
                        30, "not base64 at all !!!", INTERNAL_ENGINE));
        assertTrue(refused.getMessage().contains("ragbrain.lab.payload-key"));
    }

    @Test
    void executionRefusesAnUndecidedRetentionPolicy() {
        // Zero is "never decided", not "keep forever chosen on purpose". Enabling production
        // execution with no explicit positive policy is the exact state this refuses.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(true, false, false, executionOn(),
                        List.of(syntheticModel()), 0, SECRET_KEY_MATERIAL, INTERNAL_ENGINE));
        assertTrue(refused.getMessage()
                .contains("ragbrain.instances.retention.terminal-run-days"));
    }

    // ============================================================ connector's prerequisites

    @Test
    void connectorOnRequiresTheEngineAndTheCatalog() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(true, false, true, executionOff(), List.of(), 0, "", ""));
        assertTrue(refused.getMessage().contains("ragbrain.lab.engine.base-url"));
        assertTrue(refused.getMessage().contains("ragbrain.instances.models"));
    }

    @Test
    void connectorOnWithEngineAndCatalogConstructsQuietly() {
        // No payload key and no retention policy: both belong to execution, and a connector-only
        // API node that dispatches nothing legitimately runs without either.
        assertDoesNotThrow(() -> validator(true, false, true, executionOff(),
                List.of(syntheticModel()), 0, "", INTERNAL_ENGINE));
    }

    // ============================================================ what a refusal may say

    @Test
    void aRefusalNeverEchoesAConfiguredValue() {
        // The key material and the internal host are configured and valid; the refusal is about
        // something else entirely. Property keys may appear — values never.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(false, true, false, executionOff(),
                        List.of(syntheticModel()), 30, SECRET_KEY_MATERIAL, INTERNAL_ENGINE));
        assertFalse(refused.getMessage().contains(SECRET_KEY_MATERIAL));
        assertFalse(refused.getMessage().contains(INTERNAL_ENGINE));
        assertFalse(refused.getMessage().contains("engine.internal"));
        assertFalse(refused.getMessage().contains("synthetic-analyzer"));
    }

    // ============================================================ engine authentication

    @Test
    void executionRefusesWithoutAnEngineAuthMode() {
        // A base URL says where the engine is, not that this deployment may talk to it. Without
        // this, a deployment passes every gate and then fails every dispatched run at parse
        // re-verification, because the engine answers 401 to an unauthenticated caller.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(true, false, false, executionOn(), List.of(syntheticModel()),
                        30, SECRET_KEY_MATERIAL, INTERNAL_ENGINE, "", "", false));
        assertTrue(refused.getMessage().contains("ragbrain.lab.engine.api-key"));
    }

    @Test
    void theConnectorRefusesWithoutAnEngineAuthMode() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(true, false, true, executionOff(), List.of(syntheticModel()),
                        0, "", INTERNAL_ENGINE, "", "", false));
        assertTrue(refused.getMessage().contains("ragbrain.lab.engine.api-key"));
    }

    @Test
    void anyOneEngineAuthModeSatisfiesThePrerequisite() {
        assertDoesNotThrow(() -> validator(true, false, false, executionOn(),
                List.of(syntheticModel()), 30, SECRET_KEY_MATERIAL, INTERNAL_ENGINE,
                ENGINE_API_KEY, "", false));
        assertDoesNotThrow(() -> validator(true, false, false, executionOn(),
                List.of(syntheticModel()), 30, SECRET_KEY_MATERIAL, INTERNAL_ENGINE,
                "", "synthetic-bearer", false));
        assertDoesNotThrow(() -> validator(true, false, false, executionOn(),
                List.of(syntheticModel()), 30, SECRET_KEY_MATERIAL, INTERNAL_ENGINE,
                "", "", true));
        // Blank is not configured — the same trap as a blank payload key.
        assertThrows(IllegalStateException.class, () -> validator(true, false, false, executionOn(),
                List.of(syntheticModel()), 30, SECRET_KEY_MATERIAL, INTERNAL_ENGINE,
                "   ", "", false));
    }

    @Test
    void aRefusalNeverEchoesAnEngineApiKey() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> validator(false, true, false, executionOff(), List.of(syntheticModel()),
                        30, SECRET_KEY_MATERIAL, INTERNAL_ENGINE, ENGINE_API_KEY, "", false));
        assertFalse(refused.getMessage().contains(ENGINE_API_KEY));
    }

    // ============================================================ fixtures

    /**
     * The engine-authenticated form. These cases predate engine auth being a prerequisite and are
     * about the other prerequisites, so they configure a mode rather than trip the new refusal.
     */
    private static InstanceControlStartupValidator validator(
            boolean enabled, boolean promotion, boolean connector,
            InstanceExecutionProperties execution, List<ModelEntry> models,
            int terminalRunDays, String payloadKey, String engineBaseUrl) {
        return validator(enabled, promotion, connector, execution, models,
                terminalRunDays, payloadKey, engineBaseUrl, "", "", true);
    }

    private static InstanceControlStartupValidator validator(
            boolean enabled, boolean promotion, boolean connector,
            InstanceExecutionProperties execution, List<ModelEntry> models,
            int terminalRunDays, String payloadKey, String engineBaseUrl,
            String engineApiKey, String engineBearerToken, boolean engineDevAuth) {
        return new InstanceControlStartupValidator(
                new InstanceControlProperties(enabled), execution,
                new InstanceModelProperties(models), promotion, connector,
                terminalRunDays, payloadKey, engineBaseUrl,
                engineApiKey, engineBearerToken, engineDevAuth);
    }

    private static InstanceExecutionProperties executionOff() {
        return new InstanceExecutionProperties(false, null, null, null, null, null, null);
    }

    private static InstanceExecutionProperties executionOn() {
        return new InstanceExecutionProperties(true, null, null, null, null, null, null);
    }

    private static ModelEntry syntheticModel() {
        return new ModelEntry("synthetic", "synthetic-analyzer", 100_000L, 8_000L,
                "CONSERVATIVE_RANGE", new BigDecimal("1.00"), null, new BigDecimal("5.00"));
    }
}
