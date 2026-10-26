import Link from "next/link";
import type { ReactNode } from "react";
import { Button } from "./Button";
import { Container } from "./Container";

/** H2 + optional right-aligned mono flag, over a 1px rule. */
export function SectionHeader({
  title,
  flag,
  flagHref,
  tone = "light",
  className = "",
}: {
  title: string;
  flag?: string;
  flagHref?: string;
  tone?: "light" | "reverse";
  className?: string;
}) {
  const reverse = tone === "reverse";
  const flagClass = `eyebrow ${reverse ? "text-accent-light" : "text-muted"}`;
  return (
    <div
      className={`flex flex-wrap items-end justify-between gap-x-6 gap-y-3 border-b pb-5 ${reverse ? "border-line-dark" : "border-ink"} ${className}`}
    >
      <h2 className={`h2 ${reverse ? "!text-on-ink" : ""}`}>{title}</h2>
      {flag &&
        (flagHref ? (
          <Link href={flagHref} className={`${flagClass} hover:text-accent`}>
            {flag}
          </Link>
        ) : (
          <span className={flagClass}>{flag}</span>
        ))}
    </div>
  );
}

/** Bone hero with breadcrumb/eyebrow, H1 and lead, used on every page except Home. */
export function InnerHero({
  eyebrow,
  title,
  lead,
  breadcrumb,
  wideTitle = false,
  children,
}: {
  eyebrow: string;
  title: string;
  lead?: ReactNode;
  breadcrumb?: { label: string; href: string; current: string };
  wideTitle?: boolean;
  children?: ReactNode;
}) {
  return (
    <section className="hero-inner">
      <Container>
        {breadcrumb && (
          <nav aria-label="Breadcrumb" className="mb-7 flex gap-2 text-sm text-muted">
            <Link href={breadcrumb.href} className="hover:underline">
              {breadcrumb.label}
            </Link>
            <span aria-hidden>/</span>
            <span className="text-ink" aria-current="page">
              {breadcrumb.current}
            </span>
          </nav>
        )}
        <p className="eyebrow text-muted">{eyebrow}</p>
        <h1 className={`h1 mt-4 ${wideTitle ? "max-w-[820px]" : "max-w-[760px]"}`}>{title}</h1>
        {lead && <p className="lead mt-[18px]">{lead}</p>}
        {children}
      </Container>
    </section>
  );
}

/** White bordered card with a mono header and label/value rows. */
export function AtAGlance({
  rows,
  labelWidth = 104,
  monoLabels = false,
}: {
  rows: { k: string; v: string }[];
  labelWidth?: number;
  monoLabels?: boolean;
}) {
  return (
    <div className="card min-w-[min(100%,280px)] flex-[0_1_360px]">
      <div className="eyebrow border-b border-line-soft px-5 py-3 text-muted">At a glance</div>
      {rows.map((g) => (
        <div
          key={g.k}
          className="grid gap-3 border-b border-line-soft px-5 py-3 text-sm leading-[1.5] last:border-b-0"
          style={{ gridTemplateColumns: `${labelWidth}px 1fr` }}
        >
          <span
            className={
              monoLabels
                ? "pt-[3px] font-mono text-[11px] font-medium tracking-[0.12em] text-muted uppercase"
                : "text-muted"
            }
          >
            {g.k}
          </span>
          <span className="font-medium text-ink">{g.v}</span>
        </div>
      ))}
    </div>
  );
}

export function Headshot({ size = 72, ground = "white" }: { size?: 56 | 72; ground?: "white" | "bone" }) {
  return (
    <div
      aria-hidden
      className={`flex flex-none items-center justify-center rounded-full border border-line text-center font-mono tracking-[0.08em] text-muted ${ground === "white" ? "bg-surface-raised" : "bg-surface"}`}
      style={{ width: size, height: size, fontSize: size === 56 ? 9 : 10 }}
    >
      [HEAD
      <br />
      SHOT]
    </div>
  );
}

