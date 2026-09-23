package com.pragmaticds.docengine.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Drift guard for the COMMITTED contract at {@code docs/api/openapi.json} — the file consumers and
 * client generators read, as opposed to the live {@code /v3/api-docs} the ITs assert against.
 *
 * <p>The one regression this exists to catch is the known MockMvc regeneration trap: MockMvc
 * reports no port, so a regeneration through it silently rewrites {@code servers[0].url} from
 * {@code http://localhost:9090} to {@code http://localhost} — and every generated client then
 * targets port 80. Nothing else guards this file (there is deliberately no CI schema-drift gate
 * yet), so the server URL is pinned here, where a regeneration must confront it.
 */
class CommittedOpenApiContractTest {

    @Test
    void the_committed_contract_keeps_the_engines_real_port_in_its_server_url() {
        JsonNode api = readCommittedContract();

        JsonNode servers = api.path("servers");
        assertThat(servers.isArray()).as("servers block present").isTrue();
        assertThat(servers).isNotEmpty();
        assertThat(servers.get(0).path("url").asText())
                .as("MockMvc regeneration drops the port; the committed contract must keep it")
                .isEqualTo("http://localhost:9090");
    }

    private static JsonNode readCommittedContract() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && current != null; i++, current = current.getParent()) {
            Path contract = current.resolve("docs").resolve("api").resolve("openapi.json");
            if (Files.isRegularFile(contract)) {
                try {
                    return new ObjectMapper().readTree(Files.readString(contract));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException(
                "docs/api/openapi.json not found above " + System.getProperty("user.dir"));
    }
}
