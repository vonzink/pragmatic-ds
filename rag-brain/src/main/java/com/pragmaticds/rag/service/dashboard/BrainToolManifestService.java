package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.domain.BrainToolDefinition;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import com.pragmaticds.rag.dto.ToolDefinitionDto;
import com.pragmaticds.rag.dto.ToolDefinitionRequest;
import com.pragmaticds.rag.repository.BrainToolDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BrainToolManifestService {

    private final BrainToolDefinitionRepository repo;

    public BrainToolManifestService(BrainToolDefinitionRepository repo) {
        this.repo = repo;
    }

    public List<ToolDefinitionDto> list(UUID brainId) {
        return repo.findAllByBrainIdOrderByCreatedAtDescIdDesc(brainId).stream()
                .map(ToolDefinitionDto::from)
                .toList();
    }

    public ToolDefinitionDto get(UUID brainId, UUID id) {
        return ToolDefinitionDto.from(find(brainId, id));
    }

    public List<DashboardToolDefinition> active(UUID brainId) {
        return repo.findByBrainIdAndActiveTrueOrderByCreatedAtDescIdDesc(brainId).stream()
                .map(BrainToolManifestService::toToolDefinition)
                .toList();
    }

    public DashboardToolDefinition requireActive(UUID brainId, String name) {
        return repo.findByBrainIdAndNameAndActiveTrue(brainId, name)
                .map(BrainToolManifestService::toToolDefinition)
                .orElseThrow(() -> new IllegalArgumentException("Unknown dashboard tool: " + name));
    }

    @Transactional
    public ToolDefinitionDto create(UUID brainId, ToolDefinitionRequest req, String createdBy) {
        String name = required(req.name(), "name");
        if (repo.existsByBrainIdAndName(brainId, name)) {
            throw new IllegalArgumentException("tool definition already exists for brain: " + name);
        }
        DashboardToolMode mode = mode(req.mode());
        BrainToolDefinition tool = new BrainToolDefinition(
                brainId,
                name,
                required(req.description(), "description"),
                mode,
                confirmationRequired(mode, req),
                cleanList(req.requiredPermissions()),
                cleanSchema(req.inputSchema()),
                createdBy);
        return ToolDefinitionDto.from(repo.save(tool));
    }

    @Transactional
    public ToolDefinitionDto update(UUID brainId, UUID id, ToolDefinitionRequest req, String updatedBy) {
        BrainToolDefinition tool = find(brainId, id);
        String name = required(req.name(), "name");
        if (repo.existsByBrainIdAndNameAndIdNot(brainId, name, id)) {
            throw new IllegalArgumentException("tool definition already exists for brain: " + name);
        }
        DashboardToolMode mode = mode(req.mode());
        tool.setName(name);
        tool.setDescription(required(req.description(), "description"));
        tool.setMode(mode);
        tool.setConfirmationRequired(confirmationRequired(mode, req));
        tool.setRequiredPermissions(cleanList(req.requiredPermissions()));
        tool.setInputSchema(cleanSchema(req.inputSchema()));
        tool.setUpdatedBy(updatedBy);
        return ToolDefinitionDto.from(repo.save(tool));
    }

    @Transactional
    public ToolDefinitionDto setActive(UUID brainId, UUID id, boolean active, String updatedBy) {
        BrainToolDefinition tool = find(brainId, id);
        tool.setActive(active);
        tool.setUpdatedBy(updatedBy);
        return ToolDefinitionDto.from(repo.save(tool));
    }

    @Transactional
    public void delete(UUID brainId, UUID id) {
        repo.delete(find(brainId, id));
    }

    private BrainToolDefinition find(UUID brainId, UUID id) {
        BrainToolDefinition tool = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("tool definition not found: " + id));
        if (!tool.getBrainId().equals(brainId)) {
            throw new IllegalArgumentException("tool definition not found for brain: " + id);
        }
        return tool;
    }

    private static DashboardToolDefinition toToolDefinition(BrainToolDefinition tool) {
        return new DashboardToolDefinition(
                tool.getName(),
                tool.getDescription(),
                tool.getMode(),
                tool.isConfirmationRequired(),
                tool.getRequiredPermissions(),
                tool.getInputSchema());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static DashboardToolMode mode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("mode is required");
        }
        return DashboardToolMode.valueOf(value.strip().toUpperCase(java.util.Locale.US));
    }

    private static boolean confirmationRequired(DashboardToolMode mode, ToolDefinitionRequest req) {
        return mode == DashboardToolMode.WRITE || req.confirmationRequired();
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.strip());
            }
        }
        return out;
    }

    private static Map<String, Object> cleanSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        return new LinkedHashMap<>(schema);
    }
}
