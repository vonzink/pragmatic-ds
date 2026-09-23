package com.pragmaticds.rag.lab.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * The declared model catalog, bound from {@code ragbrain.instances.models[]}.
 *
 * <p><b>This record does not validate.</b> That is deliberate and matches the Lab payload key: a
 * constructor that threw on a malformed price list would stop RAG Brain starting at all, including
 * on deployments where instances are switched off and nothing here is ever read. Validation belongs
 * to {@link InstanceModelCatalogService}, which only exists while instances are enabled, so a bad
 * price list fails the requests that need pricing rather than the whole application.
 *
 * <p>This is a second binding on the {@code ragbrain.instances} prefix, alongside
 * {@code InstanceControlProperties}, which owns the deployment switch. Spring binds each
 * independently and ignores keys it does not declare, and keeping the model list in the model
 * package rather than in config keeps the two concerns where they are used.
 *
 * <p>No model is hardcoded anywhere in Java or in the dashboard. Availability comes from this list
 * intersected with the providers that actually have credentials, so adding a model is configuration
 * and removing a credential removes the model.
 */
@ConfigurationProperties(prefix = "ragbrain.instances")
public record InstanceModelProperties(List<ModelEntry> models) {

    public InstanceModelProperties {
        models = models == null ? List.of() : List.copyOf(models);
    }

    /**
     * One declared model. Prices are USD per million tokens, exactly as providers publish them.
     *
     * @param cachedInputUsdPerMillion null when the provider has no separate cached-input rate.
     *     Null rather than zero: zero is a rate, and a rate of zero would price cached input as
     *     free rather than as unknown.
     * @param tokenizer {@code EXACT_PROVIDER} or {@code CONSERVATIVE_RANGE}. Declaring the former
     *     does not by itself produce exact counts — see {@link InstanceTokenEstimator}.
     */
    public record ModelEntry(
            String provider,
            String model,
            long contextTokenCeiling,
            long outputTokenCeiling,
            String tokenizer,
            BigDecimal inputUsdPerMillion,
            BigDecimal cachedInputUsdPerMillion,
            BigDecimal outputUsdPerMillion) {}
}
