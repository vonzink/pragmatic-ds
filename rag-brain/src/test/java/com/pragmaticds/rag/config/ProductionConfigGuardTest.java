package com.pragmaticds.rag.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionConfigGuardTest {

    @Test
    void failsWhenAdminKeyIsDefault() {
        ProductionConfigGuard guard = new ProductionConfigGuard(
                ProductionConfigGuard.DEFAULT_ADMIN_KEY, "a-real-db-password",
                "https://app.example.com");
        assertThrows(IllegalStateException.class, guard::verify);
    }

    @Test
    void failsWhenAdminKeyIsBlank() {
        ProductionConfigGuard guard = new ProductionConfigGuard(
                "  ", "a-real-db-password", "https://app.example.com");
        assertTrue(guard.errors().stream().anyMatch(e -> e.contains("ADMIN_API_KEY")));
        assertThrows(IllegalStateException.class, guard::verify);
    }

    @Test
    void passesWithStrongConfig() {
        ProductionConfigGuard guard = new ProductionConfigGuard(
                "S3cure-Admin-Key-9f2b", "S3cure-Db-Pass", "https://app.example.com");
        assertTrue(guard.errors().isEmpty());
        assertTrue(guard.warnings().isEmpty());
        assertDoesNotThrow(guard::verify);
    }

    @Test
    void failsOnDefaultDbPasswordAndLocalhostCors() {
        ProductionConfigGuard guard = new ProductionConfigGuard(
                "S3cure-Admin-Key-9f2b", ProductionConfigGuard.DEFAULT_DB_PASSWORD,
                "http://localhost:6173,http://127.0.0.1:3000");
        assertTrue(guard.errors().stream().anyMatch(e -> e.contains("DB_PASSWORD")));
        assertTrue(guard.errors().stream().anyMatch(e -> e.contains("CORS")));
        assertThrows(IllegalStateException.class, guard::verify);
    }
}
