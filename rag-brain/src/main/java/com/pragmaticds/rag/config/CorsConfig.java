package com.pragmaticds.rag.config;

import com.pragmaticds.rag.repository.BrainProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS for the public website chat widget and the internal ops dashboard.
 *
 * <p>Admin and legacy surfaces use static, env-driven origins via the MVC CORS
 * mappings below. The PUBLIC assistant + conversation endpoints instead use a
 * dynamic {@link PublicCorsConfigurationSource} (registered as a high-priority
 * {@link CorsFilter}) so the dashboard installer can authorize a customer site
 * for embedding immediately by adding its domain to the brain profile — no
 * restart required. The static origins still apply there too.
 *
 * Allowed origins come from CORS_ALLOWED_ORIGINS (comma-separated), e.g.
 * "https://app.example.com,https://www.example.com". Localhost defaults support
 * local website development.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final String slug;

    public CorsConfig(@Value("${ragbrain.rag.cors.allowed-origins}") String allowedOrigins,
                      @Value("${brain.slug:generic}") String slug) {
        this.allowedOrigins = allowedOrigins.split(",");
        this.slug = slug;
    }

    /**
     * Dynamic CORS for the public/embed paths. Registered ahead of the admin key
     * filter so browser preflight succeeds before any auth, and scoped via URL
     * patterns so admin/legacy paths fall through to the MVC mappings.
     */
    @Bean
    FilterRegistrationBean<CorsFilter> publicCorsFilter(BrainProfileRepository profiles) {
        CorsConfigurationSource source = new PublicCorsConfigurationSource(
                List.of(allowedOrigins), profiles);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns("/api/ai/public/*", "/api/ai/conversations/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("publicCorsFilter");
        return registration;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/ai/" + slug + "/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Admin-Api-Key")
                .maxAge(3600);

        // PATCH is used to edit source links and page guides; DELETE removes
        // brains, source links, and page guides.
        //
        // Idempotency-Key is the Income Lab prototype's ONLY CORS addition: its upload, run,
        // and message routes require that header, so a cross-origin dashboard preflight has to
        // be allowed to send it. Nothing else here moves with it — not the origins, not the
        // methods, and not the other two mappings.
        registry.addMapping("/api/ai/admin/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "PUT", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Admin-Api-Key", "Idempotency-Key")
                .maxAge(3600);

        // PATCH edits document metadata; DELETE removes a document and its chunks.
        registry.addMapping("/api/ai/documents/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Admin-Api-Key")
                .maxAge(3600);
    }
}
