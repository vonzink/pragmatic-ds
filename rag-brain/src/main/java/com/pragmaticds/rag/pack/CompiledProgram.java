package com.pragmaticds.rag.pack;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A program-detection rule with its word-boundary regexes precompiled.
 * Lives in the pack package (not nested in RetrievalService) so the
 * DomainPackRegistry can build and cache it per brain and RetrievalService
 * can consume it — without a circular dependency between the two.
 */
public record CompiledProgram(String name, List<String> keywords, List<Pattern> patterns) {

    /** Compiles a pack's {@link DomainPack.ProgramRule} list into detection-ready programs. */
    public static List<CompiledProgram> compile(List<DomainPack.ProgramRule> rules) {
        return rules.stream()
                .map(r -> new CompiledProgram(
                        r.program(),
                        r.keywords(),
                        r.wordPatterns().stream().map(Pattern::compile).toList()))
                .toList();
    }
}
