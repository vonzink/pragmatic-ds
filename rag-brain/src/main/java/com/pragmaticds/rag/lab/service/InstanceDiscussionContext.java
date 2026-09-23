package com.pragmaticds.rag.lab.service;

import com.pragmaticds.rag.lab.analyze.InstanceRunProvenance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything a follow-up question may see, assembled from what one run already saved.
 *
 * <p><b>Nothing here is fetched.</b> The parsed facts, the retrieved evidence, the tool results,
 * and the answer all come out of the run's own sealed provenance and output. That is the entire
 * point of a run-pinned discussion: a question about an answer must be answered from the same
 * material the answer was produced from, or it is a different run wearing a conversation's
 * clothes. It also means a discussion cannot drift as the corpus changes underneath it.
 *
 * <p><b>It fails rather than trimming.</b> A context that will not fit the release's discussion
 * budget is refused, because silently dropping evidence would produce an answer grounded in less
 * than the run was, while still presenting itself as being about that run.
 */
public record InstanceDiscussionContext(
        String factsBlock,
        List<Evidence> evidence,
        List<Tool> tools,
        String answerJson,
        String provider,
        String model,
        long estimatedTokens) {

    public InstanceDiscussionContext {
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    }

    /** One retrieved chunk, exactly as the run read it. */
    public record Evidence(String sourceName, String documentTitle, String content) {}

    /** One tool the run executed, by pinned identity. Outputs are not replayed here. */
    public record Tool(String name, String version, String status) {}

    /** Why a run cannot be discussed. Stable, value-free codes. */
    public enum Refusal {
        /** Only a succeeded run has an answer to ask about. */
        RUN_NOT_SUCCEEDED,
        /** The run predates pinned provenance, so there is no saved context to answer from. */
        RUN_PROVENANCE_ABSENT,
        /** The run's stored output could not be opened. */
        RUN_OUTPUT_ABSENT,
        /** The saved context exceeds the release's discussion budget. */
        DISCUSSION_CONTEXT_TOO_LARGE
    }

    /**
     * Builds the context from sealed provenance and output.
     *
     * <p>Evidence order is the order retrieval returned it, because that is the order the run's
     * prompt presented it in and a follow-up should see the same ranking.
     */
    public static InstanceDiscussionContext of(InstanceRunProvenance provenance, String factsBlock,
                                               String answerJson, long estimatedTokens) {
        List<Evidence> evidence = new ArrayList<>(provenance.retrieved().size());
        for (InstanceRunProvenance.RetrievedEvidence chunk : provenance.retrieved()) {
            evidence.add(new Evidence(chunk.sourceName(), chunk.documentTitle(), chunk.content()));
        }
        List<Tool> tools = new ArrayList<>(provenance.tools().size());
        for (InstanceRunProvenance.ExecutedToolRecord tool : provenance.tools()) {
            tools.add(new Tool(tool.name(), tool.version(), tool.status()));
        }
        return new InstanceDiscussionContext(factsBlock, evidence, tools, answerJson,
                provenance.model().answeringProvider() == null
                        ? provenance.model().requestedProvider()
                        : provenance.model().answeringProvider(),
                provenance.model().answeringModel() == null
                        ? provenance.model().requestedModel()
                        : provenance.model().answeringModel(),
                estimatedTokens);
    }
}
