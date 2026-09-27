import Link from "next/link";
import { site } from "@/config/site";
import { Mark } from "./Mark";

type LogoProps = {
  tone?: "light" | "reverse";
  className?: string;
};

/** Mark + single-line wordmark (Guide §6). Links home. */
export function Logo({ tone = "light", className = "" }: LogoProps) {
  const reverse = tone === "reverse";
  return (
    <Link
      href="/"
      className={`inline-flex items-center gap-2.5 ${reverse ? "text-on-ink" : "text-ink"} ${className}`}
    >
      <Mark tone={tone} className="h-[34px] w-auto md:h-[38px]" />
      <span className="text-[13px] font-semibold tracking-[-0.01em] md:text-[15px]">
        {site.name}{" "}
        <span className={`font-normal ${reverse ? "text-on-ink-muted" : "text-muted"}`}>LLC</span>
      </span>
    </Link>
  );
}
