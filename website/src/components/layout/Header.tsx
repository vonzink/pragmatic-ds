"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Logo } from "@/components/brand/Logo";
import { Button } from "@/components/ui/Button";
import { resourceLinks, site, telHref } from "@/config/site";
import { serviceHref, services } from "@/content/services";

type Menu = "s" | "r" | null;

const navLink = "text-ink hover:underline";
const sheetRow = "flex h-[52px] items-center border-b border-line text-[17px] font-semibold text-ink";

/**
 * Sticky header: utility bar + main bar with Services/Resources dropdowns (900px and up),
 * hamburger sheet below 900px.
 */
export function Header() {
  const pathname = usePathname();
  const [open, setOpen] = useState<Menu>(null);
  const [menu, setMenu] = useState(false);
  const [ms, setMs] = useState(true);
  const [mr, setMr] = useState(false);
  // Refs, not state: hover and click fire back to back, before React re-renders the handlers.
  const hoverOpenedAt = useRef(0);
  const current = useRef<Menu>(null);

  // Close everything on navigation (adjusting state during render, keyed on the path).
  const [lastPath, setLastPath] = useState(pathname);
  if (pathname !== lastPath) {
    setLastPath(pathname);
    setOpen(null);
    setMenu(false);
  }
  useEffect(() => {
    current.current = null;
  }, [pathname]);

  useEffect(() => {
    if (!open && !menu) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        current.current = null;
        setOpen(null);
        setMenu(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, menu]);

  useEffect(() => {
    document.body.style.overflow = menu ? "hidden" : "";
  }, [menu]);

  // Hovering opens a menu a moment before the click lands. A click right after that hover-open
  // keeps the menu open instead of toggling it shut; later clicks toggle normally.
  const show = (m: Menu) => {
    current.current = m;
    setOpen(m);
  };
  const hover = (m: Menu) => {
    if (m && m !== current.current) hoverOpenedAt.current = Date.now();
    show(m);
  };
  const toggle = (m: Exclude<Menu, null>) => {
    if (current.current === m && Date.now() - hoverOpenedAt.current < 500) return;
    show(current.current === m ? null : m);
  };

  return (
    <header className="sticky top-0 z-50 bg-surface">
      <div className="hidden h-[30px] border-b border-line min-[900px]:block">
        <div className="mx-auto flex h-[30px] max-w-content items-center justify-between px-10 font-mono text-[11px] font-medium tracking-[0.14em] text-muted uppercase">
          <span>{site.tagline}</span>
          <div className="flex gap-3.5">
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
            {site.contact.phone && (
              <>
                <span aria-hidden>·</span>
                <a href={telHref(site.contact.phone)} className="hover:text-ink">
                  {site.contact.phone}
                </a>
              </>
            )}
          </div>
        </div>
      </div>

      {/* Close on leaving the whole bar + panel, so the diagonal move from the nav to a menu item keeps it open. */}
      <div className="relative border-b border-ink" onMouseLeave={() => show(null)}>
        <div className="mx-auto flex h-[58px] max-w-content items-center justify-between gap-6 px-[clamp(20px,3vw,40px)] min-[900px]:h-16">
          <Logo />

          <nav aria-label="Main" className="hidden items-center gap-7 self-stretch text-sm font-medium min-[900px]:flex">
            <button
              type="button"
              aria-expanded={open === "s"}
              aria-controls="menu-services"
              onClick={() => toggle("s")}
              onMouseEnter={() => hover("s")}
              className="flex cursor-pointer items-center gap-1 text-ink"
            >
              Services <span className="text-[10px]" aria-hidden>▾</span>
            </button>
            <Link href="/pricing/" className={navLink} onMouseEnter={() => hover(null)}>
              Pricing
            </Link>
            <Link href="/about/" className={navLink} onMouseEnter={() => hover(null)}>
              About
            </Link>
            <button
              type="button"
              aria-expanded={open === "r"}
              aria-controls="menu-resources"
              onClick={() => toggle("r")}
              onMouseEnter={() => hover("r")}
              className="flex cursor-pointer items-center gap-1 text-ink"
            >
              Resources <span className="text-[10px]" aria-hidden>▾</span>
            </button>
            <Link href="/contact/" className={navLink} onMouseEnter={() => hover(null)}>
              Contact
            </Link>
            <span onMouseEnter={() => hover(null)}>
              <Button href="/contact/" variant="graphite" className="!h-10 !px-[18px]">
                Book a consult
              </Button>
            </span>

            {open === "s" && (
              <DropdownPanel id="menu-services">
                <div className="grid grid-cols-3 gap-x-8 gap-y-1">
                  {services.map((s) => (
                    <Link
                      key={s.slug}
                      href={serviceHref(s)}
                      className="block rounded-sm p-3 text-ink hover:bg-surface"
                    >
                      <div className="flex items-baseline gap-2.5">
                        <span className="numeral">{s.num}</span>
                        <span className="text-[15px] font-semibold">{s.title}</span>
                      </div>
                      <div className="mt-1 text-sm leading-[1.5] font-normal text-muted">{s.short}</div>
                    </Link>
                  ))}
                </div>
                <div className="mt-4 flex items-center justify-between border-t border-line-soft pt-4">
                  <Link href="/#services" className="text-sm font-medium text-accent hover:underline">
                    All services →
                  </Link>
                  <span className="eyebrow text-muted">Starting prices on /pricing</span>
                </div>
              </DropdownPanel>
            )}
            {open === "r" && (
              <DropdownPanel id="menu-resources">
                <div className="grid grid-cols-3 gap-x-8 gap-y-1">
                  {resourceLinks.map((r) => (
                    <Link key={r.href} href={r.href} className="block rounded-sm p-3 text-ink hover:bg-surface">
                      <div className="text-[15px] font-semibold">{r.label}</div>
                      <div className="mt-1 text-sm leading-[1.5] font-normal text-muted">{r.blurb}</div>
                    </Link>
                  ))}
                </div>
                <div className="mt-4 border-t border-line-soft pt-4">
                  <Link href="/resources/" className="text-sm font-medium text-accent hover:underline">
                    All resources →
                  </Link>
                </div>
              </DropdownPanel>
            )}
          </nav>

          <button
            type="button"
            aria-label={menu ? "Close menu" : "Open menu"}
            aria-expanded={menu}
            aria-controls="mobile-menu"
            onClick={() => setMenu((m) => !m)}
            className="-mr-2.5 flex h-11 w-11 cursor-pointer flex-col items-center justify-center gap-[5px] min-[900px]:hidden"
          >
            <span className="block h-0.5 w-[22px] bg-ink" />
            <span className="block h-0.5 w-[22px] bg-ink" />
            <span className="block h-0.5 w-[22px] bg-ink" />
          </button>
        </div>
      </div>

      {menu && (
        <div
          id="mobile-menu"
          className="fixed inset-x-0 top-[59px] bottom-0 z-[60] flex flex-col bg-surface min-[900px]:hidden"
        >
          <div className="flex-1 overflow-auto px-5 pt-2 pb-5">
            <button
              type="button"
              aria-expanded={ms}
              onClick={() => setMs(!ms)}
              className={`${sheetRow} w-full cursor-pointer justify-between`}
            >
              Services <Plus open={ms} />
            </button>
            {ms && (
              <div className="flex flex-col border-b border-line py-1.5">
                {services.map((s) => (
                  <Link
                    key={s.slug}
                    href={serviceHref(s)}
                    className="flex min-h-11 items-center gap-2.5 text-[15px] text-body"
                  >
                    <span className="numeral">{s.num}</span>
                    {s.title}
                  </Link>
                ))}
              </div>
            )}
            <Link href="/pricing/" className={sheetRow}>
              Pricing
            </Link>
            <Link href="/about/" className={sheetRow}>
              About
            </Link>
            <button
              type="button"
              aria-expanded={mr}
              onClick={() => setMr(!mr)}
              className={`${sheetRow} w-full cursor-pointer justify-between`}
            >
              Resources <Plus open={mr} />
            </button>
            {mr && (
              <div className="flex flex-col border-b border-line py-1.5">
                {resourceLinks.map((r) => (
                  <Link key={r.href} href={r.href} className="flex min-h-11 items-center text-[15px] text-body">
                    {r.label}
                  </Link>
                ))}
              </div>
            )}
            <Link href="/contact/" className={sheetRow}>
              Contact
            </Link>
            <div className="mt-5 flex flex-col text-sm leading-[1.7] text-muted">
              <a href={`mailto:${site.contact.email}`}>{site.contact.email}</a>
              {site.contact.phone && <a href={telHref(site.contact.phone)}>{site.contact.phone}</a>}
              {site.contact.linkedin && <a href={site.contact.linkedin}>LinkedIn</a>}
            </div>
          </div>
          <div className="border-t border-ink bg-surface px-5 pt-4 pb-6">
            <Button href="/contact/" variant="graphite" className="w-full">
              Book a consult
            </Button>
          </div>
        </div>
      )}
    </header>
  );
}

function DropdownPanel({ id, children }: { id: string; children: React.ReactNode }) {
  return (
    <div
      id={id}
      className="absolute inset-x-0 top-full border-b border-ink bg-surface-raised shadow-[0_12px_28px_rgba(22,24,26,.10)]"
    >
      <div className="mx-auto max-w-content px-10 pt-7 pb-6">{children}</div>
    </div>
  );
}

function Plus({ open }: { open: boolean }) {
  return (
    <span aria-hidden className={`text-[22px] font-normal transition-transform duration-150 ${open ? "rotate-45" : ""}`}>
      +
    </span>
  );
}
