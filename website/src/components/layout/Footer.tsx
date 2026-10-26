import Link from "next/link";
import { disclosure, site, telHref } from "@/config/site";
import { serviceHref, services } from "@/content/services";

const columnTitle = "eyebrow mb-3.5 text-muted";
const columnLinks = "flex flex-col gap-2 text-sm leading-[1.5]";
const footerLink = "text-body hover:underline";
const legalLink = "text-muted hover:underline";

/**
 * Bone footer with a graphite top rule. The rule is dropped (globals.css) when a graphite
 * CTA band is the last thing in <main>.
 */
export function Footer() {
  return (
    <footer className="border-t border-ink bg-surface text-body">
      <div className="mx-auto max-w-content px-[clamp(20px,3vw,40px)] pt-[clamp(36px,5vw,56px)]">
        <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,220px),1fr))] gap-x-10 gap-y-8 border-b border-line pb-10">
          <div>
            <p className="text-[15px] font-semibold text-ink">
              {site.name} <span className="font-normal text-muted">LLC</span>
            </p>
            <p className="mt-2.5 mb-3.5 max-w-[260px] text-sm leading-[1.6]">
              Government contract accounting and compliance advisory for small and midsize federal contractors.
            </p>
            <span className="eyebrow text-accent">Veteran-owned</span>
          </div>

          <div>
            <p className={columnTitle}>Services</p>
            <div className={columnLinks}>
              {services.map((s) => (
                <Link key={s.slug} href={serviceHref(s)} className={footerLink}>
                  {s.title}
                </Link>
              ))}
            </div>
          </div>

          <div>
            <p className={columnTitle}>Company</p>
            <div className={columnLinks}>
              <Link href="/about/" className={footerLink}>About</Link>
              <Link href="/pricing/" className={footerLink}>Pricing</Link>
              <Link href="/resources/" className={footerLink}>Resources</Link>
              <Link href="/#faq" className={footerLink}>FAQ</Link>
              <Link href="/contact/" className={footerLink}>Contact</Link>
              <a href="/sitemap.xml" className={footerLink}>Site map</a>
            </div>
          </div>

          <div>
            <p className={columnTitle}>Contact</p>
            <div className={columnLinks}>
              <a href={`mailto:${site.contact.email}`} className={footerLink}>
                {site.contact.email}
              </a>
              {site.contact.phone && (
                <a href={telHref(site.contact.phone)} className={footerLink}>
                  {site.contact.phone}
                </a>
              )}
              {site.contact.mailingAddress && <span>{site.contact.mailingAddress}</span>}
              <span>
                {site.contact.location}
                {site.contact.linkedin && (
                  <>
                    {" · "}
                    <a href={site.contact.linkedin} className="text-body underline">
                      LinkedIn
                    </a>
                  </>
                )}
              </span>
            </div>
          </div>
        </div>

        <div className="pt-6 pb-8">
          {/* Privacy, Terms and Accessibility pages are pending client copy. */}
          <div className="flex flex-wrap gap-x-5 gap-y-2 text-[13px] text-muted">
            <span>Privacy Policy</span>
            <span>Terms of Use</span>
            <a href="#disclosures" className={legalLink}>Disclosures</a>
            <span>Accessibility</span>
          </div>
          <p id="disclosures" className="disclosure mt-4 max-w-[880px]">
            {disclosure}
          </p>
          <p className="disclosure mt-3">
            © 2026 {site.legalName}. Veteran-owned. Founded by a disabled veteran.
          </p>
        </div>
      </div>
    </footer>
  );
}
