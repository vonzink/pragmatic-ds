package com.pragmaticds.rag.config;

import com.pragmaticds.rag.domain.BrainConnectorClient;
import com.pragmaticds.rag.service.connect.ConnectorAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Fail-closed authentication gate for the connector surface. Runs before any
 * controller (and before any brain/tool lookup), so a connector endpoint that
 * forgets to authenticate is still gated, and unauthenticated requests get a
 * recorded 401 rather than a 400 that reveals whether a brain or tool exists.
 * Per-endpoint scope/brain/origin authorization still runs in the controllers.
 */
@Component
public class ConnectorAuthInterceptor implements HandlerInterceptor {

    /** Request attribute holding the authenticated connector client for downstream use. */
    public static final String CLIENT_ATTRIBUTE = "ragbrain.connectorClient";

    private final ConnectorAuthService auth;

    public ConnectorAuthInterceptor(ConnectorAuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true; // CORS preflight carries no credentials
        }
        BrainConnectorClient client = auth.authenticate(
                request.getHeader("Authorization"), request.getHeader("Host"));
        request.setAttribute(CLIENT_ATTRIBUTE, client);
        return true;
    }
}
