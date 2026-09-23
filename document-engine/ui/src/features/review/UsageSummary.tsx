/**
 * What this package cost to parse, in one line at the top of the review view.
 *
 * The product owner asked for cost per document. The honest answer is that
 * parsing makes ZERO paid model calls — every rung of the extraction ladder is
 * deterministic — so the cost is COMPUTE, and this bar shows compute: pages,
 * the OCR share of them (the only variable that really moves the number),
 * elapsed, and retries. Beside them sits `$0.00 · no model calls`, which is a
 * measured zero and not a placeholder: the server counts rows in the provider
 * ledger and finds none.
 *
 * Two things it deliberately does NOT do:
 *
 * - It does not present elapsed as per-document. Stages run over the whole
 *   package (rendering, OCR and parsing all finish before any logical document
 *   exists), so the label says "package" and the payload says
 *   `perDocumentElapsedAvailable: false`. Dividing by document count would make
 *   a one-page W-2 look as costly as a 26-page scan.
 * - It does not turn compute into dollars. Nobody has agreed a rate, and a
 *   made-up one would be indistinguishable on screen from a measured one.
 *
 * Per-DOCUMENT page and OCR counts ARE real (a page belongs to at most one
 * document) and appear in the expanded detail.
 */

import { useState } from 'react';

import { formatElapsed, formatUsd, ocrShare } from './usage.ts';
import type { PackageUsageView, StageUsageView } from '../../lib/api/types.ts';

export type UsageSummaryProps = {
  usage: PackageUsageView | null;
};

const STATUS_STYLES: Record<string, string> = {
  SUCCEEDED: 'bg-emerald-100 text-emerald-900',
  RUNNING: 'bg-sky-100 text-sky-900',
  PENDING: 'bg-slate-100 text-slate-500',
  SKIPPED: 'bg-slate-100 text-slate-500',
  FAILED: 'bg-red-100 text-red-900',
};

/** One headline number with its label. `emphasis` marks the one that costs money. */
function Stat({
  name,
  label,
  children,
  emphasis = false,
}: {
  name: string;
  label: string;
  children: React.ReactNode;
  emphasis?: boolean;
}) {
  return (
    <span
      data-testid="usage-stat"
      data-stat={name}
      className="flex items-baseline gap-1.5 whitespace-nowrap"
    >
      <span className="text-[10px] tracking-wide text-slate-500 uppercase">{label}</span>
      <span
        className={`text-xs tabular-nums ${
          emphasis ? 'font-semibold text-amber-900' : 'font-medium text-slate-800'
        }`}
      >
        {children}
      </span>
    </span>
  );
}

