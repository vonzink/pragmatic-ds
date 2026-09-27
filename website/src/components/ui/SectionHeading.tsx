type SectionHeadingProps = {
  id?: string;
  eyebrow?: string;
  title: string;
  /** Right-aligned mono note (Guide §2 "Section flag"). Hidden on mobile. */
  flag?: string;
  tone?: "light" | "reverse";
};

/** Eyebrow + H2 on the left, optional section flag on the right. */
export function SectionHeading({ id, eyebrow, title, flag, tone = "light" }: SectionHeadingProps) {
  const reverse = tone === "reverse";
  return (
    <div className="mb-6 flex items-end justify-between gap-6 md:mb-8">
      <div>
        {eyebrow && (
          <p className={`eyebrow mb-3 ${reverse ? "text-accent-light" : "text-muted"}`}>{eyebrow}</p>
        )}
        <h2
          id={id}
          className={`text-2xl leading-[1.1] font-semibold tracking-[-0.02em] md:text-[30px] ${reverse ? "text-on-ink" : ""}`}
        >
          {title}
        </h2>
      </div>
      {flag && (
        <p
          className={`hidden font-mono text-xs tracking-[0.1em] uppercase md:block ${reverse ? "text-accent-light" : "text-muted"}`}
        >
          {flag}
        </p>
      )}
    </div>
  );
}
