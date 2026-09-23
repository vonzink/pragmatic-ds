package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.dto.SourceLinkDto;
import com.pragmaticds.rag.dto.SourceLinkRequest;
import com.pragmaticds.rag.service.BrainResolver;
import com.pragmaticds.rag.service.retrieval.SourceLinkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin CRUD for the source-link registry. Gated by AdminApiKeyFilter (the
 * /api/ai/admin prefix). All validation / not-found / bad-enum cases throw
 * IllegalArgumentException, mapped to HTTP 400 by GlobalExceptionHandler — no
 * try/catch or per-controller @ExceptionHandler here.
 */
@RestController
@RequestMapping("/api/ai/admin/source-links")
public class AdminSourceLinkController {

    private static final String UPDATED_BY = "admin-api";

    private final SourceLinkService service;
    private final BrainResolver brainResolver;

    public AdminSourceLinkController(SourceLinkService service, BrainResolver brainResolver) {
        this.service = service;
        this.brainResolver = brainResolver;
    }

    @GetMapping
    public List<SourceLinkDto> list(@RequestParam(value = "brain", required = false) String brain) {
        return service.list(brainResolver.resolve(brain).getId());
    }

    @GetMapping("/{id}")
    public SourceLinkDto get(@PathVariable UUID id,
                             @RequestParam(value = "brain", required = false) String brain) {
        return service.get(brainResolver.resolve(brain).getId(), id);
    }

    @PostMapping
    public SourceLinkDto create(@RequestBody SourceLinkRequest body,
                                @RequestParam(value = "brain", required = false) String brain) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return service.create(brainResolver.resolve(brain).getId(), body, UPDATED_BY);
    }

    @PatchMapping("/{id}")
    public SourceLinkDto update(@PathVariable UUID id, @RequestBody SourceLinkRequest body,
                                @RequestParam(value = "brain", required = false) String brain) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return service.update(brainResolver.resolve(brain).getId(), id, body, UPDATED_BY);
    }

    @PostMapping("/{id}/activate")
    public SourceLinkDto activate(@PathVariable UUID id,
                                  @RequestParam(value = "brain", required = false) String brain) {
        return service.setActive(brainResolver.resolve(brain).getId(), id, true, UPDATED_BY);
    }

    @PostMapping("/{id}/deactivate")
    public SourceLinkDto deactivate(@PathVariable UUID id,
                                    @RequestParam(value = "brain", required = false) String brain) {
        return service.setActive(brainResolver.resolve(brain).getId(), id, false, UPDATED_BY);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id,
                                                      @RequestParam(value = "brain", required = false) String brain) {
        service.delete(brainResolver.resolve(brain).getId(), id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }
}
