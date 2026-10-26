import Link from "next/link";
import { TocBox } from "@/components/TocBox";
import { Accordion } from "@/components/ui/Accordion";
import { Button } from "@/components/ui/Button";
import { Container } from "@/components/ui/Container";
import { CtaBand, InnerHero, LastReviewed, NumberedList, PriceTierCard, RegTable } from "@/components/ui/blocks";
import type { ServiceDetail } from "@/content/serviceDetails";
import { selfCheckHref, serviceHref, services, type Service } from "@/content/services";

type Section = { id: string; label: string; body: React.ReactNode };

/**
 * Service page template: hero, sticky "On this page" rail + 720px article, CTA band.
 * With a `detail`, renders the full long-form page; without one, a short page from the service summary.
 */
export function ServicePage({ service, detail }: { service: Service; detail?: ServiceDetail }) {
  const sections: Section[] = detail ? fullSections(detail) : shortSections(service);
  const toc = [...sections.map((s) => ({ id: s.id, label: s.label })), { id: "reviewed", label: "Last reviewed" }];
  const selfCheck = service.slug === "sf-1408-pre-award-accounting";
  const secondary = selfCheck
    ? { label: "Run the self-check", href: selfCheckHref }
    : { label: "See pricing", href: "/pricing/" };

  return (
    <>
      <InnerHero
        eyebrow={detail?.eyebrow ?? `Service ${service.num}`}
        title={detail?.h1 ?? `${service.title}.`}
        lead={detail?.lead ?? service.blurb}
        breadcrumb={{ label: "Services", href: "/#services", current: service.title }}
        wideTitle
      >
        <div className="mt-7 flex flex-wrap gap-3">
          <Button href="/contact/" stack>
            Request a readiness review
          </Button>
          <Button href={secondary.href} variant="outline" stack>
            {secondary.label}
          </Button>
        </div>
        <div className="mt-7 flex flex-wrap gap-2">
          {(detail?.chips ?? ["24 yrs federal contract audit", "20 yrs DCAA", service.price, "Reply within one business day"]).map(
            (c) => (
              <span
                key={c}
                className="rounded-sm border border-line bg-surface-raised px-2.5 py-1.5 font-mono text-xs font-medium tracking-[0.06em] text-ink"
              >
                {c}
              </span>
            ),
          )}
        </div>
      </InnerHero>

      <section className="band bg-surface-raised">
        <Container className="flex flex-wrap items-start gap-x-16 gap-y-10">
          <TocRail toc={toc} />
          <TocBox toc={toc} />
          <article className="flex max-w-[720px] min-w-0 flex-[1_1_520px] flex-col gap-[clamp(40px,5vw,56px)]">
            {sections.map((s) => (
              <section key={s.id} id={s.id} className="scroll-mt-32">
                {s.body}
              </section>
            ))}
            <LastReviewed regs={detail?.regsCited} />
          </article>
        </Container>
      </section>

      <CtaBand
        title={detail?.cta.title ?? "Tell us what you received."}
        body={detail?.cta.body ?? "A survey request, an audit notice, a deadline. We reply within one business day."}
        primary={{ label: "Request a readiness review", href: "/contact/" }}
        secondary={selfCheck ? { label: "Run the self-check", href: selfCheckHref } : { label: "Book a consult", href: "/contact/" }}
      />
    </>
  );
}

