package com.pragmaticds.rag.lab.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.pragmaticds.rag.lab.analyze.InstanceToolRegistry.ExecutedTool;
import com.pragmaticds.rag.lab.analyze.InstanceToolRegistry.ToolException;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.findings.TestEnvelopes;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest.ToolContract;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pinned tool execution.
 *
 * <p>The point of these is that a release either runs exactly the tools it declared or runs none of
 * them. A half-executed tool list would reach the model as a calculation nothing downstream could
 * interpret, which is worse than a clean refusal.
 */
class InstanceToolRegistryTest {

    private static final String IN = "a".repeat(64);
    private static final String OUT = "b".repeat(64);

    private final List<String> executionLog = new ArrayList<>();

    @Test
    void toolsRunInTheOrderTheReleasePinnedThem() {
        InstanceToolRegistry registry = registryOf(
                tool("income.total", "1.0.0", IN, OUT),
                tool("income.variance", "1.0.0", IN, OUT));

        List<ExecutedTool> executed = registry.executePinned(List.of(
                new ToolContract("income.variance", "1.0.0", IN, OUT),
                new ToolContract("income.total", "1.0.0", IN, OUT)), envelope());

        assertEquals(List.of("income.variance", "income.total"),
                executed.stream().map(ExecutedTool::name).toList());
        assertEquals(List.of("income.variance", "income.total"), executionLog);
        assertEquals("income.variance", executed.getFirst().output().get("tool").asText());
    }

    @Test
    void anUnregisteredToolStopsTheReleaseBeforeAnyToolRuns() {
        InstanceToolRegistry registry = registryOf(tool("income.total", "1.0.0", IN, OUT));

        assertEquals(ToolException.Code.TOOL_NOT_REGISTERED, assertThrows(ToolException.class,
                () -> registry.executePinned(List.of(
                        new ToolContract("income.total", "1.0.0", IN, OUT),
                        new ToolContract("income.missing", "1.0.0", IN, OUT)), envelope())).code());

        // The first contract resolved, but nothing ran: resolution completes before execution.
        assertEquals(List.of(), executionLog);
    }

    /**
     * What the authoring surface is offered is exactly what execution will accept.
     *
     * <p>A tool is identified by all four fields at once, so a published list that dropped or
     * reordered one would let an author pin a contract the registry then refuses. The order is
     * asserted because a list that reshuffles between requests is a form whose options move under
     * the person using it.
     */
    @Test
    void everyShippedToolIsPublishedAsThePinnableContractInAStableOrder() {
        InstanceToolRegistry registry = registryOf(
                tool("income.variance", "1.0.0", IN, OUT),
                tool("income.total", "2.0.0", IN, OUT),
                tool("income.total", "1.0.0", IN, OUT));

        List<InstanceToolRegistry.ToolDescriptor> registered = registry.registered();

        assertEquals(List.of(
                new InstanceToolRegistry.ToolDescriptor("income.total", "1.0.0", IN, OUT),
                new InstanceToolRegistry.ToolDescriptor("income.total", "2.0.0", IN, OUT),
                new InstanceToolRegistry.ToolDescriptor("income.variance", "1.0.0", IN, OUT)),
                registered);
        assertEquals(registered, registry.registered(), "the offered order must not move");

        // Publishing a contract the server then refuses would be worse than publishing nothing.
        registry.verifyRegistered(registered.stream()
                .map(descriptor -> new ToolContract(descriptor.name(), descriptor.version(),
                        descriptor.inputSchemaSha256(), descriptor.outputSchemaSha256()))
                .toList());

        assertEquals(List.of(), registryOf().registered(),
                "shipping no tool at all is a legitimate state, not an error");
    }

    @Test
    void aToolWhoseSchemaDigestMovedIsADifferentTool() {
        InstanceToolRegistry registry = registryOf(tool("income.total", "1.0.0", IN, OUT));

        for (ToolContract nearMiss : List.of(
                new ToolContract("income.total", "2.0.0", IN, OUT),
                new ToolContract("income.total", "1.0.0", "c".repeat(64), OUT),
                new ToolContract("income.total", "1.0.0", IN, "d".repeat(64)))) {
            assertEquals(ToolException.Code.TOOL_NOT_REGISTERED,
                    assertThrows(ToolException.class,
                            () -> registry.executePinned(List.of(nearMiss), envelope())).code(),
                    "three of four fields matching is not a match");
        }
    }

    @Test
    void malformedAndRepeatedContractsAreRefused() {
        InstanceToolRegistry registry = registryOf(tool("income.total", "1.0.0", IN, OUT));

        assertEquals(ToolException.Code.TOOL_DUPLICATE_CONTRACT,
                assertThrows(ToolException.class, () -> registry.executePinned(List.of(
                        new ToolContract("income.total", "1.0.0", IN, OUT),
                        new ToolContract("income.total", "1.0.0", IN, OUT)), envelope())).code());

        assertEquals(ToolException.Code.TOOL_CONTRACT_INVALID,
                assertThrows(ToolException.class, () -> registry.executePinned(
                        List.of(new ToolContract("", "1.0.0", IN, OUT)), envelope())).code());

        assertEquals(ToolException.Code.TOOL_CONTRACT_INVALID,
                assertThrows(ToolException.class, () -> registry.executePinned(
                        List.of(new ToolContract("income.total", "1.0.0", "NOT-HEX", OUT)),
                        envelope())).code());
    }

