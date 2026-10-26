import type { Metadata } from "next";
import Link from "next/link";
import { Container } from "@/components/ui/Container";
import { AtAGlance, CtaBand, Headshot } from "@/components/ui/blocks";
import { disclosure } from "@/config/site";

export const metadata: Metadata = {
  title: "About",
  description:
    "A veteran-owned advisory practice founded by a disabled veteran and former DCAA auditor, working alongside your accountant.",
  alternates: { canonical: "/about/" },
};

const glance = [
  { k: "Serve", v: "Federal contractors, 3 to 50 employees" },
  { k: "Work", v: "Assess, advise, prepare, support. Advisory only." },
  { k: "Where", v: "Remote, nationwide. Denver-based." },
  { k: "Systems we know", v: "QuickBooks, Xero, Sage, Unanet, Deltek" },
  { k: "Reply time", v: "Within one business day" },
];

const timeline = [
  ["1990–1991", "U.S. Army", "Combat service during Desert Storm."],
  ["[YYYY]", "B.S. Public Accounting, Mesa State College", "Later licensed as a CPA in Colorado."],
  [
    "[YYYY–YYYY]",
    "DCAA, Denver",
    "Auditor → Incurred Cost Technical Specialist → Supervisory Auditor → GAGAS QA Auditor. Audit tours in Iraq and Afghanistan for DCAA and the State Department.",
  ],
  ["[YYYY–YYYY]", "VA Office of Inspector General", "GAGAS quality assurance and policy auditor."],
  ["[YYYY–YYYY]", "Department of Energy OIG", "Incurred Cost Audit Division."],
  ["[YYYY]", "Founded Pragmatic Defense Solutions", "Advisory services for small and midsize federal contractors."],
];

const dos = [
  "Assess your system against the criteria the auditor uses",
  "Advise on what has to change and why",
  "Prepare submissions, schedules and written methodologies",
  "Support you through audits and reviews",
  "Train your staff on timekeeping and charging",
];
const donts = [
  "Tax preparation or planning",
  "Bookkeeping",
  "Payroll",
  "Software implementation or configuration",
  "General management consulting",
];

const principles = [
  { title: "People", text: "We talk to the person who will sit across from the auditor, and we prepare them." },
  { title: "Strategy", text: "Fix what will stop an award first. Everything else is sequenced after." },
  { title: "Impact", text: "Every engagement ends with a document you can hand to an auditor or a CO." },
  { title: "Plain English", text: "We cite the regulation, then say what it means for your books." },
];