/** Pricing card: title, "Starting at" price, who it's for, inclusions, CTA. */
export function PriceTierCard({
  title,
  price,
  forLine,
  items,
  badge,
  className = "",
}: {
  title: string;
  price: string;
  forLine: string;
  items: string[];
  badge?: string;
  className?: string;
}) {
  return (
    <div className={`card flex flex-col transition-colors hover:border-ink ${className}`}>
      <div className="border-b border-line-soft px-6 pt-6 pb-5">
        <div className="flex min-h-[22px] items-center justify-between gap-2.5">
          <h3 className="text-[17px] font-semibold text-ink">{title}</h3>
          {badge && <span className="badge">{badge}</span>}
        </div>
        <div className="mt-3 flex items-baseline gap-2">
          <span className="eyebrow text-muted">Starting at</span>
          <span className="text-[32px] font-semibold tracking-[-0.02em] text-accent">{price}</span>
        </div>
        <p className="mt-2.5 text-sm leading-[1.6] text-muted">{forLine}</p>
      </div>
      <ul className="flex-1 px-6 py-2 text-sm leading-[1.7]">
        {items.map((it) => (
          <li key={it} className="border-b border-line-soft py-1.5 last:border-b-0">
            {it}
          </li>
        ))}
      </ul>
      <div className="px-6 pt-4 pb-6">
        <Button href="/contact/" className="w-full">
          Book a consult
        </Button>
      </div>
    </div>
  );
}

/** Graphite band with H2 + body left and two buttons right. Sits last in <main>. */
export function CtaBand({
  title,
  body,
  primary,
  secondary,
}: {
  title: string;
  body: string;
  primary: { label: string; href: string };
  secondary: { label: string; href: string };
}) {
  return (
    <section className="cta-band band bg-ink text-on-ink">
      <Container className="flex flex-wrap items-center justify-between gap-x-12 gap-y-6">
        <div className="flex-[1_1_400px]">
          <h2 className="h2 !text-on-ink">{title}</h2>
          <p className="mt-3 max-w-[560px] text-base leading-[1.6]">{body}</p>
        </div>
        <div className="flex w-full flex-wrap gap-3 md:w-auto">
          <Button href={primary.href} stack>
            {primary.label}
          </Button>
          <Button href={secondary.href} variant="outline-on-ink" stack>
            {secondary.label}
          </Button>
        </div>
      </Container>
    </section>
  );
}

/** Numbered rows under a graphite rule. */
export function NumberedList({ items }: { items: ReactNode[] }) {
  return (
    <div className="border-t border-ink">
      {items.map((text, i) => (
        <div
          key={i}
          className="grid grid-cols-[40px_1fr] gap-3 border-b border-line py-3.5 text-[15px] leading-[1.6] text-ink"
        >
          <span className="numeral pt-[3px]">{String(i + 1).padStart(2, "0")}</span>
          <span>{text}</span>
        </div>
      ))}
    </div>
  );
}

/** Bordered citation table: mono link left, description right. */
export function RegTable({
  head,
  rows,
  external = false,
}: {
  head: [string, string];
  rows: { cite: string; text: string; href: string }[];
  external?: boolean;
}) {
  const cols = "md:grid-cols-[minmax(150px,220px)_1fr]";
  return (
    <div className="overflow-hidden rounded-sm border border-line">
      <div className={`eyebrow hidden gap-3 border-b border-line bg-surface px-4 py-2.5 text-muted md:grid ${cols}`}>
        <span>{head[0]}</span>
        <span>{head[1]}</span>
      </div>
      {rows.map((r) => (
        <div
          key={r.cite}
          className={`grid gap-x-3 gap-y-1 border-b border-line-soft px-4 py-3 text-sm leading-[1.7] last:border-b-0 hover:bg-surface ${cols}`}
        >
          <a
            href={r.href}
            {...(external ? { target: "_blank", rel: "noopener noreferrer" } : {})}
            className="font-mono text-[13px] font-medium text-accent hover:underline"
          >
            {r.cite}
          </a>
          <span>{r.text}</span>
        </div>
      ))}
    </div>
  );
}

/** Bone card closing every service page. */
export function LastReviewed({ regs }: { regs?: string }) {
  return (
    <section id="reviewed" className="rounded-sm border border-line bg-surface px-6 py-5">
      <div className="eyebrow flex flex-wrap justify-between gap-x-6 gap-y-2 text-muted">
        <span>Last reviewed [DATE]</span>
        <span>Reviewed by William Austin, CPA</span>
      </div>
      {regs && <p className="mt-3 text-sm leading-[1.7]">Regulations cited: {regs}.</p>}
      <p className="disclosure mt-2.5">
        This page is educational. It is not a determination of accounting system adequacy or legal advice.
        Pragmatic Defense Solutions LLC is not affiliated with DCAA, DCMA or any federal agency.
      </p>
    </section>
  );
}
