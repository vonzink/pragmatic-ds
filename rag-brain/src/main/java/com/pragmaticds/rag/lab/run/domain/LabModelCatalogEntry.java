package com.pragmaticds.rag.lab.run.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One priced model inside one catalog version.
 *
 * <p>Prices are USD per million tokens, which is how they are configured and how providers publish
 * them: the configured number is the stored number, with no scaling on the way in. Rounding is the
 * estimator's business.
 *
 * <p>Declared {@code @Immutable} with {@code updatable = false} columns so Hibernate can never
 * compose an {@code UPDATE} against this append-only row; see commit {@code 6517c35} and
 * {@link com.pragmaticds.rag.lab.domain.LabInstanceRelease} for the dirty-check round-trip that
 * made the declaration load-bearing.
 */
@Entity
@Immutable
@Table(name = "lab_model_catalog_entry")
public class LabModelCatalogEntry {

    /**
     * Whether this model's token count can be known or only bounded. A model without a provider
     * tokenizer gets a documented conservative range and is labelled as such, so no false exact
     * figure is ever shown.
     */
    public enum TokenizerStrategy { EXACT_PROVIDER, CONSERVATIVE_RANGE }

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(name = "catalog_version_id", nullable = false, updatable = false)
    private UUID catalogVersionId;

    @Column(nullable = false, updatable = false, length = 40)
    private String provider;

    @Column(nullable = false, updatable = false, length = 160)
    private String model;

    @Column(name = "context_token_ceiling", nullable = false, updatable = false)
    private long contextTokenCeiling;

    @Column(name = "output_token_ceiling", nullable = false, updatable = false)
    private long outputTokenCeiling;

    @Enumerated(EnumType.STRING)
    @Column(name = "tokenizer_strategy", nullable = false, updatable = false, length = 32)
    private TokenizerStrategy tokenizerStrategy;

    @Column(name = "input_usd_per_million", nullable = false, updatable = false, precision = 18, scale = 6)
    private BigDecimal inputUsdPerMillion;

    /** Null when the provider has no separate cached-input rate — not zero, which would be a rate. */
    @Column(name = "cached_input_usd_per_million", updatable = false, precision = 18, scale = 6)
    private BigDecimal cachedInputUsdPerMillion;

    @Column(name = "output_usd_per_million", nullable = false, updatable = false, precision = 18, scale = 6)
    private BigDecimal outputUsdPerMillion;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency = "USD";

    protected LabModelCatalogEntry() {}

    public LabModelCatalogEntry(UUID catalogVersionId, String provider, String model,
                                long contextTokenCeiling, long outputTokenCeiling,
                                TokenizerStrategy tokenizerStrategy,
                                BigDecimal inputUsdPerMillion,
                                BigDecimal cachedInputUsdPerMillion,
                                BigDecimal outputUsdPerMillion) {
        this.catalogVersionId = catalogVersionId;
        this.provider = provider;
        this.model = model;
        this.contextTokenCeiling = contextTokenCeiling;
        this.outputTokenCeiling = outputTokenCeiling;
        this.tokenizerStrategy = tokenizerStrategy;
        this.inputUsdPerMillion = inputUsdPerMillion;
        this.cachedInputUsdPerMillion = cachedInputUsdPerMillion;
        this.outputUsdPerMillion = outputUsdPerMillion;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }

    public UUID getId() { return id; }
    public UUID getCatalogVersionId() { return catalogVersionId; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public long getContextTokenCeiling() { return contextTokenCeiling; }
    public long getOutputTokenCeiling() { return outputTokenCeiling; }
    public TokenizerStrategy getTokenizerStrategy() { return tokenizerStrategy; }
    public BigDecimal getInputUsdPerMillion() { return inputUsdPerMillion; }
    public BigDecimal getCachedInputUsdPerMillion() { return cachedInputUsdPerMillion; }
    public BigDecimal getOutputUsdPerMillion() { return outputUsdPerMillion; }
    public String getCurrency() { return currency; }
}
