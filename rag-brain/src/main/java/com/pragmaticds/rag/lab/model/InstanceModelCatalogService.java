package com.pragmaticds.rag.lab.model;

import com.pragmaticds.rag.lab.run.domain.LabModelCatalogEntry;
import com.pragmaticds.rag.lab.run.domain.LabModelCatalogVersion;
import com.pragmaticds.rag.lab.run.repository.LabModelCatalogEntryRepository;
import com.pragmaticds.rag.lab.run.repository.LabModelCatalogVersionRepository;
import com.pragmaticds.rag.provider.AiModelProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns the configured model list into an immutable, versioned price list.
 *
 * <p><b>Availability is configuration intersected with credentials.</b> A model appears only if it
 * is declared in {@code ragbrain.instances.models[]} <em>and</em> its provider has a registered
 * bean, which in this codebase a provider only gets when its API key is present. Removing a
 * credential therefore removes the model from every wizard and every run group, without a code
 * change and without a stale entry offering a model nothing can call. No model name is hardcoded
 * in Java or in the dashboard.
 *
 * <p><b>Versions are appended, never rewritten.</b> The configured entries are canonicalized and
 * hashed; a matching hash reuses the existing version, and any difference appends a new one. A run
 * priced last month can still be re-derived from the exact numbers it was priced against, because
 * those numbers were never edited in place. Editing a price in configuration produces a new
 * version rather than retroactively restating what anything cost.
 *
 * <p><b>Resolved on first use, not at startup.</b> A price list is read by the requests that need
 * pricing, so a malformed one fails those requests rather than preventing RAG Brain from starting.
 * That is the same posture the Lab payload key already takes. This bean only exists while
 * instances are enabled, so a deployment with the feature off never validates any of it.
 */
@Service
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
public class InstanceModelCatalogService {

    /** Why a catalog cannot be resolved. Stable, value-free codes; never a configured value. */
    public static final class ModelCatalogException extends RuntimeException {
        public enum Code {
            /** No model is configured, so nothing can be priced or offered. */
            MODEL_CATALOG_EMPTY,
            /** A configured entry is missing a field, malformed, or negatively priced. */
            MODEL_CATALOG_INVALID,
            /** Two entries declare the same provider and model. */
            MODEL_CATALOG_DUPLICATE,
            /** Every configured model belongs to a provider with no credentials. */
            MODEL_CATALOG_UNAVAILABLE,
            /** The requested model is not in the resolved catalog version. */
            MODEL_NOT_IN_CATALOG
        }

        private final Code code;

        public ModelCatalogException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    /** One offerable model. Prices are per million tokens, exactly as configured. */
    public record CatalogModel(
            String provider,
            String model,
            long contextTokenCeiling,
            long outputTokenCeiling,
            LabModelCatalogEntry.TokenizerStrategy tokenizerStrategy,
            BigDecimal inputUsdPerMillion,
            BigDecimal cachedInputUsdPerMillion,
            BigDecimal outputUsdPerMillion) {}

    /** The active version and everything it prices. */
    public record ResolvedCatalog(UUID versionId, String catalogSha256, List<CatalogModel> models) {
        public ResolvedCatalog {
            models = List.copyOf(Objects.requireNonNull(models, "models"));
        }
    }

    private final InstanceModelProperties properties;
    private final ObjectProvider<AiModelProvider> providerBeans;
    private final LabModelCatalogVersionRepository versions;
    private final LabModelCatalogEntryRepository entries;
    private final TransactionTemplate persistence;

    /**
     * Memoized because resolution writes: without it, every estimate would re-hash the
     * configuration and re-query for the version it already knows.
     */
    private volatile ResolvedCatalog resolved;

    public InstanceModelCatalogService(InstanceModelProperties properties,
                                       ObjectProvider<AiModelProvider> providerBeans,
                                       LabModelCatalogVersionRepository versions,
                                       LabModelCatalogEntryRepository entries,
                                       PlatformTransactionManager transactionManager) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.providerBeans = Objects.requireNonNull(providerBeans, "providerBeans");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.persistence = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** The active catalog, resolving and persisting it on first use. */
    public ResolvedCatalog active() {
        ResolvedCatalog current = resolved;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (resolved == null) {
                resolved = resolve();
            }
            return resolved;
        }
    }

    /** Every model this deployment can actually offer, in stable provider-then-model order. */
    public List<CatalogModel> available() {
        return active().models();
    }

    /** One model from the active catalog, or a refusal. Never a fallback price. */
    public CatalogModel require(String provider, String model) {
        return find(provider, model).orElseThrow(
                () -> new ModelCatalogException(ModelCatalogException.Code.MODEL_NOT_IN_CATALOG));
    }

    public Optional<CatalogModel> find(String provider, String model) {
        if (provider == null || model == null) {
            return Optional.empty();
        }
        return active().models().stream()
                .filter(entry -> entry.provider().equals(provider) && entry.model().equals(model))
                .findFirst();
    }

    /**
     * One model as it was priced in a specific historical version.
     *
     * <p>Reads the stored rows rather than the active catalog, because pricing an old run against
     * today's configuration is exactly the mistake versioning exists to prevent.
     */
    public Optional<CatalogModel> findInVersion(UUID versionId, String provider, String model) {
        if (versionId == null || provider == null || model == null) {
            return Optional.empty();
        }
        return entries.findByCatalogVersionIdAndProviderAndModel(versionId, provider, model)
                .map(InstanceModelCatalogService::toCatalogModel);
    }

