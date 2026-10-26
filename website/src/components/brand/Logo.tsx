import Link from "next/link";
import { site } from "@/config/site";
import { Mark } from "./Mark";

type LogoProps = {
  tone?: "light" | "reverse";
  className?: string;
};

/** 36px mark + single-line wordmark. Links home. */
export function Logo({ tone = "light", className = "" }: LogoProps) {
  const reverse = tone === "reverse";
  return (
    <Link
      href="/"
      className={`flex items-center gap-2.5 ${reverse ? "text-on-ink" : "text-ink"} ${className}`}
    >
      <Mark tone={tone} className="h-9 w-auto flex-none" />
      <span className="text-[13px] font-semibold tracking-[-0.01em] whitespace-nowrap min-[400px]:text-[15px]">
        {site.name}{" "}
        <span className={`font-normal ${reverse ? "text-on-ink-muted" : "text-muted"}`}>LLC</span>
      </span>
    </Link>
  );
}
