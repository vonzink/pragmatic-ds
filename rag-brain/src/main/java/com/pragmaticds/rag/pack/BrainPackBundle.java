package com.pragmaticds.rag.pack;

import com.pragmaticds.rag.service.ai.QuestionCategory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One brain's pack plus the derived state that used to be precomputed in
 * service constructors: the compiled classifier regex map (insertion order =
 * check order) and the compiled retrieval programs. Built once per brain by
 * {@link DomainPackRegistry} and cached, so the two heavy precomputes happen
 * per brain rather than once at application boot.
 */
public record BrainPackBundle(
        DomainPack pack,
        Map<QuestionCategory, List<Pattern>> classifierPatterns,
        List<CompiledProgram> programs,
        Map<String, String> acronyms) {

    /** Builds the per-brain derived state from a loaded, already-validated pack. */
    public static BrainPackBundle of(DomainPack pack) {
        Map<QuestionCategory, List<Pattern>> compiled = new LinkedHashMap<>();
        for (DomainPack.ClassifierRule rule : pack.classifierRules()) {
            compiled.put(rule.category(),
                    rule.patterns().stream().map(Pattern::compile).toList());
        }
        return new BrainPackBundle(
                pack,
                Collections.unmodifiableMap(compiled),
                CompiledProgram.compile(pack.programRules()),
                pack.acronymExpansions());
    }
}
