import { Container } from "@/components/ui/Container";
import { about } from "@/content/home";

/** Bone band: short company story left, the three principles right (Guide §4 "WhyPds"). */
export function AboutSection() {
  return (
    <section id="about" aria-labelledby="about-title" className="border-t border-line py-9 md:py-14">
      <Container className="grid gap-10 lg:grid-cols-2 lg:gap-14">
        <div>
          <p className="eyebrow mb-3 text-muted">{about.eyebrow}</p>
          <h2 id="about-title" className="text-2xl leading-[1.1] font-semibold tracking-[-0.02em] md:text-[30px]">
            {about.title}
          </h2>
          <div className="mt-5 flex max-w-lead flex-col gap-4 leading-[1.6]">
            {about.paragraphs.map((p) => (
              <p key={p}>{p}</p>
            ))}
          </div>
        </div>

        <dl className="self-start rounded-sm border border-line bg-surface-raised">
          {about.principles.map((item, i) => (
            <div
              key={item.title}
              className="grid grid-cols-[32px_1fr] gap-x-4 border-b border-line-soft px-5 py-5 last:border-b-0 md:grid-cols-[48px_1fr]"
            >
              <span className="pt-0.5 font-mono text-xs font-medium text-accent md:text-[13px]">
                {String(i + 1).padStart(2, "0")}
              </span>
              <div>
                <dt className="text-[17px] font-semibold text-ink">{item.title}</dt>
                <dd className="mt-1 text-[15px] leading-[1.55]">{item.body}</dd>
              </div>
            </div>
          ))}
        </dl>
      </Container>
    </section>
  );
}
