package com.pragmaticds.rag.lab.run;

import com.pragmaticds.rag.lab.release.InstanceReleaseManifest;
import com.pragmaticds.rag.lab.run.RunGroupCommand.RunMemberCommand;
import com.pragmaticds.rag.lab.run.RunGroupRequestCodec.MemberBasis;
import com.pragmaticds.rag.lab.run.domain.LabRunGroup;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Two digests answering two different questions.
 *
 * <p>The request digest answers "is this the same submission?" and is what an idempotency key
 * binds to. The comparison basis answers "did these members really differ only in the declared
 * dimension?" and is stored so the claim outlives the runs.
 */
class RunGroupRequestCodecTest {

    private static final UUID BRAIN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RELEASE_A = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RELEASE_B = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REGISTRATION = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID SNAPSHOT = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Test
    void theSameSubmissionHashesTheSameAndAnyChangedIdDoesNot() {
        String baseline = RunGroupRequestCodec.requestSha256(independent(RELEASE_A));

        assertEquals(baseline, RunGroupRequestCodec.requestSha256(independent(RELEASE_A)));
        assertNotEquals(baseline, RunGroupRequestCodec.requestSha256(independent(RELEASE_B)));
    }

    @Test
    void memberOrderIsPartOfTheSubmissionBecauseResultsAreReadInThatOrder() {
        RunGroupCommand forwards = new RunGroupCommand(BRAIN, LabRunGroup.Mode.COMPARISON,
                LabRunGroup.ComparisonDimension.RELEASE,
                List.of(member(RELEASE_A), member(RELEASE_B)));
        RunGroupCommand backwards = new RunGroupCommand(BRAIN, LabRunGroup.Mode.COMPARISON,
                LabRunGroup.ComparisonDimension.RELEASE,
                List.of(member(RELEASE_B), member(RELEASE_A)));

        // Not sorted: a key bound to one presentation must not replay the other.
        assertNotEquals(RunGroupRequestCodec.requestSha256(forwards),
                RunGroupRequestCodec.requestSha256(backwards));
    }

    @Test
    void twoMembersCannotBeReshapedIntoOneThatHashesTheSame() {
        // Length prefixes are what stop concatenation ambiguity: without them, adjacent fields
        // could be re-split into a different member list with identical bytes.
        RunGroupCommand one = new RunGroupCommand(BRAIN, LabRunGroup.Mode.INDEPENDENT, null,
                List.of(new RunMemberCommand("ab", RELEASE_A, REGISTRATION, SNAPSHOT)));
        RunGroupCommand other = new RunGroupCommand(BRAIN, LabRunGroup.Mode.INDEPENDENT, null,
                List.of(new RunMemberCommand("a", RELEASE_A, REGISTRATION, SNAPSHOT)));

        assertNotEquals(RunGroupRequestCodec.requestSha256(one),
                RunGroupRequestCodec.requestSha256(other));
    }

