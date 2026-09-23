package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Component
public class ToolAdapterUrlValidator {

    private final Environment environment;
    private final HostResolver resolver;

    @Autowired
    public ToolAdapterUrlValidator(Environment environment) {
        this(environment, InetAddress::getAllByName);
    }

    ToolAdapterUrlValidator(Environment environment, HostResolver resolver) {
        this.environment = environment;
        this.resolver = resolver;
    }

    public URI validate(URI renderedUri, BrainToolAdapterConfig adapter) {
        if (renderedUri == null || !renderedUri.isAbsolute()) {
            throw new IllegalArgumentException("Tool adapter URL must be absolute");
        }
        String scheme = renderedUri.getScheme() == null ? "" : renderedUri.getScheme().toLowerCase(Locale.US);
        if (!Set.of("http", "https").contains(scheme)) {
            throw new IllegalArgumentException("Tool adapter URL must use http or https");
        }
        String host = renderedUri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Tool adapter URL host is required");
        }
        String normalizedHost = host.toLowerCase(Locale.US);
        if (!adapter.getAllowedHosts().contains(normalizedHost)) {
            throw new IllegalArgumentException("Tool adapter host is not allowlisted: " + normalizedHost);
        }
        boolean prod = isProd();
        if (prod && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Tool adapter URL must use https in prod");
        }
        // Block SSRF-prone targets (cloud metadata / link-local, RFC1918 private ranges,
        // IPv6 ULA, wildcard) in EVERY profile — a staging box typically shares the same
        // metadata endpoint and internal network as prod. Loopback stays allowed outside
        // prod so local dev tools (http://127.0.0.1) keep working.
        //
        // NOTE: this resolves the host and validates the addresses immediately before the
        // caller connects. A fully rebinding-proof fix would pin the connection to a vetted
        // InetAddress, but java.net.http.HttpClient re-resolves at connect time with no
        // per-request resolver hook; the tiny validate-vs-connect window is covered in
        // practice by the JVM positive DNS cache returning the just-resolved address.
        if (isBlockedHost(normalizedHost, prod)) {
            throw new IllegalArgumentException(
                    (prod ? "Tool adapter host is blocked in prod: " : "Tool adapter host is blocked: ")
                            + normalizedHost);
        }
        return renderedUri;
    }

    private boolean isProd() {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase)
                || Arrays.stream(String.join(",", environment.getProperty("spring.profiles.active", ""))
                        .split(",")).map(String::strip).anyMatch("prod"::equalsIgnoreCase);
    }

    private boolean isBlockedHost(String host, boolean prod) {
        // Cloud metadata IP is blocked in every profile — it has no legitimate tool use.
        if ("169.254.169.254".equals(host)) {
            return true;
        }
        // Loopback (localhost) is a valid dev target; only block it in prod.
        if (prod && "localhost".equals(host)) {
            return true;
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (Exception ex) {
            if (prod) {
                throw new IllegalArgumentException("Tool adapter host could not be resolved in prod: " + host);
            }
            // Dev leniency: an unresolvable host outside prod is not a private-network hit.
            return false;
        }
        boolean blockLoopback = prod;
        return Arrays.stream(addresses).anyMatch(address -> isBlockedAddress(address, blockLoopback));
    }

    private static boolean isBlockedAddress(InetAddress address, boolean blockLoopback) {
        byte[] bytes = address.getAddress();
        return address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)   // IPv6 unique-local fc00::/7
                || (blockLoopback && address.isLoopbackAddress());
    }
}

@FunctionalInterface
interface HostResolver {
    InetAddress[] resolve(String host) throws Exception;
}
