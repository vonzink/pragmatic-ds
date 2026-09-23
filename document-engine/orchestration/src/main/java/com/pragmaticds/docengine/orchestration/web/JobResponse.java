package com.pragmaticds.docengine.orchestration.web;

import com.pragmaticds.docengine.orchestration.JobService;
import com.pragmaticds.docengine.orchestration.domain.ProcessingJob;
import com.pragmaticds.docengine.orchestration.domain.ProcessingStage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The wire shape of a job: status plus the full stage-attempt trail. Enum values ride as their
 * stable names; error information is only ever the taxonomy code — detail json stays server-side.
 */
public record JobResponse(
        UUID id,
        UUID packageId,
        String status,
        String currentStage,
        int attempt,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        List<StageResponse> stages) {

    public record StageResponse(
            String stage,
            String status,
            int attempt,
            String skipReason,
            String errorCode,
            Long durationMs) {

        static StageResponse from(ProcessingStage stage) {
            return new StageResponse(
                    stage.getStage().name(),
                    stage.getStatus().name(),
                    stage.getAttempt(),
                    stage.getSkipReason(),
                    stage.getErrorCode() == null ? null : stage.getErrorCode().name(),
                    stage.getDurationMs());
        }
    }

    public static JobResponse from(JobService.JobDetails details) {
        ProcessingJob job = details.job();
        return new JobResponse(
                job.getId(),
                job.getPackageId(),
                job.getStatus().name(),
                job.getCurrentStage() == null ? null : job.getCurrentStage().name(),
                job.getAttempt(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                details.stages().stream().map(StageResponse::from).toList());
    }
}