    @Test
    void aModelComparisonHoldsEverythingButTheModelEqual() {
        MemberBasis opus = basis("income", SNAPSHOT.toString(), "anthropic", "claude-opus-5");
        MemberBasis sonnet = basis("income", SNAPSHOT.toString(), "anthropic", "claude-sonnet-5");

        // Same basis: the two differ only in the model, which is the point of the comparison.
        assertEquals(
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.MODEL, List.of(opus)),
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.MODEL, List.of(sonnet)));

        // A different corpus snapshot breaks it: you could no longer say the difference came
        // from the model.
        MemberBasis elsewhere = basis("income", UUID.randomUUID().toString(),
                "anthropic", "claude-sonnet-5");
        assertNotEquals(
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.MODEL, List.of(opus)),
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.MODEL, List.of(elsewhere)));
    }

    @Test
    void aReleaseComparisonPermitsBehaviourAndCorpusToDifferWithinOneInstance() {
        MemberBasis first = basis("income", SNAPSHOT.toString(), "anthropic", "claude-opus-5");
        MemberBasis second = basis("income", UUID.randomUUID().toString(),
                "openai", "gpt-decimal");

        // Comparing two releases of one instance IS comparing those choices, so they are free.
        assertEquals(
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.RELEASE, List.of(first)),
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.RELEASE, List.of(second)));

        // The instance is not free: two releases of different instances is a different question.
        MemberBasis otherInstance = basis("assets", SNAPSHOT.toString(),
                "anthropic", "claude-opus-5");
        assertNotEquals(
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.RELEASE, List.of(first)),
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.RELEASE, List.of(otherInstance)));
    }

    @Test
    void anInstanceComparisonHoldsOnlyTheDocumentInCommon() {
        MemberBasis income = basis("income", SNAPSHOT.toString(), "anthropic", "claude-opus-5");
        MemberBasis assets = basis("assets", UUID.randomUUID().toString(), "openai", "gpt-decimal");

        // Two instances are different products; only the parse they were both given is shared.
        assertEquals(
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.INSTANCE, List.of(income)),
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.INSTANCE, List.of(assets)));
    }

    @Test
    void theParsedInputIsCommonGroundUnderEveryDimension() {
        MemberBasis pinned = basis("income", SNAPSHOT.toString(), "anthropic", "claude-opus-5");
        MemberBasis otherParse = new MemberBasis("income", UUID.randomUUID().toString(), 2,
                List.of(), SNAPSHOT.toString(), pinned.behaviorSha256(), pinned.toolsSha256(),
                pinned.outputSha256(), pinned.modelSha256());

        for (LabRunGroup.ComparisonDimension dimension : LabRunGroup.ComparisonDimension.values()) {
            assertNotEquals(
                    RunGroupRequestCodec.comparisonBasisSha256(dimension, List.of(pinned)),
                    RunGroupRequestCodec.comparisonBasisSha256(dimension, List.of(otherParse)),
                    dimension + " must still require the same parsed input");
        }
    }

    @Test
    void aBasisComputedUnderOneRuleIsNeverTheSameBasisUnderAnother() {
        MemberBasis member = basis("income", SNAPSHOT.toString(), "anthropic", "claude-opus-5");

        assertNotEquals(
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.MODEL, List.of(member)),
                RunGroupRequestCodec.comparisonBasisSha256(
                        LabRunGroup.ComparisonDimension.RELEASE, List.of(member)));
    }

    @Test
    void toolDeclarationOrderIsPartOfWhatARunDoes() {
        InstanceReleaseManifest.ToolContract first = new InstanceReleaseManifest.ToolContract(
                "income.calculate", "1", "a".repeat(64), "b".repeat(64));
        InstanceReleaseManifest.ToolContract second = new InstanceReleaseManifest.ToolContract(
                "assets.total", "1", "c".repeat(64), "d".repeat(64));

        // A release runs its tools in the order it lists them, so two releases listing the same
        // tools differently are not running the same thing.
        assertNotEquals(
                RunGroupRequestCodec.basisOf("income", "pkg", 1, List.of(), "snap",
                        manifest(List.of(first, second))).toolsSha256(),
                RunGroupRequestCodec.basisOf("income", "pkg", 1, List.of(), "snap",
                        manifest(List.of(second, first))).toolsSha256());
    }

    // ================================================================ fixtures

    private static RunGroupCommand independent(UUID releaseId) {
        return new RunGroupCommand(BRAIN, LabRunGroup.Mode.INDEPENDENT, null,
                List.of(member(releaseId)));
    }

    private static RunMemberCommand member(UUID releaseId) {
        return new RunMemberCommand("income", releaseId, REGISTRATION, SNAPSHOT);
    }

    private static MemberBasis basis(String slug, String snapshot, String provider, String model) {
        return RunGroupRequestCodec.basisOf(slug, "package-1", 3, List.of("source-1"), snapshot,
                manifestWithModel(provider, model));
    }

    private static InstanceReleaseManifest manifest(
            List<InstanceReleaseManifest.ToolContract> tools) {
        return new InstanceReleaseManifest(2,
                new InstanceReleaseManifest.ParsedDataContract("1.0.0", "DOCENGINE-C14N-1",
                        Set.of("PAYSTUB"), Set.of("PAYSTUB"), 1,
                        InstanceReleaseManifest.ReviewPolicy.WARN,
                        InstanceReleaseManifest.MissingFieldPolicy.PRESERVE),
                new InstanceReleaseManifest.ModelContract("anthropic", "claude-opus-5",
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                new InstanceReleaseManifest.CorpusContract(List.of()),
                new InstanceReleaseManifest.BehaviorContract("system", "task", "query",
                        new BigDecimal("0.250")),
                tools,
                new InstanceReleaseManifest.OutputContract("output", "c".repeat(64)),
                new InstanceReleaseManifest.LimitContract(100, 20, 20, 20, 1,
                        new BigDecimal("1.50")),
                new InstanceReleaseManifest.EvaluationContract("income-smoke", 1,
                        new BigDecimal("0.9500")));
    }

    private static InstanceReleaseManifest manifestWithModel(String provider, String model) {
        InstanceReleaseManifest base = manifest(List.of());
        return new InstanceReleaseManifest(2, base.parsedData(),
                new InstanceReleaseManifest.ModelContract(provider, model,
                        InstanceReleaseManifest.FallbackPolicy.NONE),
                base.corpus(), base.behavior(), base.tools(), base.output(), base.limits(),
                base.evaluations());
    }
}
