package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.run.RunGroupCommand.RunMemberCommand;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Canonicalizes a run-group submission, and the basis a comparison claims to hold equal.
 *
 * <p><b>Two different digests, for two different questions.</b> The request digest answers "is this
 * the same submission?" and is what an idempotency key is bound to. The comparison basis digest
 * answers "did these members really differ only in the declared dimension?" and is stored on the
 * group, so the claim stays checkable long after the runs finish.
 *
 * <p>Both are length-prefixed. Without prefixes, {@code ["ab","c"]} and {@code ["a","bc"]} would
 * concatenate identically, and a caller could reshape a member list into a different one that
 * hashed the same.
 */
public final class RunGroupRequestCodec {

    private RunGroupRequestCodec() {}

    /**
     * The submission, in member order.
     *
     * <p>Member order is preserved rather than sorted, because a comparison's member order is what
     * the caller will read its results in. Two submissions that list the same members in different
     * orders are different submissions, and a key bound to one must not replay the other.
     */
    public static String requestSha256(RunGroupCommand command) {
        Objects.requireNonNull(command, "command");
        List<String> fields = new ArrayList<>();
        fields.add(command.brainId().toString());
        fields.add(command.mode().name());
        fields.add(command.comparisonDimension() == null
                ? "" : command.comparisonDimension().name());
        fields.add(Integer.toString(command.members().size()));
        for (RunMemberCommand member : command.members()) {
            fields.add(member.instanceSlug());
            fields.add(member.releaseId().toString());
            fields.add(member.registrationId().toString());
            fields.add(member.corpusSnapshotId() == null
                    ? "" : member.corpusSnapshotId().toString());
        }
        return digest(fields);
    }

    /**
     * Everything a comparison holds equal, hashed so the claim can be checked later.
     *
     * <p>What counts as "held equal" depends on what is being varied, and each rule is the widest
     * one that still makes the comparison mean something:
     *
     * <ul>
     *   <li>{@code MODEL} — everything except the model. Same parsed input, same corpus, same
     *       prompts, same tools, same output schema. Anything else varying would leave you unable
     *       to say the difference came from the model.
     *   <li>{@code RELEASE} — the parsed input and the instance. Behavior, tools, corpus, and
     *       model are all free to differ, because comparing two releases of one instance is
     *       precisely comparing those choices.
     *   <li>{@code INSTANCE} — the parsed input alone. Two instances are different products; only
     *       the document they were given is common ground.
     * </ul>
     *
     * <p>The declared dimension is hashed too, so a basis computed under one rule can never be
     * mistaken for the same basis under another.
     */
    public static String comparisonBasisSha256(
            LabRunGroup.ComparisonDimension dimension, List<MemberBasis> members) {
        Objects.requireNonNull(dimension, "dimension");
        List<String> fields = new ArrayList<>();
        fields.add(dimension.name());
        // One member's view of the shared basis. Every member's must be identical, which the
        // preflight checks by comparing digests rather than by comparing fields pairwise.
        MemberBasis first = members.getFirst();
        fields.add(first.packageId());
        fields.add(Integer.toString(first.revision()));
        fields.addAll(first.selectedSourceIds());
        if (dimension != LabRunGroup.ComparisonDimension.INSTANCE) {
            fields.add(first.instanceSlug());
        }
        if (dimension == LabRunGroup.ComparisonDimension.MODEL) {
            fields.add(first.corpusSnapshotId());
            fields.add(first.behaviorSha256());
            fields.add(first.toolsSha256());
            fields.add(first.outputSha256());
        }
        return digest(fields);
    }

    /**
     * The comparable facts about one resolved member.
     *
     * <p>Digests rather than the contracts themselves, so that comparing two members is a string
     * comparison and no prompt text is ever carried into a comparison decision.
     */
    public record MemberBasis(
            String instanceSlug,
            String packageId,
            int revision,
            List<String> selectedSourceIds,
            String corpusSnapshotId,
            String behaviorSha256,
            String toolsSha256,
            String outputSha256,
            String modelSha256) {

        public MemberBasis {
            selectedSourceIds = List.copyOf(
                    Objects.requireNonNull(selectedSourceIds, "selectedSourceIds"));
        }
    }

    /** Digests the parts of a manifest a comparison may need to hold equal. */
    public static MemberBasis basisOf(String instanceSlug, String packageId, int revision,
                                      List<String> selectedSourceIds, String corpusSnapshotId,
                                      InstanceReleaseManifest manifest) {
        InstanceReleaseManifest.BehaviorContract behavior = manifest.behavior();
        List<String> behaviorFields = List.of(
                nullSafe(behavior.systemPrompt()), nullSafe(behavior.taskPrompt()),
                nullSafe(behavior.retrievalQuery()),
                behavior.temperature() == null ? "" : behavior.temperature().toPlainString());

        List<String> toolFields = new ArrayList<>();
        // Declaration order, not sorted: a release's tools run in the order it lists them, so two
        // releases listing the same tools differently are not running the same thing.
        for (InstanceReleaseManifest.ToolContract tool : manifest.tools()) {
            toolFields.add(nullSafe(tool.name()));
            toolFields.add(nullSafe(tool.version()));
            toolFields.add(nullSafe(tool.inputSchemaSha256()));
            toolFields.add(nullSafe(tool.outputSchemaSha256()));
        }

        return new MemberBasis(instanceSlug, packageId, revision, selectedSourceIds,
                nullSafe(corpusSnapshotId),
                digest(behaviorFields),
                digest(toolFields),
                digest(List.of(nullSafe(manifest.output().schemaId()),
                        nullSafe(manifest.output().schemaSha256()))),
                digest(List.of(nullSafe(manifest.model().provider()),
                        nullSafe(manifest.model().model()),
                        manifest.model().fallbackPolicy() == null
                                ? "" : manifest.model().fallbackPolicy().name())));
    }

    private static String digest(List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = nullSafe(field).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
