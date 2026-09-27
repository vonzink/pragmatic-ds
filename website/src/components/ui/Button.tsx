import Link from "next/link";
import type { ReactNode } from "react";

type Variant = "primary" | "graphite" | "secondary" | "secondary-on-ink";

const base =
  "inline-flex min-h-12 items-center justify-center rounded-sm px-[22px] text-[15px] font-semibold transition-colors md:min-h-0 md:py-3.5";

const variants: Record<Variant, string> = {
  /** Defense Blue. Use once per view (the main call to action). */
  primary: "bg-accent text-on-ink hover:bg-accent-hover",
  /** Graphite fill. Header button, so the hero keeps the only blue button. */
  graphite: "bg-ink text-on-ink hover:bg-ink-2",
  /** Outline on light grounds. */
  secondary: "border border-ink text-ink hover:bg-ink hover:text-on-ink",
  /** Outline on the graphite band. */
  "secondary-on-ink": "border border-muted text-on-ink hover:border-on-ink",
};

type ButtonProps = {
  href: string;
  variant?: Variant;
  className?: string;
  children: ReactNode;
};

/** Link styled as a button. Every button on this site navigates, so it's always an <a>. */
export function Button({ href, variant = "primary", className = "", children }: ButtonProps) {
  const classes = `${base} ${variants[variant]} ${className}`;
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
