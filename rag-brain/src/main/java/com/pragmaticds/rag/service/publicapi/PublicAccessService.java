package com.pragmaticds.rag.service.publicapi;

import com.pragmaticds.rag.domain.BrainMode;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.repository.BrainProfileRepository;
import com.pragmaticds.rag.service.profile.BrainProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PublicAccessService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BrainProfileService profiles;
    private final BrainProfileRepository repository;

    public PublicAccessService(BrainProfileService profiles, BrainProfileRepository repository) {
        this.profiles = profiles;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public BrainProfile validate(UUID brainId, String token, String origin) {
        BrainProfile profile = profiles.getOrCreate(brainId);
        if (!profile.isPublicEnabled() || profile.getMode() != BrainMode.PUBLIC_SITE) {
            throw new IllegalArgumentException("Public access is disabled for this brain");
        }
        requireAllowedDomains(profile);
        if (token == null || token.isBlank() || profile.getPublicTokenHash() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Public token is required");
        }
        if (!MessageDigest.isEqual(hashToken(token).getBytes(StandardCharsets.UTF_8),
                profile.getPublicTokenHash().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Public token rejected");
        }
        String host = originHost(origin);
        if (!profile.getAllowedDomains().isEmpty() && !profile.getAllowedDomains().contains(host)) {
            throw new IllegalArgumentException("Origin is not allowed for this brain");
        }
        return profile;
    }

    @Transactional
    public String rotateToken(UUID brainId) {
        BrainProfile profile = profiles.getOrCreate(brainId);
        if (!profile.isPublicEnabled() || profile.getMode() != BrainMode.PUBLIC_SITE) {
            throw new IllegalArgumentException("Public access must be enabled before generating a public token");
        }
        requireAllowedDomains(profile);
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = "rb_pub_" + HexFormat.of().formatHex(bytes);
        profile.setPublicTokenHash(hashToken(token));
        repository.save(profile);
        return token;
    }

    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void requireAllowedDomains(BrainProfile profile) {
        if (profile.getAllowedDomains() == null || profile.getAllowedDomains().isEmpty()) {
            throw new IllegalArgumentException("Allowed domains are required for public access");
        }
    }

    private static String originHost(String origin) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("Origin is required");
        }
        URI uri = URI.create(origin);
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Origin host is required");
        }
        return uri.getHost().toLowerCase();
    }
}
