"use client";

import Link from "next/link";
import { useState } from "react";

type Answer = "Y" | "N" | "U" | "NA" | null;

/**
 * SF 1408 Section II criteria, paraphrased. [text, ref, what the auditor asks for, allows N/A].
 * Verify against the current SF 1408 before changing.
 */
const C: [string, string, string, boolean][] = [
  ["Accounting system is in accordance with generally accepted accounting principles.", "SF 1408 II.1", "A trial balance and the basis of accounting your books are kept on.", false],
  ["Direct costs are segregated from indirect costs.", "SF 1408 II.2.a", "Chart of accounts showing separate direct and indirect account ranges.", false],
  ["Direct costs are identified and accumulated by contract.", "SF 1408 II.2.b", "A job cost report by contract reconciled to the general ledger.", false],
  ["A logical and consistent method allocates indirect costs to cost objectives.", "SF 1408 II.2.c", "A written indirect rate methodology: pools, bases and the rationale.", false],
  ["Costs are accumulated under general ledger control.", "SF 1408 II.2.d", "Reconciliation of job cost subledger totals to the general ledger.", false],
  ["Timekeeping identifies employee labor by intermediate or final cost objective.", "SF 1408 II.2.e", "Timesheets with charge codes, a written timekeeping policy and evidence of supervisor approval.", false],
  ["Labor distribution charges direct and indirect labor to the appropriate cost objectives.", "SF 1408 II.2.f", "A labor distribution report reconciling timesheet hours to payroll and to the ledger.", false],
  ["Costs charged to contracts are determined at least monthly (interim determination).", "SF 1408 II.2.g", "Monthly close evidence and month-end job cost reports.", false],
  ["Unallowable costs under FAR Part 31 are excluded from costs charged to government contracts.", "SF 1408 II.2.h", "Unallowable accounts in the chart of accounts and a screening procedure.", false],
  ["Costs are identified by contract line item and by units when required by the contract.", "SF 1408 II.2.i", "A job cost structure that can report by CLIN.", true],
  ["Preproduction costs are segregated from production costs.", "SF 1408 II.2.j", "Account or project codes that separate preproduction work.", true],
  ["The system provides financial information to support limitation of cost and limitation of funds clauses.", "SF 1408 II.3.a", "Contract-to-date cost and funding reports against ceiling.", false],
  ["The system provides information to support requests for progress payments.", "SF 1408 II.3.b", "Cost incurred reports by contract supporting SF 1443 or equivalent.", true],
  ["The system produces reliable data for pricing follow-on acquisitions.", "SF 1408 II.4", "Historical cost by contract and task usable for estimating.", false],
  ["The accounting system is in full operation (not merely designed).", "SF 1408 II.5", "Live transactions, current period reports and posted payroll.", false],
];

const OPTS: [Exclude<Answer, null>, string][] = [
  ["Y", "Yes"],
  ["N", "No"],
  ["U", "Unsure"],
  ["NA", "N/A"],
];

