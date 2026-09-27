import Link from "next/link";
import { Container } from "@/components/ui/Container";
import { mainNav, site, telHref } from "@/config/site";
import { services } from "@/content/home";

const columnTitle = "mb-3 font-mono text-[10.5px] tracking-[0.1em] text-muted uppercase";
const footerLink = "text-ink hover:underline";

/** Bone footer with a graphite top rule (Guide §3). */
export function Footer() {
  const year = new Date().getFullYear();
  return (
    <footer className="border-t border-ink text-[13px]">
      <Container>
        <div className="grid gap-8 border-b border-line pt-10 pb-8 sm:grid-cols-2 lg:grid-cols-[1.5fr_1fr_1fr_1.2fr]">
          <div>
            <p className="text-sm font-semibold text-ink">
              {site.name} <span className="font-normal text-muted">LLC</span>
            </p>
            <p className="mt-2 max-w-xs leading-[1.55]">{site.description}</p>
            <p className="mt-3 font-mono text-[10.5px] tracking-[0.1em] text-accent uppercase">{site.tagline}</p>
          </div>

          <div>
            <p className={columnTitle}>Services</p>
            <ul className="flex flex-col gap-2">
              {services.map((s) => (
                <li key={s.title}>
                  <Link href="/#services" className={footerLink}>
                    {s.title}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          <div>
            <p className={columnTitle}>Company</p>
            <ul className="flex flex-col gap-2">
              {mainNav
                .filter((item) => item.label !== "Services")
                .map((item) => (
                  <li key={item.href}>
                    <Link href={item.href} className={footerLink}>
                      {item.label}
                    </Link>
                  </li>
                ))}
            </ul>
          </div>

          <div>
            <p className={columnTitle}>Contact</p>
            <ul className="flex flex-col gap-2">
              <li>
                <a href={`mailto:${site.contact.email}`} className={footerLink}>
                  {site.contact.email}
                </a>
              </li>
              {site.contact.phone && (
                <li>
                  <a href={telHref(site.contact.phone)} className={footerLink}>
                    {site.contact.phone}
                  </a>
                </li>
              )}
              <li>{site.contact.location}</li>
            </ul>
          </div>
        </div>

        <p className="py-5 text-muted">
          © {year} {site.legalName}. All rights reserved.
        </p>
      </Container>
    </footer>
  );
}
