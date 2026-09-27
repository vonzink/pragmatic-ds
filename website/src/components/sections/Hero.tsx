import { Button } from "@/components/ui/Button";
import { Container } from "@/components/ui/Container";
import { primaryCta } from "@/config/site";
import { atAGlance, hero } from "@/content/home";
import { AtAGlance } from "./AtAGlance";

/** Home hero: headline + CTAs on the left, the at-a-glance facts on the right (Guide §4). */
export function Hero() {
  return (
    <section aria-labelledby="hero-title" className="pt-9 pb-9 md:pt-[72px] md:pb-14">
      <Container className="grid gap-10 lg:grid-cols-[1fr_360px] lg:items-end lg:gap-14">
        <div>
          <p className="eyebrow mb-4 text-muted">{hero.eyebrow}</p>
          <h1
            id="hero-title"
            className="max-w-[760px] text-[32px] leading-[1.08] font-semibold tracking-[-0.025em] md:text-[50px]"
          >
            {hero.title}
          </h1>
          <p className="mt-5 max-w-lead text-base leading-[1.55] text-pretty md:text-lg">{hero.lead}</p>
          <div className="mt-8 flex flex-col gap-2.5 sm:flex-row sm:gap-3">
            <Button href={primaryCta.href}>{primaryCta.label}</Button>
            <Button href={hero.secondaryCta.href} variant="secondary">
              {hero.secondaryCta.label}
            </Button>
          </div>
        </div>

        <AtAGlance rows={atAGlance} />
      </Container>
    </section>
  );
}
