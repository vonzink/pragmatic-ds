import Link from "next/link";
import { Accordion, type Faq } from "@/components/ui/Accordion";
import { Button } from "@/components/ui/Button";
import { Container } from "@/components/ui/Container";
import { AtAGlance, Headshot, SectionHeader } from "@/components/ui/blocks";
import { serviceHref, services } from "@/content/services";

const glance = [
  { k: "Founded by", v: "William Austin, CPA" },
  { k: "Experience", v: "24 yrs federal contract audit, 20 at DCAA" },
  { k: "We do", v: "Assess, advise, prepare, support" },
  { k: "We don't", v: "Tax, bookkeeping, software setup" },
  { k: "Where", v: "Remote, nationwide (Denver-based)" },
  { k: "Reply time", v: "Within one business day" },
];

const stats = [
  { v: "24", label: "years federal contract audit" },
  { v: "20", label: "years at DCAA" },
  { v: "3", label: "agencies audited for: DCAA, VA OIG, DOE OIG" },
];

const steps = ["Intake", "Diagnostic", "Findings", "Remediation"];

const situations = [
  "“The CO wants a pre-award survey and we bid in three weeks.”",
  "“Our incurred cost submission is due and our books weren’t set up for it.”",
  "“DCAA questioned our labor charging.”",
  "“We just won our first cost-type contract and our accountant has never done this.”",
];

const faqs: Faq[] = [
  {
    q: "Are you a CPA firm?",
    a: "William Austin is a licensed CPA in Colorado. PDS is an advisory practice, not a public accounting firm, and does not replace your CPA.",
  },
  {
    q: "Can you guarantee we pass the survey?",
    a: "No one can. We prepare you against the criteria the auditor uses and tell you plainly where you stand.",
  },
  {
    q: "Do you set up QuickBooks or Unanet?",
    a: "No. We tell you what the system has to do and work with your accountant or vendor on how.",
  },
  {
    q: "How fast can you start?",
    a: "We reply within one business day and can usually begin a diagnostic within a week.",
  },
  {
    q: "What does it cost?",
    a: "Starting prices for fixed-scope work are on the Pricing page. Audit support is scoped individually.",
  },
];

