package com.pragmaticds.rag.lab.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;
import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs exactly the tools a release pinned, in the order it pinned them.
 *
 * <p>Every contract must resolve to a tool matching all four of name, version, input schema digest,
 * and output schema digest. A missing or mismatched tool fails here, before any provider call, so a
 * release can never reach a model having silently skipped a calculation it declared.
 */
public interface InstanceToolRegistry {

    /** Executes the release's tools in declaration order, or throws before any of them run. */
    List<ExecutedTool> executePinned(
            List<InstanceReleaseManifest.ToolContract> contracts, EngineResultEnvelope envelope);

    /**
     * Resolves every contract without running anything, or throws the same code execution would.
     *
     * <p>This is what lets a release be validated before it is written rather than discovered to
     * be unrunnable at its first run: naming a tool this build does not ship is a mistake that
     * should be caught while the author is still looking at the form.
     */
    void verifyRegistered(List<InstanceReleaseManifest.ToolContract> contracts);

    /**
     * Every tool this build ships, as the four fields a contract must match exactly.
     *
     * <p>All four are part of a tool's identity, so a wizard cannot offer a tool by name alone —
     * it has to carry the version and both schema digests through to the release. An empty list is
     * a legitimate answer and means no instance can pin a tool today.
     */
    List<ToolDescriptor> registered();

    /** One offerable tool's pinned identity. */
    record ToolDescriptor(
            String name, String version, String inputSchemaSha256, String outputSchemaSha256) {}

    /** One tool's pinned identity and its output. */
    record ExecutedTool(
            String name,
            String version,
            String inputSchemaSha256,
            String outputSchemaSha256,
            JsonNode output,
            boolean producesDomain,
            boolean producesFindings) {}

    /** Stable, value-free failures. */
    final class ToolException extends RuntimeException {
        public enum Code {
            TOOL_NOT_REGISTERED,
            TOOL_CONTRACT_INVALID,
            TOOL_DUPLICATE_CONTRACT,
            TOOL_EXECUTION_FAILED,
            /** Two pinned tools both claim to produce the envelope's domain. */
            TOOL_DUPLICATE_DOMAIN_PRODUCER
        }

        private final Code code;

        public ToolException(Code code) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }
}

@Component
@ConditionalOnProperty(prefix = "ragbrain.instances", name = "enabled", havingValue = "true")
class DefaultInstanceToolRegistry implements InstanceToolRegistry {

    private final Map<String, InstanceToolExecutor> byIdentity = new HashMap<>();

    /**
     * Zero registered executors is a legitimate state, not a misconfiguration: no instance ships a
     * pre-computable tool today. Spring's plain {@code List} injection would refuse to start at
     * all in that case, so the executors arrive through a provider instead.
     */
    @Autowired
    DefaultInstanceToolRegistry(ObjectProvider<InstanceToolExecutor> executors) {
        this(executors.orderedStream().toList());
    }

    DefaultInstanceToolRegistry(List<InstanceToolExecutor> executors) {
        for (InstanceToolExecutor executor : Objects.requireNonNull(executors, "executors")) {
            byIdentity.put(identity(executor.name(), executor.version(),
                    executor.inputSchemaSha256(), executor.outputSchemaSha256()), executor);
        }
    }

    @Override
    public void verifyRegistered(List<InstanceReleaseManifest.ToolContract> contracts) {
        resolve(Objects.requireNonNull(contracts, "contracts"));
    }

    @Override
    public List<ToolDescriptor> registered() {
        // Sorted so the wizard's list does not reorder itself between deployments for no reason.
        Comparator<ToolDescriptor> byNameThenVersion = Comparator
                .comparing(ToolDescriptor::name)
                .thenComparing(ToolDescriptor::version);
        return byIdentity.values().stream()
                .map(executor -> new ToolDescriptor(executor.name(), executor.version(),
                        executor.inputSchemaSha256(), executor.outputSchemaSha256()))
                .sorted(byNameThenVersion)
                .toList();
    }

    /** The one place a contract becomes an executor, shared by validation and execution. */
    private List<InstanceToolExecutor> resolve(
            List<InstanceReleaseManifest.ToolContract> contracts) {
        List<InstanceToolExecutor> resolved = new ArrayList<>(contracts.size());
        Set<String> seen = new LinkedHashSet<>();
        for (InstanceReleaseManifest.ToolContract contract : contracts) {
            if (contract == null || blank(contract.name()) || blank(contract.version())
                    || !digest(contract.inputSchemaSha256())
                    || !digest(contract.outputSchemaSha256())) {
                throw new ToolException(ToolException.Code.TOOL_CONTRACT_INVALID);
            }
            String identity = identity(contract.name(), contract.version(),
                    contract.inputSchemaSha256(), contract.outputSchemaSha256());
            if (!seen.add(identity)) {
                throw new ToolException(ToolException.Code.TOOL_DUPLICATE_CONTRACT);
            }
            InstanceToolExecutor executor = byIdentity.get(identity);
            if (executor == null) {
                throw new ToolException(ToolException.Code.TOOL_NOT_REGISTERED);
            }
            resolved.add(executor);
        }
        // Two domain producers would make the envelope's payload depend on declaration order,
        // which is exactly the kind of silent ordering dependency a pinned release exists to
        // rule out. Caught during validation as well as execution, so a release that could
        // never run coherently is refused while its author is still looking at the form.
        if (resolved.stream().filter(InstanceToolExecutor::producesDomain).count() > 1) {
            throw new ToolException(ToolException.Code.TOOL_DUPLICATE_DOMAIN_PRODUCER);
        }
        return resolved;
    }

    @Override
    public List<ExecutedTool> executePinned(
            List<InstanceReleaseManifest.ToolContract> contracts, EngineResultEnvelope envelope) {
        Objects.requireNonNull(contracts, "contracts");
        Objects.requireNonNull(envelope, "envelope");

        // Resolve every contract first. Executing half a release's tools and then discovering the
        // next one is missing would leave a partial calculation nothing downstream could interpret.
        List<InstanceToolExecutor> resolved = resolve(contracts);

        List<ExecutedTool> executed = new ArrayList<>(resolved.size());
        for (InstanceToolExecutor executor : resolved) {
            JsonNode output;
            try {
                output = executor.execute(envelope);
            } catch (RuntimeException failure) {
                // The cause may carry borrower values, so it is deliberately not chained.
                throw new ToolException(ToolException.Code.TOOL_EXECUTION_FAILED);
            }
            if (output == null) {
                throw new ToolException(ToolException.Code.TOOL_EXECUTION_FAILED);
            }
            executed.add(new ExecutedTool(executor.name(), executor.version(),
                    executor.inputSchemaSha256(), executor.outputSchemaSha256(), output,
                    executor.producesDomain(), executor.producesFindings()));
        }
        return List.copyOf(executed);
    }

    private static String identity(
            String name, String version, String inputSha256, String outputSha256) {
        return String.join(" ", name, version, inputSha256, outputSha256);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