export default function AboutPage() {
  return (
    <>
      <section className="hero-inner">
        <Container className="flex flex-wrap items-start gap-x-16 gap-y-10">
          <div className="min-w-0 flex-[1_1_520px]">
            <p className="eyebrow text-muted">About</p>
            <h1 className="h1 mt-4 max-w-[760px]">
              A former federal auditor and an operator, working alongside your accountant.
            </h1>
            <p className="lead mt-[18px]">
              Pragmatic Defense Solutions is a veteran-owned advisory practice founded by a disabled veteran. We help
              small and midsize federal contractors understand, document and demonstrate compliant contract accounting.
            </p>
          </div>
          <AtAGlance rows={glance} labelWidth={120} monoLabels />
        </Container>
      </section>

      <section className="band bg-surface-raised">
        <Container className="flex flex-col gap-[clamp(40px,5vw,56px)]">
          <TwoCol title="Why PDS exists.">
            <p>
              Small contractors get the same accounting system criteria as large ones, with none of the staff. When a
              survey request or an audit notice arrives, the founder, the controller and the outside CPA are usually
              learning the rules on a deadline.
            </p>
            <p>
              PDS was started to close that gap from the auditor&apos;s side. We know the checklists because we applied
              them, and we explain them in plain English so your team and your accountant can act.
            </p>
          </TwoCol>

          <div>
            <div className="flex flex-wrap items-end justify-between gap-5 border-b border-ink pb-5">
              <div className="flex items-center gap-4">
                <Headshot ground="bone" />
                <div>
                  <h2 className="h2">William Austin, CPA</h2>
                  <div className="mt-1.5 text-sm text-muted">
                    Founder · 24 years federal contract audit · Licensed CPA, Colorado
                  </div>
                </div>
              </div>
              <span className="eyebrow text-muted">Founder timeline</span>
            </div>
            <div className="mt-5 overflow-hidden rounded-sm border border-line bg-surface-raised">
              {timeline.map(([years, title, line]) => (
                <div
                  key={title}
                  className="grid gap-x-5 gap-y-1 border-b border-line-soft px-5 py-3.5 text-sm leading-[1.6] last:border-b-0 hover:bg-surface md:grid-cols-[140px_minmax(180px,1fr)_2fr]"
                >
                  <span className="font-mono text-[13px] font-medium text-accent">{years}</span>
                  <span className="font-semibold text-ink">{title}</span>
                  <span>{line}</span>
                </div>
              ))}
            </div>
            <p className="disclosure mt-3.5">
              Roles and years are stated for background. Past government employment does not imply agency endorsement.
              Bracketed dates to be confirmed.
            </p>
          </div>

          <div className="flex flex-wrap items-start gap-x-6 gap-y-4 rounded-sm border border-line p-6">
            <Headshot ground="bone" />
            <div className="flex-[1_1_320px]">
              <h3 className="text-xl font-semibold text-ink">Brook Zink</h3>
              <div className="mt-1 text-sm text-muted">Co-owner · Operations</div>
              <p className="mt-3 max-w-[640px] text-[15px] leading-[1.6]">
                Brook brings 15+ years running finance, HR and operations for growing companies. She runs intake,
                scheduling and delivery so engagements start on time and end with the documents you need.
              </p>
            </div>
          </div>

          <div>
            <h2 className="h2">What we do and don&apos;t do.</h2>
            <div className="mt-5 grid grid-cols-[repeat(auto-fit,minmax(min(100%,280px),1fr))] gap-px overflow-hidden rounded-sm border border-line bg-line">
              <DoList label="Do" items={dos} accent />
              <DoList label="Don't" items={donts} />
            </div>
          </div>

          <TwoCol title="For CPAs and attorneys.">
            <div>
              <p>
                We work alongside the client&apos;s existing CPA, attorney or APEX Accelerator counselor. We do not take
                over the books, the tax work or the legal relationship, and we report back to you as the client
                prefers.
              </p>
              <Link
                href="/contact/?type=referral"
                className="mt-3.5 inline-block text-[15px] font-medium text-accent hover:underline"
              >
                Refer a client →
              </Link>
            </div>
          </TwoCol>

          <div>
            <h2 className="h2">How we work.</h2>
            <div className="mt-5 grid grid-cols-[repeat(auto-fit,minmax(min(100%,220px),1fr))] gap-px overflow-hidden rounded-sm border border-line bg-line">
              {principles.map((p) => (
                <div key={p.title} className="bg-surface-raised p-5">
                  <div className="eyebrow text-accent">{p.title}</div>
                  <p className="mt-2.5 text-[15px] leading-[1.6]">{p.text}</p>
                </div>
              ))}
            </div>
            <p className="disclosure mt-4 max-w-[720px]">{disclosure}</p>
          </div>
        </Container>
      </section>

      <CtaBand
        title="Tell us what you received."
        body="A survey request, an audit notice, a deadline. We reply within one business day."
        primary={{ label: "Request a readiness review", href: "/contact/" }}
        secondary={{ label: "Book a consult", href: "/contact/" }}
      />
    </>
  );
}

function TwoCol({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap gap-x-16 gap-y-6">
      <h2 className="h2 flex-[0_1_360px]">{title}</h2>
      <div className="flex max-w-[720px] flex-[1_1_440px] flex-col gap-3.5 text-base leading-[1.6] text-pretty">
        {children}
      </div>
    </div>
  );
}

function DoList({ label, items, accent = false }: { label: string; items: string[]; accent?: boolean }) {
  return (
    <div className="bg-surface-raised">
      <div className={`eyebrow border-b border-line-soft px-5 py-3 ${accent ? "text-accent" : "text-muted"}`}>{label}</div>
      <ul className={`px-5 py-1.5 text-sm leading-[1.7] ${accent ? "text-ink" : "text-body"}`}>
        {items.map((it) => (
          <li key={it} className="border-b border-line-soft py-1.5 last:border-b-0">
            {it}
          </li>
        ))}
      </ul>
    </div>
  );
}