    // ================================================================ resolution

    private ResolvedCatalog resolve() {
        List<CatalogModel> configured = validated();
        Set<String> credentialed = providerBeans.stream()
                .map(AiModelProvider::getProviderName)
                .collect(Collectors.toSet());

        // A model whose provider has no bean has no credentials, so offering it would be offering
        // something nothing can call.
        List<CatalogModel> offerable = configured.stream()
                .filter(entry -> credentialed.contains(entry.provider()))
                .toList();
        if (offerable.isEmpty()) {
            throw new ModelCatalogException(ModelCatalogException.Code.MODEL_CATALOG_UNAVAILABLE);
        }

        String hash = canonicalHash(offerable);
        return persistence.execute(status -> {
            LabModelCatalogVersion version = versions.findByCatalogSha256(hash)
                    .orElseGet(() -> append(hash, offerable));
            return new ResolvedCatalog(version.getId(), hash, offerable);
        });
    }

    private LabModelCatalogVersion append(String hash, List<CatalogModel> offerable) {
        LabModelCatalogVersion version = versions.saveAndFlush(
                new LabModelCatalogVersion(hash, offerable.size()));
        entries.saveAll(offerable.stream()
                .map(entry -> new LabModelCatalogEntry(version.getId(), entry.provider(),
                        entry.model(), entry.contextTokenCeiling(), entry.outputTokenCeiling(),
                        entry.tokenizerStrategy(), entry.inputUsdPerMillion(),
                        entry.cachedInputUsdPerMillion(), entry.outputUsdPerMillion()))
                .toList());
        return version;
    }

    /** Every configured entry, checked and sorted. Fails closed on anything it cannot trust. */
    private List<CatalogModel> validated() {
        List<InstanceModelProperties.ModelEntry> declared = properties.models();
        if (declared.isEmpty()) {
            throw new ModelCatalogException(ModelCatalogException.Code.MODEL_CATALOG_EMPTY);
        }
        Set<String> seen = new HashSet<>();
        List<CatalogModel> models = new ArrayList<>(declared.size());
        for (InstanceModelProperties.ModelEntry entry : declared) {
            models.add(check(entry));
            if (!seen.add(entry.provider() + " " + entry.model())) {
                throw new ModelCatalogException(
                        ModelCatalogException.Code.MODEL_CATALOG_DUPLICATE);
            }
        }
        return models.stream()
                .sorted(Comparator.comparing(CatalogModel::provider)
                        .thenComparing(CatalogModel::model))
                .toList();
    }

    private static CatalogModel check(InstanceModelProperties.ModelEntry entry) {
        if (blank(entry.provider()) || blank(entry.model())
                || entry.contextTokenCeiling() <= 0 || entry.outputTokenCeiling() <= 0
                || entry.outputTokenCeiling() > entry.contextTokenCeiling()
                || negative(entry.inputUsdPerMillion()) || negative(entry.outputUsdPerMillion())
                || (entry.cachedInputUsdPerMillion() != null
                    && entry.cachedInputUsdPerMillion().signum() < 0)) {
            throw new ModelCatalogException(ModelCatalogException.Code.MODEL_CATALOG_INVALID);
        }
        LabModelCatalogEntry.TokenizerStrategy strategy;
        try {
            strategy = LabModelCatalogEntry.TokenizerStrategy.valueOf(
                    Objects.requireNonNull(entry.tokenizer(), "tokenizer"));
        } catch (IllegalArgumentException | NullPointerException unrecognised) {
            throw new ModelCatalogException(ModelCatalogException.Code.MODEL_CATALOG_INVALID);
        }
        return new CatalogModel(entry.provider(), entry.model(), entry.contextTokenCeiling(),
                entry.outputTokenCeiling(), strategy, entry.inputUsdPerMillion(),
                entry.cachedInputUsdPerMillion(), entry.outputUsdPerMillion());
    }

    /**
     * Length-prefixed canonical digest over the sorted entries.
     *
     * <p>Length prefixes rather than a delimiter, so no combination of configured values can be
     * rearranged into a different list that hashes the same. Prices are compared as plain strings
     * of their configured scale, which means {@code 3.00} and {@code 3.0} are different catalogs.
     * That is deliberate: a scale change is a change to what was written down.
     */
    private static String canonicalHash(List<CatalogModel> models) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CatalogModel model : models) {
                update(digest, model.provider());
                update(digest, model.model());
                update(digest, Long.toString(model.contextTokenCeiling()));
                update(digest, Long.toString(model.outputTokenCeiling()));
                update(digest, model.tokenizerStrategy().name());
                update(digest, model.inputUsdPerMillion().toPlainString());
                update(digest, model.cachedInputUsdPerMillion() == null
                        ? "" : model.cachedInputUsdPerMillion().toPlainString());
                update(digest, model.outputUsdPerMillion().toPlainString());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    private static void update(MessageDigest digest, String field) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static CatalogModel toCatalogModel(LabModelCatalogEntry entry) {
        return new CatalogModel(entry.getProvider(), entry.getModel(),
                entry.getContextTokenCeiling(), entry.getOutputTokenCeiling(),
                entry.getTokenizerStrategy(), entry.getInputUsdPerMillion(),
                entry.getCachedInputUsdPerMillion(), entry.getOutputUsdPerMillion());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean negative(BigDecimal value) {
        return value == null || value.signum() < 0;
    }
}
