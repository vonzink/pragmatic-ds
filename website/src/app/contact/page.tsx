import type { Metadata } from "next";
import { ContactForm } from "@/components/ContactForm";
import { Container } from "@/components/ui/Container";
import { InnerHero } from "@/components/ui/blocks";
import { site, telHref } from "@/config/site";

export const metadata: Metadata = {
  title: "Contact",
  description: "Request a readiness review or book a triage call. We reply within one business day.",
  alternates: { canonical: "/contact/" },
};

const ready = [
  "The request or notice itself",
  "Contract type and agency",
  "Accounting platform",
  "Fiscal year end",
  "Deadline date",
];

const reachRow = "grid grid-cols-[90px_1fr] gap-3 border-b border-line py-3 text-sm leading-[1.6]";

export default function ContactPage() {
  return (
    <>
      <InnerHero
        eyebrow="Contact"
        title="What did you receive?"
        lead="A survey request, an audit notice, a deadline. Send the basics and we reply within one business day."
      />
      <section className="band bg-surface-raised">
        <Container className="flex flex-wrap items-start gap-x-12 gap-y-10">
          <div className="flex min-w-0 flex-[1_1_300px] flex-col gap-7">
            <div className="rounded-sm border border-line">
              <div className="eyebrow border-b border-line-soft px-5 py-3 text-muted">What to have ready</div>
              {ready.map((r, i) => (
                <div key={r} className="flex gap-3 border-b border-line-soft px-5 py-3 text-sm leading-[1.6] last:border-b-0">
                  <span className="numeral">{String(i + 1).padStart(2, "0")}</span>
                  <span className="text-ink">{r}</span>
                </div>
              ))}
            </div>
            <div>
              <div className="eyebrow border-b border-ink pb-3 text-muted">Other ways to reach us</div>
              <div className={reachRow}>
                <span className="text-muted">Email</span>
                <a href={`mailto:${site.contact.email}`} className="text-ink hover:underline">
                  {site.contact.email}
                </a>
              </div>
              {site.contact.phone && (
                <div className={reachRow}>
                  <span className="text-muted">Phone</span>
                  <a href={telHref(site.contact.phone)} className="text-ink hover:underline">
                    {site.contact.phone}
                  </a>
                </div>
              )}
              {site.contact.linkedin && (
                <div className={reachRow}>
                  <span className="text-muted">LinkedIn</span>
                  <a href={site.contact.linkedin} className="text-accent hover:underline">
                    {site.name}
                  </a>
                </div>
              )}
              <p className="disclosure mt-3">Vendors and sales: email only, please.</p>
            </div>
          </div>
          <ContactForm />
        </Container>
      </section>
    </>
  );
}
