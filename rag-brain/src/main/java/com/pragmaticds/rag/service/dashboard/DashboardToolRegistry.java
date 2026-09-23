package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class DashboardToolRegistry {

    private final BrainToolManifestService manifests;

    public DashboardToolRegistry(BrainToolManifestService manifests) {
        this.manifests = manifests;
    }

    public List<DashboardToolDefinition> list(UUID brainId) {
        return manifests.active(brainId);
    }

    public DashboardToolDefinition require(UUID brainId, String name) {
        return manifests.requireActive(brainId, name);
    }
}
