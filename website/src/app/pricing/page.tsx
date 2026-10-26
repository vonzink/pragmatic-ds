import type { Metadata } from "next";
import { Accordion, type Faq } from "@/components/ui/Accordion";
import { Container } from "@/components/ui/Container";
import { CtaBand, InnerHero, PriceTierCard } from "@/components/ui/blocks";

export const metadata: Metadata = {
  title: "Pricing",
  description: "Starting prices for fixed-scope government contract accounting advisory. Audit support is scoped individually.",
  alternates: { canonical: "/pricing/" },
};

const tiers = [
  {
    title: "SF 1408 readiness review",
    price: "[$2,750]",
    forLine: "For first-time cost-type bidders with an existing accounting system.",
    items: ["Criteria gap matrix", "Mock survey walkthrough", "Written gap report", "Corrective action plan and re-check"],
  },
  {
    title: "Indirect rate structure",
    badge: "Most common",
    price: "[$3,250]",
    forLine: "For contractors who need pools, bases and a methodology they can defend.",
    items: ["Pool and base design", "Rate calculation on current-year data", "Written methodology", "Provisional billing rate support"],
  },
  {
    title: "Post-award system review",
    price: "[$4,000]",
    forLine: "For contractors who already hold cost-type work.",
    items: ["DFARS 252.242-7006 criteria review", "Findings ranked by severity", "Remediation plan", "Re-check after remediation"],
  },
];

const fees = [
  ["SF 1408 pre-award readiness", "Starting at [$2,750]", "Gap matrix, mock survey, gap report, corrective action plan, one re-check."],
  ["DCAA / DCMA audit support", "Scoped individually", "Notice triage, document map and response support through close-out of the audit."],
  ["Incurred cost submissions", "Starting at [$4,500]", "Schedules prepared and reconciled, adequacy review, package ready for the ICE model."],
  ["Indirect cost rates", "Starting at [$3,250]", "Pool and base design, rate calculation, written methodology."],
  ["Post-award accounting systems", "Starting at [$4,000]", "DFARS 252.242-7006 review, findings, remediation plan."],
  ["Labor and timekeeping", "Starting at [$1,500]", "Policy assessment, control testing, one employee training session."],
  ["Monthly compliance advisory", "From [$750]/month", "Standing advisory time, quarterly rate check, first call on any notice."],
];

const commitments = [
  "Reply within one business day",
  "Scoped proposal within three business days",
  "Diagnostic typically starts within one week",
];

const faqs: Faq[] = [
  {
    q: "Why “starting at”?",
    a: "The price covers a defined scope for a single-entity contractor with one accounting system. More entities, more contracts or heavier remediation raise it, and we say so in the proposal before work begins.",
  },
  { q: "Can we pay in stages?", a: "Yes. Fixed-scope work is typically half at kickoff and half at delivery of the written report." },
  {
    q: "Is the first call free?",
    a: "Yes. A short triage call costs nothing and ends with either a scoped proposal or a referral elsewhere.",
  },
];

const feeCols = "md:grid-cols-[minmax(200px,1.2fr)_180px_2fr]";

export default function PricingPage() {
  return (
    <>
      <InnerHero
        eyebrow="Pricing"
        title="Prices are on this page."
        lead="Starting prices for fixed-scope work. Audit support is scoped individually because no two notices are alike."
      />

      <section className="band bg-surface-raised">
        <Container className="flex flex-col gap-[clamp(40px,5vw,56px)]">
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,280px),1fr))] gap-4">
            {tiers.map((t) => (
              <PriceTierCard key={t.title} {...t} />
            ))}
          </div>

          <div>
            <h2 className="h2">Fixed-scope work.</h2>
            <div className="mt-5 overflow-hidden rounded-sm border border-line">
              <div className={`eyebrow hidden gap-4 border-b border-line bg-surface px-4 py-2.5 text-muted md:grid ${feeCols}`}>
                <span>Project</span>
                <span>Starting fee</span>
                <span>What it covers</span>
              </div>
              {fees.map(([project, fee, covers]) => (
                <div
                  key={project}
                  className={`grid gap-x-4 gap-y-1.5 border-b border-line-soft px-4 py-3.5 text-sm leading-[1.7] last:border-b-0 hover:bg-surface ${feeCols}`}
                >
                  <span className="font-medium text-ink">{project}</span>
                  <span className="font-mono text-[13px] font-medium text-accent">{fee}</span>
                  <span>{covers}</span>
                </div>
              ))}
            </div>
          </div>

          <div>
            <h2 className="h2">What we commit to.</h2>
            <div className="mt-5 grid grid-cols-[repeat(auto-fit,minmax(min(100%,240px),1fr))] gap-px overflow-hidden rounded-sm border border-line bg-line">
              {commitments.map((c, i) => (
                <div key={c} className="bg-surface-raised p-5">
                  <div className="numeral">{String(i + 1).padStart(2, "0")}</div>
                  <p className="mt-2.5 text-base leading-[1.4] font-medium text-ink">{c}</p>
                </div>
              ))}
            </div>
            <p className="disclosure mt-4 max-w-[720px]">
              Starting prices apply to defined scope and are confirmed in a written proposal before work begins. Audit
              support, remediation beyond a corrective action plan and multi-entity work are scoped individually. PDS
              provides advisory services and does not implement software, prepare taxes or keep books.
            </p>
          </div>

          <div className="max-w-[720px]">
            <h2 className="h2 border-b border-ink pb-4">Pricing questions.</h2>
            <Accordion items={faqs} />
          </div>
        </Container>
      </section>

      <CtaBand
        title="Not sure which one you need?"
        body="Tell us what you received. We will say which scope fits, or that none does."
        primary={{ label: "Request a readiness review", href: "/contact/" }}
        secondary={{ label: "Book a consult", href: "/contact/" }}
      />
    </>
  );
}
