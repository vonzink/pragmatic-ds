package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallRequest;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolCallResponse;
import com.pragmaticds.rag.dto.DashboardAgentDtos.DashboardToolDefinition;

public interface ToolAdapterExecutor {
    DashboardToolCallResponse execute(BrainToolAdapterConfig adapter,
                                      DashboardToolDefinition tool,
                                      DashboardToolCallRequest request);
}
