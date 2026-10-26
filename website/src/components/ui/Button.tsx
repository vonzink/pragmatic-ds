import Link from "next/link";
import type { ReactNode } from "react";

type Variant = "primary" | "graphite" | "outline" | "outline-on-ink";

const base =
  "inline-flex h-12 max-w-full items-center justify-center rounded-sm px-[22px] text-center text-[15px] font-semibold transition-colors";

const variants: Record<Variant, string> = {
  /** Defense Blue. The main call to action. */
  primary: "bg-accent text-on-ink hover:bg-accent-hover",
  /** Graphite fill. Header and mobile menu only. */
  graphite: "bg-ink text-on-ink hover:bg-ink-2",
  /** Outline on light grounds. */
  outline: "border border-ink text-ink hover:bg-ink hover:text-on-ink",
  /** Outline on the graphite band. */
  "outline-on-ink": "border border-on-ink text-on-ink hover:bg-on-ink hover:text-ink",
};

type ButtonProps = {
  href: string;
  variant?: Variant;
  /** Full width on mobile, auto from 768px (the default for hero and CTA-band buttons). */
  stack?: boolean;
  className?: string;
  children: ReactNode;
};

/** Link styled as a button. */
export function Button({ href, variant = "primary", stack = false, className = "", children }: ButtonProps) {
  const classes = `${base} ${variants[variant]} ${stack ? "w-full md:w-auto" : ""} ${className}`;
  // Plain <a> for mailto:/tel:/external links; Next <Link> for in-site routes.
  if (/^(mailto:|tel:|https?:)/.test(href)) {
    return (
      <a href={href} className={classes}>
        {children}
      </a>
    );
  }
  return (
    <Link href={href} className={classes}>
      {children}
    </Link>
  );
}
