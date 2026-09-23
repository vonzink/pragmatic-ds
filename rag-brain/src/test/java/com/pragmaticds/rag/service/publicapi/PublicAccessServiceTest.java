package com.pragmaticds.rag.service.publicapi;

import com.pragmaticds.rag.domain.BrainMode;
import com.pragmaticds.rag.domain.BrainProfile;
import com.pragmaticds.rag.repository.BrainProfileRepository;
import com.pragmaticds.rag.service.profile.BrainProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicAccessServiceTest {

    private final BrainProfileService profiles = mock(BrainProfileService.class);
    private final BrainProfileRepository repository = mock(BrainProfileRepository.class);
    private final PublicAccessService service = new PublicAccessService(profiles, repository);

    private BrainProfile profile(UUID brainId, String token) {
        BrainProfile p = new BrainProfile();
        p.setBrainId(brainId);
        p.setMode(BrainMode.PUBLIC_SITE);
        p.setPublicEnabled(true);
        p.setAllowedDomains(List.of("example.com", "www.example.com"));
        p.setPublicTokenHash(PublicAccessService.hashToken(token));
        return p;
    }

    @Test
    void validateAcceptsMatchingTokenAndAllowedOrigin() {
        UUID brainId = UUID.randomUUID();
        when(profiles.getOrCreate(brainId)).thenReturn(profile(brainId, "pub_test"));

        assertDoesNotThrow(() -> service.validate(brainId, "pub_test", "https://www.example.com/page"));
    }

    @Test
    void validateRejectsBadToken() {
        UUID brainId = UUID.randomUUID();
        when(profiles.getOrCreate(brainId)).thenReturn(profile(brainId, "pub_test"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.validate(brainId, "wrong", "https://example.com"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void validateRejectsMissingTokenAsUnauthorized() {
        UUID brainId = UUID.randomUUID();
        when(profiles.getOrCreate(brainId)).thenReturn(profile(brainId, "pub_test"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.validate(brainId, " ", "https://example.com"));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void validateRejectsUnlistedOrigin() {
        UUID brainId = UUID.randomUUID();
        when(profiles.getOrCreate(brainId)).thenReturn(profile(brainId, "pub_test"));

        assertThrows(IllegalArgumentException.class,
                () -> service.validate(brainId, "pub_test", "https://evil.test"));
    }

    @Test
    void validateRejectsPublicProfileWithNoAllowedDomains() {
        UUID brainId = UUID.randomUUID();
        BrainProfile p = profile(brainId, "pub_test");
        p.setAllowedDomains(List.of());
        when(profiles.getOrCreate(brainId)).thenReturn(p);

        assertThrows(IllegalArgumentException.class,
                () -> service.validate(brainId, "pub_test", "https://example.com"));
    }

    @Test
    void rotateTokenRejectsPublicProfileWithNoAllowedDomains() {
        UUID brainId = UUID.randomUUID();
        BrainProfile p = profile(brainId, "old");
        p.setAllowedDomains(List.of());
        when(profiles.getOrCreate(brainId)).thenReturn(p);

        assertThrows(IllegalArgumentException.class, () -> service.rotateToken(brainId));
    }

    @Test
    void rotateTokenStoresHashAndReturnsPlainTokenOnce() {
        UUID brainId = UUID.randomUUID();
        BrainProfile p = profile(brainId, "old");
        when(profiles.getOrCreate(brainId)).thenReturn(p);
        when(repository.save(any(BrainProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        String token = service.rotateToken(brainId);

        assertEquals(true, token.startsWith("rb_pub_"));
        assertEquals(PublicAccessService.hashToken(token), p.getPublicTokenHash());
        verify(repository).save(p);
    }
}