    @Test
    void aFailingToolReportsACodeAndNeverChainsItsCause() {
        InstanceToolRegistry registry = registryOf(new FakeTool("income.total", "1.0.0", IN, OUT) {
            @Override
            public JsonNode execute(EngineResultEnvelope envelope) {
                throw new IllegalStateException("borrower gross pay 4,812.55 did not parse");
            }
        });

        ToolException failure = assertThrows(ToolException.class, () -> registry.executePinned(
                List.of(new ToolContract("income.total", "1.0.0", IN, OUT)), envelope()));

        assertEquals(ToolException.Code.TOOL_EXECUTION_FAILED, failure.code());
        // The cause could carry borrower values into a stack trace, so it is never attached.
        assertNull(failure.getCause());
        assertEquals("TOOL_EXECUTION_FAILED", failure.getMessage());
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Two tools both claiming to own the envelope's domain is refused, and refused during
     * VALIDATION as well as execution.
     *
     * <p>Whichever ran last would silently win, making the analyzer's structured payload depend
     * on the order a release happened to list its tools — precisely the kind of hidden ordering
     * dependency pinning exists to eliminate. Catching it in verifyRegistered means the release
     * author sees it while still looking at the form, not at the first run.
     */
    @Test
    void twoDomainProducingToolsAreRefusedBeforeEitherRuns() {
        InstanceToolRegistry registry = registryOf(
                domainTool("assets.ledger.v1", "1.0.0", IN, OUT),
                domainTool("assets.ledger.v2", "1.0.0", IN, OUT));
        List<ToolContract> both = List.of(
                new ToolContract("assets.ledger.v1", "1.0.0", IN, OUT),
                new ToolContract("assets.ledger.v2", "1.0.0", IN, OUT));

        assertEquals(ToolException.Code.TOOL_DUPLICATE_DOMAIN_PRODUCER,
                assertThrows(ToolException.class,
                        () -> registry.executePinned(both, envelope())).code());
        assertEquals(List.of(), executionLog, "neither may run");

        assertEquals(ToolException.Code.TOOL_DUPLICATE_DOMAIN_PRODUCER,
                assertThrows(ToolException.class,
                        () -> registry.verifyRegistered(both)).code());
    }

    /** One domain producer is the supported case and carries its claim through to the caller. */
    @Test
    void oneDomainProducingToolIsCarriedThroughAsSuch() {
        InstanceToolRegistry registry = registryOf(
                domainTool("assets.ledger.v1", "1.0.0", IN, OUT),
                tool("income.total", "1.0.0", IN, OUT));

        List<ExecutedTool> executed = registry.executePinned(List.of(
                new ToolContract("assets.ledger.v1", "1.0.0", IN, OUT),
                new ToolContract("income.total", "1.0.0", IN, OUT)), envelope());

        assertEquals(List.of(true, false),
                executed.stream().map(ExecutedTool::producesDomain).toList());
    }

    private FakeTool domainTool(
            String name, String version, String inputSha, String outputSha) {
        return new FakeTool(name, version, inputSha, outputSha) {
            @Override
            public boolean producesDomain() {
                return true;
            }
        };
    }

    /**
     * Unlike a domain producer, many findings producers may run in the same release: findings
     * merge rather than replace, so there is nothing here analogous to
     * {@code TOOL_DUPLICATE_DOMAIN_PRODUCER}.
     */
    @Test
    void executedToolCarriesTheFindingsFlag() {
        InstanceToolRegistry registry = registryOf(
                findingsTool("findings.tool", "1.0.0", IN, OUT),
                tool("plain.tool", "1.0.0", IN, OUT));

        List<ExecutedTool> executed = registry.executePinned(List.of(
                new ToolContract("findings.tool", "1.0.0", IN, OUT),
                new ToolContract("plain.tool", "1.0.0", IN, OUT)), envelope());

        assertEquals(List.of(true, false),
                executed.stream().map(ExecutedTool::producesFindings).toList());
    }

    @Test
    void manyToolsMayProduceFindings() {
        InstanceToolRegistry registry = registryOf(
                findingsTool("findings.one", "1.0.0", IN, OUT),
                findingsTool("findings.two", "1.0.0", IN, OUT));

        List<ExecutedTool> executed = registry.executePinned(List.of(
                new ToolContract("findings.one", "1.0.0", IN, OUT),
                new ToolContract("findings.two", "1.0.0", IN, OUT)), envelope());

        assertEquals(2, executed.stream().filter(ExecutedTool::producesFindings).count());
    }

    private FakeTool findingsTool(
            String name, String version, String inputSha, String outputSha) {
        return new FakeTool(name, version, inputSha, outputSha) {
            @Override
            public boolean producesFindings() {
                return true;
            }
        };
    }

    private InstanceToolRegistry registryOf(InstanceToolExecutor... executors) {
        return new DefaultInstanceToolRegistry(List.of(executors));
    }

    private FakeTool tool(String name, String version, String inputSha, String outputSha) {
        return new FakeTool(name, version, inputSha, outputSha);
    }

    private class FakeTool implements InstanceToolExecutor {
        private final String name;
        private final String version;
        private final String inputSha;
        private final String outputSha;

        FakeTool(String name, String version, String inputSha, String outputSha) {
            this.name = name;
            this.version = version;
            this.inputSha = inputSha;
            this.outputSha = outputSha;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public String inputSchemaSha256() {
            return inputSha;
        }

        @Override
        public String outputSchemaSha256() {
            return outputSha;
        }

        @Override
        public JsonNode execute(EngineResultEnvelope envelope) {
            executionLog.add(name);
            return JsonNodeFactory.instance.objectNode().put("tool", name);
        }
    }

    private static EngineResultEnvelope envelope() {
        return TestEnvelopes.envelope();
    }
}