function fullSections(d: ServiceDetail): Section[] {
  return [
    {
      id: "fits",
      label: "Who this fits",
      body: (
        <>
          <h2 className="h2">Who this fits.</h2>
          <div className="mt-[18px] flex flex-col gap-3.5 text-base leading-[1.6] text-pretty">
            {d.fits.map((p) => (
              <p key={p}>{p}</p>
            ))}
          </div>
        </>
      ),
    },
    {
      id: "evaluates",
      label: "What the auditor evaluates",
      body: (
        <>
          <h2 className="h2">What the auditor evaluates.</h2>
          <p className="mt-3.5 text-base leading-[1.6]">{d.evaluates.intro}</p>
          <div className="mt-5">
            <RegTable head={["Citation", "What it covers"]} rows={d.evaluates.rows} external />
          </div>
        </>
      ),
    },
    {
      id: "fail",
      label: "Where contractors fail",
      body: (
        <>
          <h2 className="h2">Where contractors fail.</h2>
          <div className="mt-5">
            <NumberedList items={d.fails} />
          </div>
        </>
      ),
    },
    {
      id: "help",
      label: "How we help",
      body: (
        <>
          <h2 className="h2">How we help.</h2>
          <div className="mt-5 grid grid-cols-[repeat(auto-fit,minmax(min(100%,260px),1fr))] gap-px overflow-hidden rounded-sm border border-line bg-line">
            {d.help.map((h) => (
              <div key={h.phase} className="bg-surface-raised p-5">
                <div className="eyebrow text-muted">{h.phase}</div>
                <h3 className="mt-2 text-[17px] font-semibold text-ink">{h.title}</h3>
                <p className="mt-1.5 text-[15px] leading-[1.6]">{h.text}</p>
              </div>
            ))}
          </div>
        </>
      ),
    },
    {
      id: "price",
      label: "Deliverables and price",
      body: (
        <>
          <h2 className="h2">Deliverables and price.</h2>
          <PriceTierCard className="mt-5 max-w-[480px]" {...d.tier} />
          <p className="disclosure mt-3.5">{d.priceNote}</p>
        </>
      ),
    },
    {
      id: "faq",
      label: "FAQ",
      body: (
        <>
          <h2 className="h2 border-b border-ink pb-4">{d.faqTitle}</h2>
          <Accordion items={d.faqs} />
        </>
      ),
    },
    { id: "related", label: "Related", body: <Related links={d.related} /> },
  ];
}

function shortSections(s: Service): Section[] {
  const scoped = !s.price.startsWith("Starting at");
  const related = services
    .filter((o) => o.slug !== s.slug)
    .slice(0, 3)
    .map((o) => ({ label: o.title, href: serviceHref(o) }))
    .concat({ label: "SF 1408 self-check tool", href: selfCheckHref });
  return [
    {
      id: "help",
      label: "How we help",
      body: (
        <>
          <h2 className="h2">How we help.</h2>
          <div className="mt-5">
            <NumberedList items={s.items} />
          </div>
        </>
      ),
    },
    {
      id: "price",
      label: "Deliverables and price",
      body: (
        <>
          <h2 className="h2">Deliverables and price.</h2>
          {scoped ? (
            <div className="card mt-5 max-w-[480px] px-6 py-6">
              <h3 className="text-[17px] font-semibold text-ink">{s.title}</h3>
              <p className="mt-3 text-[24px] font-semibold tracking-[-0.02em] text-accent">{s.price}</p>
              <p className="mt-2.5 text-sm leading-[1.6] text-muted">
                No two notices are alike. We scope the work after a short triage call.
              </p>
              <Button href="/contact/" className="mt-5 w-full">
                Book a consult
              </Button>
            </div>
          ) : (
            <PriceTierCard
              className="mt-5 max-w-[480px]"
              title={s.title}
              price={s.price.replace("Starting at ", "")}
              forLine={s.blurb}
              items={s.items}
            />
          )}
          <p className="disclosure mt-3.5">
            Starting prices apply to defined scope. Audit support and remediation beyond the plan are scoped
            individually.
          </p>
        </>
      ),
    },
    { id: "related", label: "Related", body: <Related links={related} /> },
  ];
}

function Related({ links }: { links: { label: string; href: string }[] }) {
  return (
    <>
      <h2 className="h2">Related.</h2>
      <div className="mt-5 grid grid-cols-[repeat(auto-fit,minmax(min(100%,260px),1fr))] gap-px overflow-hidden rounded-sm border border-line bg-line">
        {links.map((l) => (
          <Link
            key={l.href}
            href={l.href}
            className="flex justify-between bg-surface-raised px-5 py-4 text-[15px] font-medium text-ink hover:bg-surface"
          >
            {l.label} <span className="text-accent">→</span>
          </Link>
        ))}
      </div>
    </>
  );
}

export type Toc = { id: string; label: string }[];

function TocRail({ toc }: { toc: Toc }) {
  return (
    <nav aria-label="On this page" className="sticky top-[120px] hidden flex-[0_0_220px] min-[900px]:block">
      <div className="eyebrow border-b border-ink pb-3 text-muted">On this page</div>
      <div className="flex flex-col">
        {toc.map((t, i) => (
          <a
            key={t.id}
            href={`#${t.id}`}
            className="flex gap-2.5 border-b border-line-soft py-[9px] text-sm text-body hover:text-accent"
          >
            <span className="font-mono text-xs font-medium text-accent">{String(i + 1).padStart(2, "0")}</span>
            {t.label}
          </a>
        ))}
      </div>
    </nav>
  );
}
