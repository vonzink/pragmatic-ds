/**
 * Processing status, stage by stage — acceptance criterion 4.
 *
 * "Stage errors are visible to the user with their error code" is a low bar
 * that most pipelines still fail, because a spinner that never resolves is
 * cheaper to build than an honest failure. So the FAILED row here is the
 * loudest thing on screen, and it names the `errorCode` verbatim: that string
 * is the only detail the server will ever give (`GlobalExceptionHandler` never
 * serialises an exception message, since one may quote document content), and
 * it is the string a reviewer will paste into a support ticket.
 *
 * SKIPPED stages show their `skipReason` for the same reason: "OCR was skipped
 * because the page had a native text layer" is information, and a silent gap
 * in the trail is not.
 */

import { useState } from 'react';

import type { JobResponse, StageResponse } from '../../lib/api/types.ts';

export type JobStatusProps = {
  job: JobResponse | null;
  /** True while the poller is between requests. */
  polling?: boolean;
  /** Offered only when the job is resumable. */
  onResume?: () => void;
};

const STATUS_STYLES: Record<string, string> = {
  SUCCEEDED: 'bg-emerald-100 text-emerald-900',
  COMPLETED: 'bg-emerald-100 text-emerald-900',
  RUNNING: 'bg-sky-100 text-sky-900',
  PENDING: 'bg-slate-100 text-slate-500',
  SKIPPED: 'bg-slate-100 text-slate-500',
  FAILED: 'bg-red-100 text-red-900',
  HUMAN_REVIEW_REQUIRED: 'bg-amber-100 text-amber-900',
};

function StageRow({ stage }: { stage: StageResponse }) {
  const failed = stage.status === 'FAILED';
  return (
    <li
      data-testid="stage-row"
      data-stage={stage.stage}
      data-status={stage.status}
      className={`flex flex-wrap items-baseline gap-x-2 gap-y-1 px-3 py-1.5 ${
        failed ? 'bg-red-50' : ''
      }`}
    >
      <span className="font-mono text-[11px] text-slate-700">{stage.stage}</span>
      <span
        className={`rounded-sm px-1.5 py-0.5 text-[10px] font-medium ${
          STATUS_STYLES[stage.status] ?? 'bg-slate-100 text-slate-600'
        }`}
      >
        {stage.status.toLowerCase()}
      </span>
      {stage.attempt > 1 ? (
        <span className="text-[10px] text-slate-500">attempt {String(stage.attempt)}</span>
      ) : null}
      {stage.durationMs !== null ? (
        <span className="text-[10px] tabular-nums text-slate-400">
          {String(stage.durationMs)} ms
        </span>
      ) : null}

      {stage.errorCode ? (
        <span
          data-testid="stage-error-code"
          data-error-code={stage.errorCode}
          className="w-full font-mono text-[11px] font-semibold text-red-800"
        >
          {stage.errorCode}
        </span>
      ) : null}

      {stage.skipReason ? (
        <span data-testid="stage-skip-reason" className="w-full text-[11px] text-slate-500">
          skipped: {stage.skipReason}
        </span>
      ) : null}
    </li>
  );
}

export default function JobStatus({ job, polling = false, onResume }: JobStatusProps) {
  // Before the early return: a hook after a conditional return is a rules-of-hooks violation.
  const [trailOpen, setTrailOpen] = useState<boolean | null>(null);

  if (!job) return null;

  const failedStages = job.stages.filter((stage) => stage.status === 'FAILED');
  const terminal = job.status === 'COMPLETED' || job.status === 'FAILED';
  // HUMAN_REVIEW_REQUIRED is the pipeline's normal end state — work is done and a person is
  // expected. `terminal` above is about the JOB being over; this is about the WORK being over.
  const settled =
    terminal || job.status === 'HUMAN_REVIEW_REQUIRED';
  const showTrail = trailOpen ?? !(settled && failedStages.length === 0);

  return (
    <section
      data-testid="job-status"
      data-job-status={job.status}
      aria-label="Processing status"
      className="rounded-md border border-slate-200 bg-white"
    >
      <header className="flex flex-wrap items-baseline gap-2 border-b border-slate-200 px-3 py-2">
        <h3 className="text-xs font-semibold tracking-wide text-slate-600 uppercase">Processing</h3>
        <span
          className={`rounded-sm px-1.5 py-0.5 text-[11px] font-medium ${
            STATUS_STYLES[job.status] ?? 'bg-slate-100 text-slate-600'
          }`}
        >
          {job.status.toLowerCase().replace(/_/g, ' ')}
        </span>
        {job.currentStage && !terminal ? (
          <span className="text-[11px] text-slate-500">at {job.currentStage}</span>
        ) : null}
        {polling && !terminal ? (
          <span role="status" className="text-[11px] text-slate-400">
            refreshing…
          </span>
        ) : null}
        {onResume && failedStages.length > 0 ? (
          <button
            type="button"
            data-testid="resume-job"
            onClick={onResume}
            className="ml-auto rounded-sm border border-slate-300 px-2 py-0.5 text-[11px] text-slate-700 hover:border-slate-400"
          >
            Retry from the failed stage
          </button>
        ) : null}
      </header>

      {failedStages.length > 0 ? (
        <p
          data-testid="job-failure-summary"
          role="alert"
          className="border-b border-red-200 bg-red-50 px-3 py-2 text-xs text-red-900"
        >
          Processing stopped at{' '}
          {failedStages.map((stage) => `${stage.stage} (${stage.errorCode ?? 'no code'})`).join(', ')}
          . Anything downstream of it did not run, so the data below may be incomplete.
        </p>
      ) : null}

      {/* Collapsed once the job settles WITHOUT a failure. The trail is a progress indicator:
          while work is running it is the most interesting thing on screen, and the moment it
          finishes it is thirteen rows of history sitting on top of the extracted data the
          reviewer actually came for. A failure keeps it open — that is when the detail earns
          its space. */}
      {showTrail ? (
        <ol className="divide-y divide-slate-100">
          {job.stages.map((stage) => (
            <StageRow key={`${stage.stage}-${String(stage.attempt)}`} stage={stage} />
          ))}
        </ol>
      ) : (
        <button
          type="button"
          data-testid="job-trail-toggle"
          onClick={() => { setTrailOpen(true); }}
          className="w-full px-3 py-1.5 text-left text-[11px] text-slate-500 hover:bg-slate-50"
        >
          {job.stages.length} stages — show detail
        </button>
      )}
      {showTrail && settled ? (
        <button
          type="button"
          data-testid="job-trail-toggle"
          onClick={() => { setTrailOpen(false); }}
          className="w-full border-t border-slate-100 px-3 py-1.5 text-left text-[11px] text-slate-500 hover:bg-slate-50"
        >
          Hide detail
        </button>
      ) : null}
    </section>
  );
}
