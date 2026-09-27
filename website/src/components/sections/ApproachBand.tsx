import { Container } from "@/components/ui/Container";
import { SectionHeading } from "@/components/ui/SectionHeading";
import { approach } from "@/content/home";

/** The page's one graphite band: four-step process in a bordered table (Guide §4 "ScenarioGrid"). */
export function ApproachBand() {
  return (
    <section id="approach" aria-labelledby="approach-title" className="bg-ink py-9 text-on-ink md:py-14">
      <Container>
        <SectionHeading
          id="approach-title"
          eyebrow={approach.eyebrow}
          title={approach.title}
          flag={approach.flag}
          tone="reverse"
        />

        <ol className="grid rounded-sm border border-line-dark sm:grid-cols-2 lg:grid-cols-4">
          {approach.steps.map((step, i) => (
            <li
              key={step.title}
              className="border-b border-line-dark p-4 last:border-b-0 sm:p-6 sm:odd:border-r lg:border-b-0 lg:border-r lg:last:border-r-0"
            >
              <p className="font-mono text-xs font-medium text-accent-light">{String(i + 1).padStart(2, "0")}</p>
              <h3 className="mt-2 text-[15px] leading-[1.3] font-semibold text-on-ink md:mt-3 md:text-[17px]">
                {step.title}
              </h3>
              <p className="mt-1.5 text-sm leading-[1.5] text-on-ink-muted">{step.body}</p>
            </li>
          ))}
        </ol>
      </Container>
    </section>
  );
}
