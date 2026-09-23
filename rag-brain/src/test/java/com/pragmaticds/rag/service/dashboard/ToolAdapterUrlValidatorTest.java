package com.pragmaticds.rag.service.dashboard;

import com.pragmaticds.rag.TestBrains;
import com.pragmaticds.rag.domain.BrainToolAdapterConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolAdapterUrlValidatorTest {

    @Test
    void permitsAllowedLocalhostOutsideProd() {
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment());
        URI uri = validator.validate(URI.create("http://127.0.0.1:18091/api/search"),
                adapter(List.of("127.0.0.1")));

        assertEquals("127.0.0.1", uri.getHost());
    }

    @Test
    void rejectsHostOutsideAdapterAllowlist() {
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(URI.create("https://evil.example.com/api/search"),
                        adapter(List.of("api.example.com"))));

        assertEquals("Tool adapter host is not allowlisted: evil.example.com", ex.getMessage());
    }

    @Test
    void requiresHttpsInProd() {
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment()
                .withProperty("spring.profiles.active", "prod"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(URI.create("http://api.example.com/search"),
                        adapter(List.of("api.example.com"))));

        assertEquals("Tool adapter URL must use https in prod", ex.getMessage());
    }

    @Test
    void rejectsMetadataAddressInProd() {
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment()
                .withProperty("spring.profiles.active", "prod"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(URI.create("https://169.254.169.254/latest/meta-data"),
                        adapter(List.of("169.254.169.254"))));

        assertEquals("Tool adapter host is blocked in prod: 169.254.169.254", ex.getMessage());
    }

    @Test
    void rejectsPrivateIpv4AddressInProd() {
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment()
                .withProperty("spring.profiles.active", "prod"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(URI.create("https://10.0.0.5/api"),
                        adapter(List.of("10.0.0.5"))));

        assertEquals("Tool adapter host is blocked in prod: 10.0.0.5", ex.getMessage());
    }

    @Test
    void rejectsUniqueLocalIpv6AddressInProd() {
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment()
                .withProperty("spring.profiles.active", "prod"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(URI.create("https://[fd00::1]/api"),
                        adapter(List.of("[fd00::1]"))));

        assertEquals("Tool adapter host is blocked in prod: [fd00::1]", ex.getMessage());
    }

    @Test
    void rejectsMetadataAddressOutsideProd() {
        // Metadata IP has no legitimate tool use; blocked in every profile.
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(URI.create("http://169.254.169.254/latest/meta-data"),
                        adapter(List.of("169.254.169.254"))));

        assertEquals("Tool adapter host is blocked: 169.254.169.254", ex.getMessage());
    }

    @Test
    void rejectsPrivateIpv4AddressOutsideProd() {
        // Private RFC1918 ranges are blocked even outside prod — staging shares the VPC.
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment(),
                host -> new InetAddress[]{InetAddress.getByName("10.0.0.5")});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(URI.create("http://api.internal/search"),
                        adapter(List.of("api.internal"))));

        assertEquals("Tool adapter host is blocked: api.internal", ex.getMessage());
    }

    @Test
    void rejectsRebindingToPrivateAddressOutsideProd() {
        // Simulates DNS rebinding: an allowlisted host resolving to one public and one
        // private address is rejected because ANY blocked address fails the check.
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment(),
                host -> new InetAddress[]{
                        InetAddress.getByName("93.184.216.34"),
                        InetAddress.getByName("192.168.1.10")
                });

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(URI.create("http://api.example.com/search"),
                        adapter(List.of("api.example.com"))));

        assertEquals("Tool adapter host is blocked: api.example.com", ex.getMessage());
    }

    @Test
    void permitsPublicHostOutsideProd() {
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment(),
                host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});

        URI uri = validator.validate(URI.create("http://api.example.com/search"),
                adapter(List.of("api.example.com")));

        assertEquals("api.example.com", uri.getHost());
    }

    @Test
    void rejectsNonHttpSchemeBeforeCheckingHost() {
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(URI.create("file:///etc/passwd"),
                        adapter(List.of("etc"))));

        assertEquals("Tool adapter URL must use http or https", ex.getMessage());
    }

    @Test
    void rejectsProdHostWhenAnyResolvedAddressIsBlocked() {
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment()
                .withProperty("spring.profiles.active", "prod"),
                host -> new InetAddress[]{
                        InetAddress.getByName("8.8.8.8"),
                        InetAddress.getByName("10.0.0.5")
                });

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(URI.create("https://api.example.com/search"),
                        adapter(List.of("api.example.com"))));

        assertEquals("Tool adapter host is blocked in prod: api.example.com", ex.getMessage());
    }

    @Test
    void rejectsUnresolvedHostInProd() {
        ToolAdapterUrlValidator validator = new ToolAdapterUrlValidator(new MockEnvironment()
                .withProperty("spring.profiles.active", "prod"),
                host -> {
                    throw new java.net.UnknownHostException(host);
                });

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(URI.create("https://api.example.com/search"),
                        adapter(List.of("api.example.com"))));

        assertEquals("Tool adapter host could not be resolved in prod: api.example.com", ex.getMessage());
    }

    private static BrainToolAdapterConfig adapter(List<String> allowedHosts) {
        return new BrainToolAdapterConfig(TestBrains.DEFAULT_ID, "searchLoans", true,
                "GET", "https://api.example.com/search", "NONE", null, null,
                Map.of(), Map.of(), 5000, allowedHosts, "test");
    }
}
