package com.pragmaticds.rag.controller;

import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainConnectorClient;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardAskRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardAskResponse;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallResponse;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.service.connect.ConnectorAuthService;
import com.pragmaticds.rag.service.connect.ConnectorPrincipal;
import com.pragmaticds.rag.service.connect.ConnectorScope;
import com.pragmaticds.rag.service.dashboard.DashboardAgentService;
import com.pragmaticds.rag.service.dashboard.DashboardToolGatewayService;
import com.pragmaticds.rag.service.dashboard.DashboardToolMode;
import com.pragmaticds.rag.service.dashboard.DashboardToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/connect/v1/brains/{slug}/dashboard")
public class DashboardAgentController {

    private final BrainRepository brains;
    private final ConnectorAuthService auth;
    private final DashboardToolRegistry registry;
    private final DashboardToolGatewayService gateway;
    private final DashboardAgentService agent;

    public DashboardAgentController(BrainRepository brains,
                                    ConnectorAuthService auth,
                                    DashboardToolRegistry registry,
                                    DashboardToolGatewayService gateway,
                                    DashboardAgentService agent) {
        this.brains = brains;
        this.auth = auth;
        this.registry = registry;
        this.gateway = gateway;
        this.agent = agent;
    }

    @GetMapping("/tools")
    public List<DashboardToolDefinition> tools(@PathVariable String slug,
                                               @RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestHeader(value = "Host", required = false) String host,
                                               @RequestHeader(value = "Origin", required = false) String origin) {
        Brain brain = requireBrain(slug);
        auth.require(authorization, ConnectorScope.DASHBOARD_TOOLS_LIST,
                brain.getId(), host, origin, "DASHBOARD_TOOLS_LIST");
        return registry.list(brain.getId());
    }

    @PostMapping("/tools/{toolName}/call")
    public DashboardToolCallResponse callTool(@PathVariable String slug,
                                              @PathVariable String toolName,
                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestHeader(value = "Host", required = false) String host,
                                              @RequestHeader(value = "Origin", required = false) String origin,
                                              @RequestBody DashboardToolCallRequest request) {
        Brain brain = requireBrain(slug);
        DashboardToolDefinition tool = registry.require(brain.getId(), toolName);
        String scope = tool.mode() == DashboardToolMode.WRITE
                ? ConnectorScope.DASHBOARD_TOOLS_WRITE
                : ConnectorScope.DASHBOARD_TOOLS_READ;
        String event = tool.mode() == DashboardToolMode.WRITE
                ? "DASHBOARD_TOOL_WRITE"
                : "DASHBOARD_TOOL_READ";
        BrainConnectorClient client = auth.require(authorization, scope, brain.getId(), host, origin, event);
        return gateway.call(brain.getId(), toolName, request, ConnectorPrincipal.from(client));
    }

    @PostMapping("/ask")
    public DashboardAskResponse ask(@PathVariable String slug,
                                    @RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestHeader(value = "Host", required = false) String host,
                                    @RequestHeader(value = "Origin", required = false) String origin,
                                    @RequestBody DashboardAskRequest request) {
        Brain brain = requireBrain(slug);
        BrainConnectorClient client = auth.require(authorization, ConnectorScope.DASHBOARD_ASK,
                brain.getId(), host, origin, "DASHBOARD_ASK");
        return agent.ask(brain, request, ConnectorPrincipal.from(client));
    }

    private Brain requireBrain(String slug) {
        return brains.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + slug));
    }
}
