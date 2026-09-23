package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.ToolAdapterConfigDto;
import com.pragmaticds.rag.dto.ToolAdapterConfigRequest;
import com.pragmaticds.rag.dto.ToolAdapterRunDto;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.dashboard.ToolAdapterConfigService;
import com.pragmaticds.rag.service.dashboard.ToolAdapterRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/admin/tool-adapters")
public class AdminToolAdapterConfigController {

    private static final String UPDATED_BY = "admin-api";

    private final ToolAdapterConfigService service;
    private final ToolAdapterRunService runService;
    private final BrainResolver brainResolver;

    public AdminToolAdapterConfigController(ToolAdapterConfigService service,
                                            ToolAdapterRunService runService,
                                            BrainResolver brainResolver) {
        this.service = service;
        this.runService = runService;
        this.brainResolver = brainResolver;
    }

    @GetMapping("/{toolName}")
    public ToolAdapterConfigDto get(@PathVariable String toolName,
                                    @RequestParam(value = "brain", required = false) String brain) {
        return service.get(brainResolver.resolve(brain).getId(), toolName);
    }

    @PutMapping("/{toolName}")
    public ToolAdapterConfigDto put(@PathVariable String toolName,
                                    @RequestBody ToolAdapterConfigRequest body,
                                    @RequestParam(value = "brain", required = false) String brain) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return service.upsert(brainResolver.resolve(brain).getId(), toolName, body, UPDATED_BY);
    }

    @DeleteMapping("/{toolName}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String toolName,
                                                      @RequestParam(value = "brain", required = false) String brain) {
        service.delete(brainResolver.resolve(brain).getId(), toolName);
        return ResponseEntity.ok(Map.of("deleted", true, "toolName", toolName));
    }

    /** Recent (sanitized) execution records for a tool's adapter — the read side of the audit trail. */
    @GetMapping("/{toolName}/runs")
    public List<ToolAdapterRunDto> runs(@PathVariable String toolName,
                                        @RequestParam(value = "brain", required = false) String brain) {
        return runService.recentRuns(brainResolver.resolve(brain).getId(), toolName);
    }
}
