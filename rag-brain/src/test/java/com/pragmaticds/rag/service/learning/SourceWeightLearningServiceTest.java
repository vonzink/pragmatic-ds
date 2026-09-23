package com.pragmaticds.rag.service.learning;

import com.pragmaticds.rag.config.ClusterJobLock;
import com.pragmaticds.rag.config.LearningProperties;
import com.pragmaticds.rag.domain.Brain;
import com.pragmaticds.rag.domain.BrainSourceWeightEvent;
import com.pragmaticds.rag.domain.FeedbackRating;
import com.pragmaticds.rag.domain.FeedbackSource;
import com.pragmaticds.rag.domain.RagAnswerFeedback;
import com.pragmaticds.rag.domain.WeightEventStatus;
import com.pragmaticds.rag.repository.BrainRepository;
import com.pragmaticds.rag.repository.BrainSourceWeightEventRepository;
import com.pragmaticds.rag.repository.RagAnswerFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceWeightLearningServiceTest {

    RagAnswerFeedbackRepository feedbackRepo = mock(RagAnswerFeedbackRepository.class);
    BrainSourceWeightEventRepository eventRepo = mock(BrainSourceWeightEventRepository.class);
    BrainRepository brainRepo = mock(BrainRepository.class);
    SourceWeightService weightService = mock(SourceWeightService.class);

    // Injected resolvers (functional interfaces defined on the service).
    SourceWeightLearningService.TraceDocumentResolver traceDocs =
            mock(SourceWeightLearningService.TraceDocumentResolver.class);
    SourceWeightLearningService.DocumentTrustResolver trust =
            mock(SourceWeightLearningService.DocumentTrustResolver.class);

    LearningProperties props = new LearningProperties(0.8, 1.2, 0.05, 5, 3, 0.10, 0.98);

    // The cluster lock is transparent here: stub it to always run the job body inline.
    ClusterJobLock clusterJobLock = mock(ClusterJobLock.class);

    SourceWeightLearningService service = new SourceWeightLearningService(
            feedbackRepo, eventRepo, brainRepo, weightService, traceDocs, trust, props,
            clusterJobLock, /* globallyDisabled */ false);

    @BeforeEach
    void runJobUnderLockInline() {
        when(clusterJobLock.runIfLeader(anyLong(), anyString(), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return true;
        });
    }

    private final UUID brainId = UUID.randomUUID();
    private final UUID docA = UUID.randomUUID();

    private static RagAnswerFeedback fb(UUID brain, UUID trace, FeedbackRating rating, FeedbackSource source) {
        return new RagAnswerFeedback(trace, brain, rating.name(), source.name(), null, "session", "user");
    }

    @Test
    void skipsBrainThatIsNotLearningEnabled() {
        Brain brain = mock(Brain.class);
        when(brain.isLearningEnabled()).thenReturn(false);
        when(brainRepo.findAll()).thenReturn(List.of(brain));

        service.runOnce();

        verify(feedbackRepo, never()).findByBrainIdAndProcessedAtIsNullAndCreatedAtAfter(any(), any());
        verify(weightService, never()).upsertWeight(any(), any(), anyDouble(), anyInt(), anyString());
    }

    @Test
    void skipsEveryBrainWhenGloballyDisabled() {
        SourceWeightLearningService disabled = new SourceWeightLearningService(
                feedbackRepo, eventRepo, brainRepo, weightService, traceDocs, trust, props,
                clusterJobLock, true);
        Brain brain = mock(Brain.class);
        when(brain.isLearningEnabled()).thenReturn(true);
        when(brainRepo.findAll()).thenReturn(List.of(brain));

        disabled.runOnce();

        verify(feedbackRepo, never()).findByBrainIdAndProcessedAtIsNullAndCreatedAtAfter(any(), any());
    }

    @Test
    void belowMinEvidenceMakesNoChange() {
        UUID trace = UUID.randomUUID();
        // 4 end-user upvotes for docA < minEvidence(5) -> no change.
        List<RagAnswerFeedback> rows = List.of(
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER));
        stubBrain(true);
        when(feedbackRepo.findByBrainIdAndProcessedAtIsNullAndCreatedAtAfter(eq(brainId), any())).thenReturn(rows);
        when(traceDocs.documentIdsByTrace(any())).thenReturn(Map.of(trace, List.of(docA)));

        service.runOnce();

        verify(weightService, never()).upsertWeight(any(), any(), anyDouble(), anyInt(), anyString());
        verify(eventRepo, never()).save(any());
    }

    @Test
    void appliesWeightWhenEvidenceMetAndMoveWithinReviewThreshold() {
        UUID trace = UUID.randomUUID();
        // 5 end-user upvotes -> evidence 5 >= min; capped +0.05 -> 1.05,
        // proposedDelta 0.05 <= reviewThreshold(0.10) -> APPLIED.
        List<RagAnswerFeedback> rows = List.of(
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER));
        stubBrain(true);
        when(feedbackRepo.findByBrainIdAndProcessedAtIsNullAndCreatedAtAfter(eq(brainId), any())).thenReturn(rows);
        when(traceDocs.documentIdsByTrace(any())).thenReturn(Map.of(trace, List.of(docA)));
        when(weightService.weightFor(brainId, docA)).thenReturn(1.0);
        when(trust.isTopAuthority(docA)).thenReturn(false);

        service.runOnce();

        verify(weightService).upsertWeight(eq(brainId), eq(docA), eq(1.05), eq(5), anyString());
        var ev = forClass(BrainSourceWeightEvent.class);
        verify(eventRepo).save(ev.capture());
        assertEquals(WeightEventStatus.APPLIED.name(), ev.getValue().getStatus());
        // Consumed rows are marked processed in the same pass so they cannot be re-tallied.
        verify(feedbackRepo).saveAll(rows);
        rows.forEach(r -> org.junit.jupiter.api.Assertions.assertNotNull(r.getProcessedAt()));
    }

    @Test
    void routesToPendingWhenProposedMoveExceedsReviewThreshold() {
        UUID trace = UUID.randomUUID();
        // Current already high (1.16); +0.05 -> 1.21 clamped to 1.20;
        // proposedDelta |1.20 - 1.0| = 0.20 > reviewThreshold(0.10) -> PENDING.
        List<RagAnswerFeedback> rows = List.of(
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER));
        stubBrain(true);
        when(feedbackRepo.findByBrainIdAndProcessedAtIsNullAndCreatedAtAfter(eq(brainId), any())).thenReturn(rows);
        when(traceDocs.documentIdsByTrace(any())).thenReturn(Map.of(trace, List.of(docA)));
        when(weightService.weightFor(brainId, docA)).thenReturn(1.16);
        when(trust.isTopAuthority(docA)).thenReturn(false);

        service.runOnce();

        verify(weightService, never()).upsertWeight(any(), any(), anyDouble(), anyInt(), anyString());
        var ev = forClass(BrainSourceWeightEvent.class);
        verify(eventRepo).save(ev.capture());
        assertEquals(WeightEventStatus.PENDING.name(), ev.getValue().getStatus());
        assertEquals(1.20, ev.getValue().getProposedWeight(), 1e-9);
    }

    @Test
    void routesToPendingWhenDownvoteWouldLowerATopAuthoritySource() {
        UUID trace = UUID.randomUUID();
        // 5 downvotes -> proposed 0.95 (delta 0.05 <= threshold) but the doc is
        // top-authority and the move is a DECREASE -> PENDING, not APPLIED.
        List<RagAnswerFeedback> rows = List.of(
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER));
        stubBrain(true);
        when(feedbackRepo.findByBrainIdAndProcessedAtIsNullAndCreatedAtAfter(eq(brainId), any())).thenReturn(rows);
        when(traceDocs.documentIdsByTrace(any())).thenReturn(Map.of(trace, List.of(docA)));
        when(weightService.weightFor(brainId, docA)).thenReturn(1.0);
        when(trust.isTopAuthority(docA)).thenReturn(true);

        service.runOnce();

        verify(weightService, never()).upsertWeight(any(), any(), anyDouble(), anyInt(), anyString());
        var ev = forClass(BrainSourceWeightEvent.class);
        verify(eventRepo).save(ev.capture());
        assertEquals(WeightEventStatus.PENDING.name(), ev.getValue().getStatus());
    }

    @Test
    void adminVoteCountsAdminVoteWeightTimes() {
        UUID trace = UUID.randomUUID();
        // 2 admin upvotes * adminVoteWeight(3) = 6 weighted votes >= minEvidence(5) -> APPLIED.
        List<RagAnswerFeedback> rows = List.of(
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.ADMIN),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.ADMIN));
        stubBrain(true);
        when(feedbackRepo.findByBrainIdAndProcessedAtIsNullAndCreatedAtAfter(eq(brainId), any())).thenReturn(rows);
        when(traceDocs.documentIdsByTrace(any())).thenReturn(Map.of(trace, List.of(docA)));
        when(weightService.weightFor(brainId, docA)).thenReturn(1.0);
        when(trust.isTopAuthority(docA)).thenReturn(false);

        service.runOnce();

        // evidence = 6 (2 admin * 3)
        verify(weightService).upsertWeight(eq(brainId), eq(docA), eq(1.05), eq(6), anyString());
    }

    @Test
    void topAuthorityDecayOnlyDecreaseWithNetZeroVotesAutoApplies() {
        UUID trace = UUID.randomUUID();
        // current=1.05 (above neutral, top-authority), 3 up + 3 down (net-zero,
        // up>=down, no genuine downvote signal) -> decay alone nudges 1.05 toward
        // neutral: proposed=1.049, which IS < current, but that motion is pure
        // decay-toward-neutral, not a real downvote. Must auto-apply, not PENDING.
        // (Also within reviewThreshold, so this isolates the top-authority gate,
        // not the beyondThreshold gate, as the thing under test.)
        List<RagAnswerFeedback> rows = List.of(
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER));
        stubBrain(true);
        when(feedbackRepo.findByBrainIdAndProcessedAtIsNullAndCreatedAtAfter(eq(brainId), any())).thenReturn(rows);
        when(traceDocs.documentIdsByTrace(any())).thenReturn(Map.of(trace, List.of(docA)));
        when(weightService.weightFor(brainId, docA)).thenReturn(1.05);
        when(trust.isTopAuthority(docA)).thenReturn(true);

        service.runOnce();

        verify(weightService).upsertWeight(eq(brainId), eq(docA), eq(1.049), eq(6), anyString());
        var ev = forClass(BrainSourceWeightEvent.class);
        verify(eventRepo).save(ev.capture());
        assertEquals(WeightEventStatus.APPLIED.name(), ev.getValue().getStatus());
    }

    @Test
    void topAuthorityWithGenuineNetDownvotesRoutesToPending() {
        UUID trace = UUID.randomUUID();
        // current=1.05 (above neutral, top-authority), 1 up + 4 down (genuine
        // net-negative signal, down>up) -> proposed=1.019, a real downvote-driven
        // decrease on a top-authority source -> PENDING, even though it is within
        // the review threshold (isolating the top-authority gate from beyondThreshold).
        List<RagAnswerFeedback> rows = List.of(
                fb(brainId, trace, FeedbackRating.UP, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER),
                fb(brainId, trace, FeedbackRating.DOWN, FeedbackSource.END_USER));
        stubBrain(true);
        when(feedbackRepo.findByBrainIdAndProcessedAtIsNullAndCreatedAtAfter(eq(brainId), any())).thenReturn(rows);
        when(traceDocs.documentIdsByTrace(any())).thenReturn(Map.of(trace, List.of(docA)));
        when(weightService.weightFor(brainId, docA)).thenReturn(1.05);
        when(trust.isTopAuthority(docA)).thenReturn(true);

        service.runOnce();

        verify(weightService, never()).upsertWeight(any(), any(), anyDouble(), anyInt(), anyString());
        var ev = forClass(BrainSourceWeightEvent.class);
        verify(eventRepo).save(ev.capture());
        assertEquals(WeightEventStatus.PENDING.name(), ev.getValue().getStatus());
    }

    private void stubBrain(boolean learning) {
        Brain brain = mock(Brain.class);
        when(brain.getId()).thenReturn(brainId);
        when(brain.isLearningEnabled()).thenReturn(learning);
        when(brainRepo.findAll()).thenReturn(List.of(brain));
    }
}
