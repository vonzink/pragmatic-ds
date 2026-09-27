import Link from "next/link";
import { Logo } from "@/components/brand/Logo";
import { Button } from "@/components/ui/Button";
import { Container } from "@/components/ui/Container";
import { mainNav, primaryCta, site } from "@/config/site";
import { MobileNav } from "./MobileNav";

/** Utility bar (scrolls away) + sticky main bar with the graphite bottom rule (Guide §3). */
export function Header() {
  return (
    <>
      <div className="hidden border-b border-line md:block">
        <Container className="flex h-[30px] items-center justify-between font-mono text-[11px] tracking-[0.06em] text-muted uppercase">
          <span>{site.tagline}</span>
          <span className="flex gap-3">
            {site.contact.linkedin && (
              <>
                <a href={site.contact.linkedin} className="hover:text-ink">
                  LinkedIn
                </a>
                <span aria-hidden>·</span>
              </>
            )}
            <a href={`mailto:${site.contact.email}`} className="hover:text-ink">
              {site.contact.email}
            </a>
          </span>
        </Container>
      </div>

      <header className="sticky top-0 z-40 border-b border-ink bg-surface">
        <Container className="flex h-[58px] items-center justify-between md:h-16">
          <Logo />

          <nav aria-label="Main" className="hidden items-center gap-[26px] md:flex">
            {mainNav.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                className="text-sm font-medium text-ink underline-offset-[6px] hover:underline"
              >
                {item.label}
              </Link>
            ))}
            <Button href={primaryCta.href} variant="graphite" className="!min-h-0 px-4 py-[11px] text-sm">
              {primaryCta.label}
            </Button>
          </nav>

          <MobileNav />
        </Container>
      </header>
    </>
  );
}