function StageRow({ stage }: { stage: StageUsageView }) {
  const failed = stage.status === 'FAILED';
  return (
    <li
      data-testid="usage-stage-row"
      data-stage={stage.stage}
      data-status={stage.status}
      data-attempt={String(stage.attempt)}
      className={`flex flex-wrap items-baseline gap-x-2 gap-y-1 px-3 py-1 ${
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
      <span className="ml-auto text-[11px] tabular-nums text-slate-500">
        {formatElapsed(stage.durationMs)}
      </span>
      {stage.errorCode ? (
        <span className="w-full font-mono text-[11px] font-semibold text-red-800">
          {stage.errorCode}
        </span>
      ) : null}
      {stage.skipReason ? (
        <span className="w-full text-[11px] text-slate-500">skipped: {stage.skipReason}</span>
      ) : null}
    </li>
  );
}

export default function UsageSummary({ usage }: UsageSummaryProps) {
  // Before the early return: a hook after a conditional return breaks the rules of hooks.
  const [detailOpen, setDetailOpen] = useState(false);

  if (!usage) return null;

  const share = ocrShare(usage.pages.ocrPages, usage.pages.total);
  const retried = usage.attempts.retriedAttempts;
  const wallClock = usage.elapsed.packageWallClockMs ?? usage.elapsed.packageStageElapsedMs;
  // Elapsed is a PACKAGE number and the label says so, because the payload says so.
  const elapsedLabel = usage.elapsed.perDocumentElapsedAvailable ? 'elapsed' : 'elapsed (package)';

  return (
    <section
      data-testid="usage-summary"
      data-ocr-pages={String(usage.pages.ocrPages)}
      data-model-calls={String(usage.modelCost.calls)}
      aria-label="Processing cost"
      className="shrink-0 border-b border-slate-200 bg-slate-50"
    >
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1 px-4 py-1.5">
        <Stat name="pages" label="pages">
          {String(usage.pages.total)}
        </Stat>

        {/* Emphasised: a scanned page is the one thing here that reliably costs
            more than the page next to it. */}
        <Stat name="ocr" label="ocr" emphasis={usage.pages.ocrPages > 0}>
          {String(usage.pages.ocrPages)}
          {share === null ? null : (
            <span className="ml-1 font-normal text-slate-500">({String(share)}%)</span>
          )}
        </Stat>

        <Stat name="elapsed" label={elapsedLabel}>
          {formatElapsed(wallClock)}
        </Stat>

        {retried > 0 ? (
          <Stat name="retries" label="retries" emphasis>
            {String(retried)}
          </Stat>
        ) : null}

        <Stat name="cost" label="model cost">
          {formatUsd(usage.modelCost.costUsd)}
          <span className="ml-1 font-normal text-slate-500">
            {usage.modelCost.calls === 0
              ? '· no model calls'
              : `· ${String(usage.modelCost.calls)} calls`}
          </span>
        </Stat>

        <button
          type="button"
          data-testid="usage-detail-toggle"
          aria-expanded={detailOpen}
          onClick={() => { setDetailOpen(!detailOpen); }}
          className="ml-auto rounded-sm border border-slate-300 bg-white px-2 py-0.5 text-[11px] text-slate-700 hover:border-slate-400"
        >
          {detailOpen ? 'Hide breakdown' : 'Breakdown'}
        </button>
      </div>

      {detailOpen ? (
        <div className="border-t border-slate-200 bg-white">
          {/* Retries first: it is the number a reader is most likely to be able
              to act on, and the wasted seconds are stated rather than left to be
              subtracted out of the total. */}
          {usage.attempts.retries.length > 0 ? (
            <p
              data-testid="usage-wasted"
              className="border-b border-slate-100 px-3 py-1.5 text-[11px] text-amber-900"
            >
              {formatElapsed(usage.elapsed.failedAttemptElapsedMs)} of the elapsed time went to
              attempts that failed:{' '}
              {usage.attempts.retries
                .map(
                  (retry) =>
                    `${retry.stage} ×${String(retry.attempts)} (${retry.lastErrorCode ?? 'no code'})`,
                )
                .join(', ')}
              .
            </p>
          ) : null}

          <ol className="divide-y divide-slate-100">
            {usage.elapsed.stages.map((stage) => (
              <StageRow key={`${stage.stage}-${String(stage.attempt)}`} stage={stage} />
            ))}
          </ol>

          {usage.documents.length > 0 ? (
            <div className="border-t border-slate-200">
              <h4 className="px-3 pt-2 text-[10px] font-semibold tracking-wide text-slate-500 uppercase">
                Pages per document
              </h4>
              {/* Pages ARE attributable to a document; elapsed is not. So this
                  table shows page and OCR counts and no durations — the split
                  the data actually supports. */}
              <ul className="divide-y divide-slate-100">
                {usage.documents.map((document) => (
                  <li
                    key={document.documentId}
                    data-testid="usage-document-row"
                    data-document-id={document.documentId}
                    className="flex items-baseline gap-2 px-3 py-1 text-[11px]"
                  >
                    <span className="font-mono text-slate-700">{document.documentTypeCode}</span>
                    <span className="ml-auto tabular-nums text-slate-600">
                      {String(document.pages)} pages
                    </span>
                    <span className="tabular-nums text-slate-500">
                      {String(document.ocrPages)} ocr
                    </span>
                  </li>
                ))}
              </ul>
              {usage.unassignedPages > 0 ? (
                <p className="px-3 py-1 text-[11px] text-slate-500">
                  {String(usage.unassignedPages)} page
                  {usage.unassignedPages === 1 ? '' : 's'} belong to no document (blank or
                  duplicate) — they were still rendered and read.
                </p>
              ) : null}
            </div>
          ) : null}

          {/* The anti-lie footer. A total whose scope is unstated invites the
              reader to assume it is complete. */}
          <div
            data-testid="usage-producers"
            className="border-t border-slate-200 px-3 py-1.5 text-[11px] text-slate-500"
          >
            {usage.modelCost.producers.map((producer) => (
              <p key={producer.producer}>
                <span className="font-mono text-slate-700">{producer.producer}</span>{' '}
                {producer.observed ? 'counted above' : 'not counted'} — {producer.reason}
              </p>
            ))}
          </div>
        </div>
      ) : null}
    </section>
  );
}