/** Interactive self-check. Nothing is stored; state lives only in this page. */
export function SelfCheck() {
  const [ans, setAns] = useState<Answer[]>(() => Array(C.length).fill(null));

  const pick = (i: number, v: Answer) => setAns((a) => a.map((x, j) => (j === i ? v : x)));

  const answered = ans.filter(Boolean).length;
  const applicable = ans.filter((a) => a !== "NA").length;
  const yes = ans.filter((a) => a === "Y").length;
  const gaps = (want: Answer) =>
    C.flatMap((c, i) => (ans[i] === want ? [{ text: c[0], ref: c[1], ask: c[2] }] : []));
  const critical = gaps("N");
  const docs = gaps("U");
  const empty = answered === 0;
  const total = C.length;
  const unanswered = total - answered;

  const scoreNote =
    applicable < total
      ? `${total - applicable} marked not applicable. ${unanswered ? `${unanswered} still unanswered.` : "All criteria answered."}`
      : unanswered
        ? `${unanswered} still unanswered.`
        : "All criteria answered.";

  const summary = `SF 1408 self-check: ${yes} of ${applicable} criteria ready. Critical gaps: ${critical.map((g) => g.ref).join(", ") || "none"}. Needs documentation: ${docs.map((g) => g.ref).join(", ") || "none"}.`;
  const sendHref = `/contact/?type=survey&summary=${encodeURIComponent(summary)}`;

  const seeResult = () => {
    const el = document.getElementById("result");
    if (el) window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - 80, behavior: "smooth" });
  };

  return (
    <section className="band bg-surface-raised pb-[clamp(88px,8vw,56px)] min-[900px]:pb-[clamp(36px,5vw,56px)]">
      <div className="mx-auto flex max-w-content flex-wrap items-start gap-x-12 gap-y-8">
        <div className="min-w-0 flex-[1_1_520px]">
          <div className="flex items-end justify-between gap-4 border-b border-ink pb-4">
            <h2 className="h2">Criteria</h2>
            <span className="eyebrow text-muted" aria-live="polite">
              {answered} of {total} answered
            </span>
          </div>
          <div className="mt-4 flex flex-col gap-2.5">
            {C.map((c, i) => {
              const n = String(i + 1).padStart(2, "0");
              return (
                <div
                  key={c[1]}
                  className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 rounded-sm border border-line bg-surface-raised px-[18px] py-4 hover:border-ink"
                >
                  <div className="flex min-w-0 flex-[1_1_300px] gap-3.5">
                    <span className="numeral flex-[0_0_24px] pt-[3px]">{n}</span>
                    <div>
                      <div id={`c-${i}`} className="text-[15px] leading-[1.5] text-ink">
                        {c[0]}
                      </div>
                      <div className="mt-1 font-mono text-xs font-medium tracking-[0.06em] text-muted">{c[1]}</div>
                    </div>
                  </div>
                  <div
                    role="radiogroup"
                    aria-labelledby={`c-${i}`}
                    className="flex flex-none overflow-hidden rounded-sm border border-ink"
                  >
                    {OPTS.filter(([v]) => v !== "NA" || c[3]).map(([v, label]) => {
                      const on = ans[i] === v;
                      return (
                        <button
                          key={v}
                          type="button"
                          role="radio"
                          aria-checked={on}
                          onClick={() => pick(i, v)}
                          className={`relative h-12 min-w-16 cursor-pointer border-r border-ink px-3.5 text-sm font-medium last:border-r-0 hover:underline focus-visible:z-10 focus-visible:-outline-offset-2 ${on ? "bg-ink text-on-ink" : "bg-surface-raised text-ink"}`}
                        >
                          {label}
                        </button>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        <aside
          id="result"
          aria-live="polite"
          className="card min-w-[min(100%,300px)] flex-[0_1_360px] min-[900px]:sticky min-[900px]:top-[120px]"
        >
          <div className="eyebrow border-b border-line-soft px-5 py-3 text-muted">Your result</div>
          {empty ? (
            <div className="px-5 py-7">
              <div className="text-[40px] leading-none font-semibold tracking-[-0.02em] text-line">— of {total}</div>
              <p className="mt-3 text-[15px] leading-[1.6] text-muted">Answer the criteria to see your result.</p>
            </div>
          ) : (
            <>
              <div className="border-b border-line-soft px-5 py-6">
                <div className="text-[40px] leading-none font-semibold tracking-[-0.02em] text-accent">
                  {yes} of {applicable} criteria ready
                </div>
                <p className="mt-2.5 text-sm leading-[1.6] text-muted">{scoreNote}</p>
              </div>
              <GapList label="Critical gaps" items={critical} critical />
              <GapList label="Needs documentation" items={docs} />
              <div className="no-print flex flex-col gap-2.5 p-5">
                <Link
                  href={sendHref}
                  className="flex min-h-12 items-center justify-center rounded-sm bg-accent px-4 py-2.5 text-center text-[15px] leading-[1.3] font-semibold text-on-ink hover:bg-accent-hover"
                >
                  Send these results with a readiness review request
                </Link>
                <button
                  type="button"
                  onClick={() => window.print()}
                  className="flex h-12 cursor-pointer items-center justify-center rounded-sm border border-ink text-[15px] font-semibold text-ink hover:bg-ink hover:text-on-ink"
                >
                  Print this page
                </button>
              </div>
            </>
          )}
          <p className="disclosure px-5 pb-5">
            Educational only. Not a determination of accounting system adequacy or legal advice. Not affiliated with any
            federal agency.
          </p>
        </aside>
      </div>

      {!empty && (
        <div className="no-print fixed inset-x-0 bottom-0 z-40 border-t border-ink bg-surface px-5 py-3 min-[900px]:hidden">
          <button
            type="button"
            onClick={seeResult}
            className="h-12 w-full cursor-pointer rounded-sm bg-ink text-[15px] font-semibold text-on-ink"
          >
            See result · {yes} of {applicable}
          </button>
        </div>
      )}
    </section>
  );
}

function GapList({
  label,
  items,
  critical = false,
}: {
  label: string;
  items: { text: string; ref: string; ask: string }[];
  critical?: boolean;
}) {
  if (!items.length) return null;
  return (
    <>
      <div className="px-5 pt-4 pb-1">
        <div className={`eyebrow ${critical ? "text-error" : "text-muted"}`}>
          {label} · {items.length}
        </div>
      </div>
      {items.map((g) => (
        <div key={g.ref} className="border-b border-line-soft px-5 py-3">
          <div className="flex justify-between gap-3 text-[15px] leading-[1.45] text-ink">
            <span>{g.text}</span>
            <span className="font-mono text-[11px] font-medium whitespace-nowrap text-muted">{g.ref}</span>
          </div>
          <p className="mt-1.5 text-sm leading-[1.55]">
            <span className="text-muted">What the auditor will ask for:</span> {g.ask}
          </p>
        </div>
      ))}
    </>
  );
}
