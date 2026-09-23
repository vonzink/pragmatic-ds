package com.pragmaticds.rag.lab.release;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Read-only display adaptation for the Income prototype's v1 releases.
 *
 * <p>A v1 manifest records observed inference and live dependencies, not model, corpus, or tool
 * pins. It therefore must never be presented as promotable v2 evidence.
 */
public final class LegacyIncomeManifestAdapter {

    public LegacyIncomeDisplay adapt(LabReleaseManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        List<String> limitations = new ArrayList<>();
        limitations.add(manifest.prototypeLimitations().code());
        limitations.addAll(manifest.prototypeLimitations().liveDependencies());
        return new LegacyIncomeDisplay(manifest.instanceSlug(), manifest.analyzerSlug(), limitations,
                false, false, false, false);
    }

    public record LegacyIncomeDisplay(
            String instanceSlug,
            String analyzerSlug,
            List<String> liveDependencies,
            boolean modelPinned,
            boolean corpusPinned,
            boolean toolsPinned,
            boolean promotionEligible) {
        public LegacyIncomeDisplay {
            liveDependencies = List.copyOf(Objects.requireNonNull(liveDependencies, "liveDependencies"));
            if (modelPinned || corpusPinned || toolsPinned || promotionEligible) {
                throw new IllegalArgumentException("legacy Income manifests are not promotion eligible");
            }
        }
    }
}
