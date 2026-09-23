package com.pragmaticds.rag.config;

import com.pragmaticds.rag.domain.BrainMode;
import com.pragmaticds.rag.repository.BrainProfileRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dynamic CORS for the public assistant + conversation endpoints. Browser
 * widgets are embedded on customer sites whose origins are NOT known at boot,
 * so this source reflects a request {@code Origin} when its host is in a brain
 * profile's {@code allowedDomains}. This lets the dashboard installer authorize
 * a site for embedding immediately, without a server restart or env change.
 *
 * <p>The static {@code CORS_ALLOWED_ORIGINS} list is always honored too (so the
 * ops dashboard and configured origins keep working). The allowed-host set is
 * cached briefly to keep preflight checks off the hot DB path.
 */
public class PublicCorsConfigurationSource implements CorsConfigurationSource {

    private static final long CACHE_TTL_MS = 30_000;
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "OPTIONS");
    private static final List<String> ALLOWED_HEADERS =
            List.of("Content-Type", "X-Public-Brain-Token", "X-Session-Id");

    private final List<String> staticOrigins;
    private final BrainProfileRepository profiles;

    private volatile Set<String> cachedHosts = Set.of();
    private volatile long cachedAt = 0L;

    public PublicCorsConfigurationSource(List<String> staticOrigins, BrainProfileRepository profiles) {
        this.staticOrigins = staticOrigins == null ? List.of() : List.copyOf(staticOrigins);
        this.profiles = profiles;
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setMaxAge(3600L);

        List<String> origins = new ArrayList<>(staticOrigins);
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            String host = hostOf(origin);
            if (host != null && allowedHosts().contains(host)) {
                origins.add(origin.strip());
            }
        }
        config.setAllowedOrigins(origins.stream().distinct().toList());
        return config;
    }

    private Set<String> allowedHosts() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_TTL_MS) {
            return cachedHosts;
        }
        Set<String> hosts = new HashSet<>();
        profiles.findAll().forEach(profile -> {
            if (profile.isPublicEnabled() && profile.getMode() == BrainMode.PUBLIC_SITE
                    && profile.getAllowedDomains() != null) {
                profile.getAllowedDomains().forEach(domain -> {
                    if (domain != null && !domain.isBlank()) {
                        hosts.add(domain.strip().toLowerCase(Locale.US));
                    }
                });
            }
        });
        cachedHosts = Set.copyOf(hosts);
        cachedAt = now;
        return cachedHosts;
    }

    private static String hostOf(String origin) {
        try {
            URI uri = URI.create(origin.strip());
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.US);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
