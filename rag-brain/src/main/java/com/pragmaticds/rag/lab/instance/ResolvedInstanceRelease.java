package com.pragmaticds.rag.lab.instance;

import com.pragmaticds.rag.lab.domain.LabInstance;
import com.pragmaticds.rag.lab.domain.LabInstanceRelease;
import com.pragmaticds.rag.lab.release.DecodedInstanceManifest;

/** A brain-scoped release plus the version-specific manifest it stores. */
public record ResolvedInstanceRelease(
        LabInstance instance,
        LabInstanceRelease release,
        DecodedInstanceManifest manifest,
        boolean live) {}
