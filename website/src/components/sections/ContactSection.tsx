import { Button } from "@/components/ui/Button";
import { Container } from "@/components/ui/Container";
import { site, telHref } from "@/config/site";
import { contact } from "@/content/home";

/**
 * White band with the "Next step" card (Guide §4 "NextStep").
 * Email only for now; a real form comes once the backend exists.
 */
export function ContactSection() {
  return (
    <section id="contact" aria-labelledby="contact-title" className="border-t border-ink bg-surface-raised py-9 md:py-14">
      <Container>
        <div className="rounded-sm border border-line bg-surface p-6 md:p-9 lg:flex lg:items-end lg:justify-between lg:gap-14">
          <div className="max-w-[640px]">
            <p className="eyebrow mb-3 text-muted">{contact.eyebrow}</p>
            <h2 id="contact-title" className="text-[22px] leading-[1.15] font-semibold tracking-[-0.02em] md:text-[28px]">
              {contact.title}
            </h2>
            <p className="mt-3 text-[15px] leading-[1.6]">{contact.body}</p>
          </div>
          <div className="mt-6 flex flex-col gap-2.5 sm:flex-row sm:gap-3 lg:mt-0 lg:shrink-0">
            <Button href={`mailto:${site.contact.email}`}>Email {site.contact.email}</Button>
            {site.contact.phone && (
              <Button href={telHref(site.contact.phone)} variant="secondary">
                Call {site.contact.phone}
              </Button>
            )}
          </div>
        </div>
      </Container>
    </section>
  );
}
