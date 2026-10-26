import type { Metadata } from "next";
import Link from "next/link";
import { Container } from "@/components/ui/Container";
import { CtaBand, Headshot, InnerHero, RegTable, SectionHeader } from "@/components/ui/blocks";
import { selfCheckHref } from "@/content/services";

export const metadata: Metadata = {
  title: "Resources",
  description: "Free educational tools, dated guides and the official DCAA, FAR, DFARS and CAS sources for federal contractors.",
  alternates: { canonical: "/resources/" },
};

/** Tools without an href are designed but not built yet; they render as "Coming soon". */
const tools: { title: string; line: string; href?: string }[] = [
  { title: "SF 1408 self-check", line: "Score your accounting system against the pre-award survey criteria.", href: selfCheckHref },
  { title: "Indirect rate and wrap rate calculator", line: "Fringe, overhead and G&A from your pools and bases, with a fully loaded rate." },
  { title: "Incurred cost submission deadline tracker", line: "Enter your fiscal year end. Get the submission date and a working-back schedule." },
  { title: "“Which audit is this?” triage", line: "Paste the subject line of the notice. Learn what kind of review it is and who runs it." },
  { title: "Audit data-request checklist", line: "The documents DCAA typically asks for, by audit type, as a printable list." },
  { title: "GovCon accounting glossary", line: "Plain-English definitions for the terms in the FAR, DFARS and CAS you will hear first." },
];

const guides = [
  { topic: "Pre-award", title: "SF 1408 preparation guide (PDF)", cta: "Download →" },
  { topic: "Incurred cost", title: "What the ICE model schedules actually ask for", cta: "Read →" },
  { topic: "Rates", title: "Writing an indirect rate methodology you can defend", cta: "Read →" },
  { topic: "Timekeeping", title: "The labor floor check: what happens and how to prepare", cta: "Read →" },
];

const sources = [
  ["DCAA Checklists & Tools", "The agency’s own pre-award survey checklist and audit programs.", "https://www.dcaa.mil/Checklists-Tools/"],
  ["ICE model", "The incurred cost submission template DCAA expects.", "https://www.dcaa.mil/Checklists-Tools/ICE-Model/"],
  ["DCAA Contract Audit Manual", "How auditors are instructed to run each audit.", "https://www.dcaa.mil/Guidance/CAM-Contract-Audit-Manual/"],
  ["FAR Part 31", "Cost principles: what is allowable.", "https://www.acquisition.gov/far/part-31"],
  ["FAR 52.216-7", "Allowable Cost and Payment clause, including the six-month incurred cost deadline.", "https://www.acquisition.gov/far/52.216-7"],
  ["DFARS 252.242-7006", "The 18 post-award accounting system criteria.", "https://www.acquisition.gov/dfars/252.242-7006-accounting-system-administration."],
  ["CAS (48 CFR 99)", "Cost Accounting Standards, when coverage applies.", "https://www.ecfr.gov/current/title-48/chapter-99"],
  ["DCMA", "Contract administration and business system determinations.", "https://www.dcma.mil/"],
  ["SAM.gov", "Entity registration and CAGE code.", "https://sam.gov/"],
  ["SBA VetCert", "Veteran-owned small business certification.", "https://veterans.certify.sba.gov/"],
  ["APEX Accelerators", "Free government contracting counseling, by region.", "https://www.apexaccelerators.us/"],
].map(([cite, text, href]) => ({ cite, text, href }));

const row = "-mx-2 flex flex-wrap items-baseline gap-x-6 gap-y-2 rounded-sm border-b border-line px-2 py-[18px] text-body";

export default function ResourcesPage() {
  return (
    <>
      <InnerHero
        eyebrow="Resources"
        title="Tools, guides and the official sources."
        lead="Free, educational, and pointed at the primary documents. Nothing here stores what you enter."
      />

      <section className="band bg-surface-raised">
        <Container className="flex flex-col gap-[clamp(40px,5vw,56px)]">
          <div id="tools" className="scroll-mt-32">
            <SectionHeader title="Tools." flag="Free · Educational" className="!pb-4" />
            {tools.map((t, i) => {
              const inner = (
                <>
                  <span className="numeral flex-[0_0_32px]">{String(i + 1).padStart(2, "0")}</span>
                  <span className="flex-[1_1_240px] text-[17px] font-semibold text-ink">{t.title}</span>
                  <span className="flex-[3_1_320px] text-[15px] leading-[1.5]">{t.line}</span>
                  <span className={`flex-none text-sm font-medium ${t.href ? "text-accent" : "text-muted"}`}>
                    {t.href ? "Open →" : "Coming soon"}
                  </span>
                </>
              );
              return t.href ? (
                <Link key={t.title} href={t.href} className={`${row} hover:bg-surface`}>
                  {inner}
                </Link>
              ) : (
                <div key={t.title} className={row}>
                  {inner}
                </div>
              );
            })}
          </div>

          <div id="guides" className="scroll-mt-32">
            <SectionHeader title="Guides." flag="Reviewed and dated" className="!pb-4" />
            {guides.map((g) => (
              <div key={g.title} className={row}>
                <span className="flex-[0_0_150px] font-mono text-xs font-medium tracking-[0.12em] text-muted uppercase">
                  {g.topic}
                </span>
                <span className="flex-[1_1_300px] text-[17px] font-semibold text-ink">{g.title}</span>
                <span className="flex-none font-mono text-xs font-medium text-muted">Last reviewed [DATE]</span>
                <span className="flex-none text-sm font-medium text-muted">Coming soon</span>
              </div>
            ))}
          </div>

          <div id="sources" className="scroll-mt-32">
            <h2 className="h2">Official sources.</h2>
            <p className="mt-3 max-w-[720px] text-base leading-[1.6]">
              Primary documents first. If our page and the source disagree, the source wins.
            </p>
            <div className="mt-5">
              <RegTable head={["Source", "Why it matters"]} rows={sources} external />
            </div>
          </div>

          <div className="flex max-w-[720px] flex-col gap-3.5">
            <div className="flex items-center gap-4 rounded-sm border border-line bg-surface p-5">
              <Headshot size={56} />
              <div>
                <div className="eyebrow text-muted">Reviewed by</div>
                <div className="mt-1 text-base font-semibold text-ink">William Austin, CPA</div>
                <div className="text-sm text-muted">Founder · 24 years federal contract audit, 20 at DCAA</div>
              </div>
            </div>
            <p className="disclosure">
              Tools and guides are educational. They are not a determination of accounting system adequacy or legal
              advice. Pragmatic Defense Solutions LLC is not affiliated with, and is not endorsed by, DCAA, DCMA or any
              federal agency. Verify criteria against the current forms and regulations.
            </p>
          </div>
        </Container>
      </section>

      <CtaBand
        title="The tool found gaps?"
        body="Send the results with a readiness review request. We reply within one business day."
        primary={{ label: "Request a readiness review", href: "/contact/" }}
        secondary={{ label: "Run the self-check", href: selfCheckHref }}
      />
    </>
  );
}
