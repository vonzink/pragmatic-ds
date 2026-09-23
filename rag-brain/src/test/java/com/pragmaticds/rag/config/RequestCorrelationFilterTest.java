package com.pragmaticds.rag.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter("X-Auth-Request-Email");

    @Test
    void generatesRequestIdAndClearsMdcAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/admin/stats");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> idDuringRequest = new AtomicReference<>();
        FilterChain chain = (req, res) -> idDuringRequest.set(MDC.get(RequestCorrelationFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertNotNull(idDuringRequest.get(), "requestId is present in MDC during the request");
        assertEquals(idDuringRequest.get(), response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER));
        assertNull(MDC.get(RequestCorrelationFilter.MDC_KEY), "MDC must be cleared after the request");
    }

    @Test
    void honorsCleanInboundRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(RequestCorrelationFilter.MDC_KEY)));

        assertEquals("abc-123", seen.get());
        assertEquals("abc-123", response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER));
    }

    @Test
    void bindsProxyForwardedUserToMdcAndClearsAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/admin/brains/x/learning");
        request.addHeader("X-Auth-Request-Email", "operator@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> userDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response,
                (req, res) -> userDuringRequest.set(MDC.get(RequestCorrelationFilter.USER_MDC_KEY)));

        assertEquals("operator@example.com", userDuringRequest.get());
        assertNull(MDC.get(RequestCorrelationFilter.USER_MDC_KEY), "user MDC must be cleared after the request");
    }

    @Test
    void ignoresMissingOrUnsafeProxyUserHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/admin/stats");
        request.addHeader("X-Auth-Request-Email", "bad user\ninjected");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response,
                (req, res) -> seen.set(MDC.get(RequestCorrelationFilter.USER_MDC_KEY)));

        assertNull(seen.get(), "an unsafe/newline-injected user header must be ignored");
    }

    @Test
    void rejectsUnsafeInboundRequestIdAndGeneratesOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "bad id\ninjected");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(RequestCorrelationFilter.MDC_KEY)));

        assertNotNull(seen.get());
        assertEquals(false, seen.get().contains("injected"));
    }
}
