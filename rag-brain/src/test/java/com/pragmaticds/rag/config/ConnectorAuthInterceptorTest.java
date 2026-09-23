package com.pragmaticds.rag.config;

import com.pragmaticds.rag.domain.BrainConnectorClient;
import com.pragmaticds.rag.service.connect.ConnectorAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConnectorAuthInterceptorTest {

    private final ConnectorAuthService auth = mock(ConnectorAuthService.class);
    private final ConnectorAuthInterceptor interceptor = new ConnectorAuthInterceptor(auth);

    @Test
    void skipsCorsPreflight() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/connect/v1/brains");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(auth);
    }

    @Test
    void authenticatesAndStashesClientOnValidToken() {
        BrainConnectorClient client = new BrainConnectorClient(UUID.randomUUID(), "Agent", "MCP_AGENT", "hash");
        when(auth.authenticate(any(), any())).thenReturn(client);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/connect/v1/brains");
        request.addHeader("Authorization", "Bearer rb_conn_x");
        request.addHeader("Host", "peer.local");

        boolean proceed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(proceed);
        assertSame(client, request.getAttribute(ConnectorAuthInterceptor.CLIENT_ATTRIBUTE));
    }

    @Test
    void rejectsUnauthenticatedRequestBeforeHandler() {
        when(auth.authenticate(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Connector token is required"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp/tools/rag_brain_ask");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }
}
