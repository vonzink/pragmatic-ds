package com.pragmaticds.docengine.classification.rules;

/**
 * One weighted anchor of a rule pack (V6 seed comment: the v1 pack format).
 *
 * <p>{@code startsDocument} (Spec 5a) is the pack's declaration that matching this anchor means
 * the page BEGINS a new logical document — a second {@code SCHEDULE E (Form 1040)} header inside
 * one package is a second form, not a continuation of the first. It is data, not code: multiple
 * Schedule Cs and multiple K-1s need identical behaviour, and a form-specific rule inside
 * {@code PackageSplitter} would be mortgage knowledge in the wrong module.
 *
 * <p>Absent from the pack JSON it is {@code false}, which is every pack shipped before Spec 5a.
 * {@link RulePackLoader} parses it EXPLICITLY: unknown keys in a definition are silently ignored,
 * so a flag no one reads is indistinguishable from a flag no one set.
 */
public record Anchor(String id, AnchorKind kind, String pattern, double weight, boolean startsDocument) {}
