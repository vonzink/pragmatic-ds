package com.pragmaticds.docengine.ingestion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for the ingestion → orchestration seam: records every {@code startJob} call and
 * returns a fresh job id, so ITs can assert both that the seam was crossed and that the returned
 * job id surfaces in the response.
 */
public class RecordingProcessingStarter implements ProcessingStarter {

    public record Call(UUID packageId, String idempotencyKey, UUID jobId) {}

    private final List<Call> calls = new CopyOnWriteArrayList<>();

    @Override
    public UUID startJob(UUID packageId, String idempotencyKey) {
        UUID jobId = UUID.randomUUID();
        calls.add(new Call(packageId, idempotencyKey, jobId));
        return jobId;
    }

    /**
     * Never a replay: the ingestion ITs exercise fresh uploads, and the replay path has its own
     * seam-level IT against the real wiring (IdempotentReplaySeamIT).
     */
    @Override
    public Optional<ExistingJob> findExisting(String idempotencyKey) {
        return Optional.empty();
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public Call onlyCall() {
        if (calls.size() != 1) {
            throw new AssertionError("expected exactly one startJob call, got " + calls.size());
        }
        return calls.get(0);
    }

    public void reset() {
        calls.clear();
    }
}
