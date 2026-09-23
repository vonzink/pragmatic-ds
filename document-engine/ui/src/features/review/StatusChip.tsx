/**
 * The small status words — validation, review, extraction method — shown the
 * same way on a flat row and in a cluster's detail strip.
 *
 * The style map is keyed by string rather than by a union: the server's
 * validation vocabulary is string constants, not a Java enum, so an unknown
 * status has to render neutrally instead of vanishing.
 */

const VALIDATION_STYLES: Record<string, string> = {
  VALID: 'bg-emerald-100 text-emerald-900',
  WARNING: 'bg-amber-100 text-amber-900',
  ERROR: 'bg-red-100 text-red-900',
  MANUAL_REVIEW_REQUIRED: 'bg-amber-100 text-amber-900',
  UNABLE_TO_VALIDATE: 'bg-slate-100 text-slate-600',
  NOT_VALIDATED: 'bg-slate-100 text-slate-600',
};

export default function StatusChip({ status, testId }: { status: string; testId: string }) {
  return (
    <span
      data-testid={testId}
      data-status={status}
      className={`rounded-sm px-1.5 py-0.5 text-[11px] font-medium ${
        VALIDATION_STYLES[status] ?? 'bg-slate-100 text-slate-600'
      }`}
    >
      {status.toLowerCase().replace(/_/g, ' ')}
    </span>
  );
}
