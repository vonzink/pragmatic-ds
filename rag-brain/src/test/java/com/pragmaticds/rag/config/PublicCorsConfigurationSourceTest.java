package com.pragmaticds.rag.config;

import com.pragmaticds.rag.domain.BrainMode;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.repository.BrainProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicCorsConfigurationSourceTest {

    private BrainProfile publicProfile(List<String> domains) {
        BrainProfile p = new BrainProfile();
        p.setMode(BrainMode.PUBLIC_SITE);
        p.setPublicEnabled(true);
        p.setAllowedDomains(domains);
        return p;
    }

    private CorsConfiguration configFor(String origin, BrainProfileRepository profiles) {
        PublicCorsConfigurationSource source = new PublicCorsConfigurationSource(
                List.of("http://localhost:6173"), profiles);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/public/x/ask");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return source.getCorsConfiguration(request);
    }

    @Test
    void reflectsAnOriginWhoseHostIsAllowlistedOnABrain() {
        BrainProfileRepository profiles = mock(BrainProfileRepository.class);
        when(profiles.findAll()).thenReturn(List.of(publicProfile(List.of("example.com"))));

        CorsConfiguration config = configFor("https://example.com", profiles);
        assertNotNull(config);
        // exact origin must be reflected (scheme + host) for credentials-safe CORS
        assertTrue(config.getAllowedOrigins().contains("https://example.com"),
                "an allowlisted customer origin must be reflected dynamically");
    }

    @Test
    void alwaysIncludesStaticEnvOrigins() {
        BrainProfileRepository profiles = mock(BrainProfileRepository.class);
        when(profiles.findAll()).thenReturn(List.of());

        CorsConfiguration config = configFor("https://evil.test", profiles);
        assertTrue(config.getAllowedOrigins().contains("http://localhost:6173"),
                "configured env origins are always honored");
        assertFalse(config.getAllowedOrigins().contains("https://evil.test"),
                "an un-allowlisted origin must not be reflected");
    }

    @Test
    void ignoresDomainsFromDisabledOrNonPublicProfiles() {
        BrainProfile disabled = publicProfile(List.of("disabled.com"));
        disabled.setPublicEnabled(false);
        BrainProfile secure = publicProfile(List.of("secure.com"));
        secure.setMode(BrainMode.SECURE_DEPLOYMENT);

        BrainProfileRepository profiles = mock(BrainProfileRepository.class);
        when(profiles.findAll()).thenReturn(List.of(disabled, secure));

        CorsConfiguration disabledConfig = configFor("https://disabled.com", profiles);
        assertFalse(disabledConfig.getAllowedOrigins().contains("https://disabled.com"),
                "a public-disabled brain must not authorize embedding");
    }

    @Test
    void allowsTheExpectedMethodsAndHeaders() {
        BrainProfileRepository profiles = mock(BrainProfileRepository.class);
        when(profiles.findAll()).thenReturn(List.of());

        CorsConfiguration config = configFor("https://example.com", profiles);
        assertTrue(config.getAllowedMethods().contains("POST"));
        assertTrue(config.getAllowedMethods().contains("GET"));
        assertTrue(config.getAllowedMethods().contains("OPTIONS"));
        assertTrue(config.getAllowedHeaders().contains("X-Public-Brain-Token"));
        assertTrue(config.getAllowedHeaders().contains("X-Session-Id"));
    }
}
