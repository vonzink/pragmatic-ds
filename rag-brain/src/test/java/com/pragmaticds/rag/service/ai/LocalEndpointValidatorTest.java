package com.pragmaticds.rag.service.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalEndpointValidatorTest {

    private final LocalEndpointValidator permissive = new LocalEndpointValidator("");
    private final LocalEndpointValidator allowlisted = new LocalEndpointValidator("192.168.1.50, ollama.lan");

    @Test
    void allowsLocalhostAndLanWhenPermissive() {
        permissive.validate("http://localhost:11434/v1");
        permissive.validate("http://127.0.0.1:1234/v1");
        permissive.validate("http://192.168.1.50:11434/v1");
        permissive.validate("https://lm.example.com/v1");
    }

    @Test
    void blocksLinkLocalMetadataAlways() {
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("http://169.254.169.254/latest/meta-data"));
        assertThrows(IllegalArgumentException.class, () -> allowlisted.validate("http://169.254.169.254/"));
    }

    @Test
    void rejectsNonHttpSchemeAndMissingHost() {
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("ftp://192.168.1.50/v1"));
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> permissive.validate("not a url"));
    }

    @Test
    void allowlistRestrictsToListedHosts() {
        allowlisted.validate("http://192.168.1.50:11434/v1");   // in list
        assertThrows(IllegalArgumentException.class, () -> allowlisted.validate("http://192.168.1.99:11434/v1")); // not in list
    }
}