export default function HomePage() {
  return (
    <>
      <section className="bg-surface px-[clamp(20px,3vw,40px)] pt-[clamp(40px,6vw,72px)] pb-[clamp(36px,5vw,56px)]">
        <Container className="flex flex-wrap items-start gap-x-16 gap-y-10">
          <div className="min-w-0 flex-[1_1_420px]">
            <p className="eyebrow text-muted">Government contract accounting &amp; compliance advisory</p>
            <h1 className="h1 h1-home mt-5 max-w-[760px]">Built by the auditor who used to check your books.</h1>
            <p className="lead mt-[22px]">
              Pre-award surveys, incurred cost submissions, indirect rates and audit response for small and midsize
              federal contractors. Advisory services that work alongside your existing accountant.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <Button href="/contact/" stack>
                Request a readiness review
              </Button>
              <Button href="/contact/?deadline=1" variant="outline" stack>
                Facing an audit deadline?
              </Button>
            </div>
          </div>
          <AtAGlance rows={glance} />
        </Container>
      </section>

      <section id="services" className="band scroll-mt-24 border-t border-ink bg-surface-raised">
        <Container>
          <SectionHeader title="What we help with." flag="Starting prices on /pricing" flagHref="/pricing/" />
          {services.map((s) => (
            <Link
              key={s.slug}
              href={serviceHref(s)}
              className="-mx-2 flex flex-wrap gap-x-8 gap-y-3 rounded-sm border-b border-line px-2 py-6 text-body hover:bg-surface"
            >
              <div className="numeral flex-[0_0_40px] pt-[5px]">{s.num}</div>
              <div className="min-w-0 flex-[1_1_340px]">
                <div className="flex flex-wrap items-center gap-2.5">
                  <h3 className="text-xl leading-[1.25] font-semibold tracking-[-0.01em] text-ink">{s.title}</h3>
                  {s.badge && <span className="badge">{s.badge}</span>}
                </div>
                <p className="mt-2 max-w-[600px] text-[15px] leading-[1.6] text-pretty">{s.blurb}</p>
              </div>
              <div className="flex min-w-[min(100%,220px)] flex-[0_1_260px] flex-col justify-between gap-3.5">
                <ul className="hidden text-sm leading-[1.7] md:block">
                  {s.items.map((it) => (
                    <li key={it} className="border-b border-line-soft last:border-b-0">
                      {it}
                    </li>
                  ))}
                </ul>
                <div className="flex items-baseline justify-between gap-3">
                  <span className="text-sm font-medium text-accent">Explore →</span>
                  <span className="font-mono text-[13px] font-medium text-muted">{s.price}</span>
                </div>
              </div>
            </Link>
          ))}
        </Container>
      </section>

      <section className="band bg-surface">
        <Container className="flex flex-wrap items-start gap-x-16 gap-y-10">
          <div className="min-w-0 flex-[1_1_480px]">
            <p className="eyebrow text-muted">Why a former auditor</p>
            <h2 className="h2 mt-4 max-w-[640px] text-pretty">
              We know what the auditor is looking for because we were the auditor.
            </h2>
            <p className="mt-5 max-w-[600px] text-base leading-[1.6] text-pretty">
              William Austin spent 20 years at the Defense Contract Audit Agency in Denver as an auditor, incurred cost
              technical specialist, supervisory auditor and GAGAS quality assurance auditor, with audit tours in Iraq
              and Afghanistan. He went on to GAGAS quality assurance work at the VA Office of Inspector General and
              incurred cost audits at the Department of Energy OIG. He works daily in the FAR, DFARS, DEAR and CAS.
            </p>
            <div className="mt-7 flex items-center gap-4 border-t border-line pt-6">
              <Headshot />
              <div>
                <div className="text-base font-semibold text-ink">William Austin, CPA</div>
                <div className="mt-0.5 text-sm text-muted">Founder · 24 years federal contract audit</div>
              </div>
            </div>
            <p className="disclosure mt-4">
              Roles and years are stated for background. Past government employment does not imply agency
              endorsement.
            </p>
          </div>
          <div className="flex min-w-0 flex-[1_1_340px] flex-col gap-4">
            <div className="card grid grid-cols-[repeat(auto-fit,minmax(min(100%,140px),1fr))] overflow-hidden">
              {stats.map((s) => (
                <div key={s.v} className="-mr-px -mb-px border-r border-b border-line-soft px-5 py-6">
                  <div className="text-[40px] leading-none font-semibold tracking-[-0.02em] text-accent">{s.v}</div>
                  <div className="mt-2.5 text-sm leading-[1.5]">{s.label}</div>
                </div>
              ))}
            </div>
            <div className="card overflow-hidden">
              <div className="eyebrow border-b border-line-soft px-5 py-3 text-muted">How we work</div>
              <div className="grid grid-cols-2 gap-px bg-line-soft">
                {steps.map((s, i) => (
                  <div key={s} className="flex items-baseline gap-2.5 bg-surface-raised px-5 py-4">
                    <span className="numeral">{i + 1}</span>
                    <span className="font-mono text-xs font-medium tracking-[0.12em] text-ink uppercase">{s}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </Container>
      </section>

      <section className="band bg-ink text-on-ink">
        <Container>
          <SectionHeader title="Where clients usually start." flag="Where clients start" tone="reverse" />
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,240px),1fr))] gap-x-8">
            {situations.map((q, i) => (
              <div key={q} className="border-b border-line-dark py-6">
                <div className="font-mono text-[13px] font-medium text-accent-light">
                  {String(i + 1).padStart(2, "0")}
                </div>
                <p className="mt-3 text-[17px] leading-[1.45] text-pretty">{q}</p>
              </div>
            ))}
          </div>
        </Container>
      </section>

      <section id="faq" className="band scroll-mt-24 bg-surface-raised">
        <Container className="flex flex-wrap items-start gap-x-16 gap-y-10">
          <div className="min-w-0 flex-[1_1_480px]">
            <h2 className="h2 border-b border-ink pb-5">Questions we get first.</h2>
            <Accordion items={faqs} initialOpen={0} />
          </div>
          <div className="min-w-[min(100%,280px)] flex-[0_1_400px] rounded-sm border border-line bg-surface p-7">
            <p className="eyebrow text-muted">Next step</p>
            <h2 className="h2 mt-3">Tell us what you received.</h2>
            <p className="mt-3.5 text-[15px] leading-[1.6]">
              A survey request, an audit notice, a deadline. Send the basics and we will reply within one business
              day.
            </p>
            <div className="mt-6 flex flex-col gap-2.5">
              <Button href="/contact/">Request a readiness review</Button>
              <Button href="/contact/" variant="outline">
                Book a consult
              </Button>
            </div>
          </div>
        </Container>
      </section>
    </>
  );
}
