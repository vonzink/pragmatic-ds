"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { mainNav, primaryCta } from "@/config/site";

/** Hamburger + full-height bone sheet for screens under 768px. */
export function MobileNav() {
  const [open, setOpen] = useState(false);

  // Close on Escape and lock page scroll while the sheet is open.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
    };
  }, [open]);

  const close = () => setOpen(false);

  return (
    <div className="md:hidden">
      <button
        type="button"
        aria-expanded={open}
        aria-controls="mobile-menu"
        aria-label={open ? "Close menu" : "Open menu"}
        onClick={() => setOpen((v) => !v)}
        className="-mr-2.5 flex size-11 items-center justify-center text-ink"
      >
        <svg viewBox="0 0 24 24" className="size-6" aria-hidden fill="none" stroke="currentColor" strokeWidth={1.75}>
          {open ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
        </svg>
      </button>

      {open && (
        <div
          id="mobile-menu"
          className="fixed inset-x-0 top-[58px] bottom-0 z-40 flex flex-col bg-surface px-5 pb-6"
        >
          <nav aria-label="Main" className="flex flex-col">
            {mainNav.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                onClick={close}
                className="border-b border-line py-4 text-lg font-medium text-ink"
              >
                {item.label}
              </Link>
            ))}
          </nav>
          <Link
            href={primaryCta.href}
            onClick={close}
            className="mt-auto flex min-h-12 items-center justify-center rounded-sm bg-ink text-[15px] font-semibold text-on-ink"
          >
            {primaryCta.label}
          </Link>
        </div>
      )}
    </div>
  );
}
