package com.pragmaticds.docengine.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pragmaticds.docengine.AbstractPostgresIT;
import com.pragmaticds.docengine.platform.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A URL that maps to no handler must be 404, not 500.
 *
 * <p>Spring raises {@code NoResourceFoundException}, which implements the {@code ErrorResponse}
 * INTERFACE but does not extend {@code ErrorResponseException} — so the existing framework-status
 * handler misses it and the catch-all turns every typo'd URL into 500 INTERNAL. That is wrong for
 * a client, and it also logs an ERROR with a stack trace for what is ordinary 404 traffic, which
 * is how real incidents get lost in noise.
 *
 * <p>Found while investigating a stale container during Phase 6 — the misleading 500 is what made
 * a missing route look like a broken one.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=2"})
class UnknownRouteIT extends AbstractPostgresIT {

    @Autowired MockMvc mockMvc;

    @BeforeEach
    void bind() {
        TenantContext.set(ORG_DEV);
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    @Test
    void an_unknown_route_is_not_found_rather_than_a_server_error() throws Exception {
        mockMvc.perform(get("/v1/no-such-route"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void an_unknown_nested_route_is_also_not_found() throws Exception {
        // The shape that misled the Phase 6 investigation: a plausible-looking path under a real
        // prefix. It must be indistinguishable from any other unmapped URL.
        mockMvc.perform(get("/v1/packages/11111111-1111-1111-1111-111111111111/not-a-subresource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
