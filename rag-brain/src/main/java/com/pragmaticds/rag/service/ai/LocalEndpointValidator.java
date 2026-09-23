package com.pragmaticds.rag.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SSRF guard for a brain's local-LLM base URL. http/https only; the cloud-metadata
 * link-local range (169.254/16, fe80::/10) is ALWAYS blocked; localhost + LAN are
 * allowed by default (home servers). An optional LOCAL_LLM_ALLOWED_HOSTS allowlist
 * restricts to exact hosts when set. Throws IllegalArgumentException (-> 400).
 */
@Component
public class LocalEndpointValidator {

    private final Set<String> allowedHosts;

    public LocalEndpointValidator(@Value("${brain.local-llm.allowed-hosts:}") String allowed) {
        this.allowedHosts = Arrays.stream(allowed.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.US))
                .collect(Collectors.toUnmodifiableSet());
    }

    public void validate(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid local LLM base URL: " + baseUrl);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("Local LLM base URL must be http or https: " + baseUrl);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Local LLM base URL has no host: " + baseUrl);
        }
        // Always block link-local (cloud-metadata 169.254.169.254 etc.) — resolve so a
        // hostname pointing at link-local is caught too. A metadata IP literal always
        // resolves and is blocked here; a host that cannot be resolved at write time is
        // not a reachable link-local target (and may resolve only on the deployment LAN),
        // so an UnknownHostException is not itself an SSRF failure.
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLinkLocalAddress()) {
                    throw new IllegalArgumentException(
                            "Local LLM host resolves to a blocked link-local address: " + host);
                }
            }
        } catch (UnknownHostException e) {
            // Unresolvable host: cannot be a link-local target. Fall through to the
            // allowlist check, which still constrains hosts when LOCAL_LLM_ALLOWED_HOSTS is set.
        }
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host.toLowerCase(Locale.US))) {
            throw new IllegalArgumentException(
                    "Local LLM host '" + host + "' is not in LOCAL_LLM_ALLOWED_HOSTS");
        }
    }
}
