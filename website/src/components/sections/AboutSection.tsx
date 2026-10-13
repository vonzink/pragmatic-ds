import Image from "next/image";
import { Container } from "@/components/ui/Container";
import { about } from "@/content/home";
import { site } from "@/config/site";

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
          <div className="mt-7 border-t border-ink">
            {site.owners.map((owner) => (
              <div key={owner.name} className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1 border-b border-line py-4">
                <h3 className="text-lg font-semibold">{owner.name}</h3>
                <p className="text-sm text-muted">
                  {owner.role}
                  {owner.background && <span>{` · ${owner.background}`}</span>}
                </p>
              </div>
            ))}
          </div>
        </div>

        <div className="self-start">
          <dl className="rounded-sm border border-line bg-surface-raised">
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
          <Image
            src="/brand/sba-service-disabled-veteran-owned-certified.png"
            alt="SBA Service-Disabled Veteran-Owned Certified"
            width={1210}
            height={1513}
            className="mx-auto mt-7 h-auto w-44"
          />
        </div>
      </Container>
    </section>
  );
}
