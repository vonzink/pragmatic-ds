import { Container } from "@/components/ui/Container";
import { SectionHeading } from "@/components/ui/SectionHeading";
import { services } from "@/content/home";

const numeral = (i: number) => String(i + 1).padStart(2, "0");

/**
 * Numbered service rows on a white band (Guide §4 "ServiceRows").
 * Rows aren't links yet because service pages don't exist. When they do,
 * wrap each row in a single <a> with the title as its accessible name.
 */
export function ServiceRows() {
  return (
    <section id="services" aria-labelledby="services-title" className="border-t border-ink bg-surface-raised py-9 md:py-14">
      <Container>
        <SectionHeading id="services-title" eyebrow="Key services" title="What we do." flag="Four ways we help" />

        <ol className="border-t border-ink">
          {services.map((service, i) => (
            <li
              key={service.title}
              className="grid grid-cols-[32px_1fr] gap-x-4 border-b border-line py-5 md:grid-cols-[40px_1fr_1fr] md:gap-x-6 md:py-[26px] lg:grid-cols-[48px_300px_1fr_240px]"
            >
              <span className="pt-1 font-mono text-xs font-medium text-accent md:text-[13px]">{numeral(i)}</span>
              <h3 className="text-[17px] leading-[1.2] font-semibold md:text-xl">{service.title}</h3>
              <p className="col-start-2 mt-2 text-sm leading-[1.55] md:col-start-auto md:mt-0 md:text-[15px]">
                {service.summary}
              </p>
              <ul className="hidden text-sm leading-[1.7] text-ink md:col-start-3 md:mt-3 md:block lg:col-start-auto lg:mt-0">
                {service.includes.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </li>
          ))}
        </ol>
      </Container>
    </section>
  );
}
