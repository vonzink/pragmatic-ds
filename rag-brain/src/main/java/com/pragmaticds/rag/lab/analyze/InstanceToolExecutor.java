package com.pragmaticds.rag.lab.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.pragmaticds.rag.lab.engine.EngineResultEnvelope;

/**
 * One deterministic tool an instance release may pin.
 *
 * <p>A tool is identified by all four of name, version, and its input and output schema digests.
 * Three of those matching is not a match: a tool whose schemas changed is a different tool, and
 * silently running it would let a release drift from what it was pinned to.
 *
 * <p>Implementations are deterministic and take no external dependency at execution time. Borrower
 * data may pass through {@link #execute} and its return value, so neither may be logged; both exist
 * in request memory and encrypted run provenance and nowhere else.
 */
public interface InstanceToolExecutor {

    String name();

    String version();

    String inputSchemaSha256();

    String outputSchemaSha256();

    /** Runs the tool over one already-verified parsed envelope. */
    JsonNode execute(EngineResultEnvelope envelope);

    /**
     * Whether this tool's output IS the analyzer envelope's {@code domain} object.
     *
     * <p>Most tools compute something that travels in provenance beside the answer. A few compute
     * the answer's own structured payload from parsed facts — the assets ledger does — and that
     * output must REPLACE what the model wrote rather than sitting next to it, or the run would
     * record an engine-built ledger while reporting on a model-built one.
     *
     * <p>Structural rather than a name match: nothing downstream should have to know that a tool
     * called "assets.ledger.v1" is special. At most one pinned tool may answer true; two would
     * make the domain depend on declaration order.
     */
    default boolean producesDomain() {
        return false;
    }

    /**
     * Whether this tool contributes findings.
     *
     * <p>Unlike {@link #producesDomain()}, many tools may answer true: findings merge rather than
     * replace. A tool answering true returns {@code {"findings": [...]}} matching
     * {@code ai/tools/findings-v1.output.schema.json}, and its findings carry no subject key —
     * that is run context, and a tool holds none.
     */
    default boolean producesFindings() {
        return false;
    }
}
