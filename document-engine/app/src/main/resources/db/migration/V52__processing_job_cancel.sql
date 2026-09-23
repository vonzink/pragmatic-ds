-- A cancel is a request the runner honours at its next checkpoint (before a worker stage, between
-- OCR pages), never an interrupt: the in-flight worker call finishes, then the job fails JOB_CANCELLED.
alter table processing_job add column cancel_requested_at timestamptz;
