package com.pragmaticds.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the fail-closed {@link ConnectorAuthInterceptor} across the connector
 * surface. Every {@code /api/connect/**} endpoint and every MCP tool <em>call</em>
 * ({@code POST /mcp/tools/{name}}) requires a valid connector token before the
 * handler runs. The public discovery surfaces stay open: {@code /.well-known/…}
 * (a different path) and the static MCP tool list ({@code GET /mcp/tools}, which
 * has no trailing segment and so is not matched by {@code /mcp/tools/*}).
 */
@Configuration
public class ConnectorSecurityConfig implements WebMvcConfigurer {

    private final ConnectorAuthInterceptor connectorAuth;

    public ConnectorSecurityConfig(ConnectorAuthInterceptor connectorAuth) {
        this.connectorAuth = connectorAuth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(connectorAuth)
                .addPathPatterns("/api/connect/**", "/mcp/tools/*");
    }
}
