package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.SourceVisibility;
import com.pragmaticds.rag.dto.AskRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardAskRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardAskResponse;
import com.pragmaticds.rag.dto.DashboardAgentDtos.UserContext;
import com.pragmaticds.rag.service.AskService;
import com.pragmaticds.rag.service.connect.ConnectorPrincipal;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardAgentService {

    private final AskService askService;
    private final DashboardToolRegistry registry;

    public DashboardAgentService(AskService askService, DashboardToolRegistry registry) {
        this.askService = askService;
        this.registry = registry;
    }

    public DashboardAskResponse ask(Brain brain, DashboardAskRequest req, ConnectorPrincipal principal) {
        // Same rule as the tool gateway: request-body identity is never trusted. A
        // connector may only assert a tenant bound to its token, so it can't ask the
        // internal brain while claiming to be another tenant (the tenant_id flows into
        // prompt facts and, in future, tenant-filtered retrieval).
        UserContext user = req == null ? null : req.user();
        ConnectorPrincipal effective = principal == null
                ? ConnectorPrincipal.from(null)
                : principal;
        effective.assertMayActForTenant(user == null ? null : user.tenantId());

        SourceVisibility visibility = visibility(req == null ? null : req.visibility());
        String sessionId = req == null || req.sessionId() == null || req.sessionId().isBlank()
                ? "dashboard-session"
                : req.sessionId();
        AskRequest askRequest = new AskRequest(
                req == null ? null : req.conversationId(),
                sessionId,
                req == null ? null : req.message(),
                null,
                null,
                req == null ? null : req.pageRoute(),
                "INTERNAL",
                facts(req == null ? null : req.facts(), req == null ? null : req.user()));
        return new DashboardAskResponse(
                askService.ask(askRequest, brain.getId(), visibility),
                registry.list(brain.getId()));
    }

    private static SourceVisibility visibility(String value) {
        if (value == null || value.isBlank()) {
            return SourceVisibility.INTERNAL;
        }
        SourceVisibility visibility = SourceVisibility.valueOf(value.strip().toUpperCase(java.util.Locale.US));
        if (visibility == SourceVisibility.PUBLIC) {
            return SourceVisibility.INTERNAL;
        }
        return visibility;
    }

    private static Map<String, String> facts(Map<String, Object> requestFacts, UserContext user) {
        Map<String, String> out = new LinkedHashMap<>();
        if (requestFacts != null) {
            requestFacts.forEach((key, value) -> {
                if (key != null && value != null) {
                    out.put(key, String.valueOf(value));
                }
            });
        }
        if (user != null) {
            put(out, "user_id", user.userId());
            put(out, "tenant_id", user.tenantId());
            put(out, "roles", join(user.roles()));
            put(out, "permissions", join(user.permissions()));
        }
        return out;
    }

    private static void put(Map<String, String> facts, String key, String value) {
        if (value != null && !value.isBlank()) {
            facts.put(key, value);
        }
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(",", values);
    }
}
