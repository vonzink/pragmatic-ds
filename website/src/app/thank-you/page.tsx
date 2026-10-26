import type { Metadata } from "next";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { InnerHero } from "@/components/ui/blocks";
import { site } from "@/config/site";
import { selfCheckHref } from "@/content/services";

export const metadata: Metadata = {
  title: "Request sent",
  robots: { index: false },
};

const steps = [
  ["We reply within one business day", `From ${site.contact.email}. Check spam if you do not see it.`],
  ["We triage on a short call", "Twenty to thirty minutes. Have the request or notice in front of you."],
  [
    "You get a scoped proposal within three business days",
    "Fixed scope and price where the work allows it. If we are not the right fit, we say so and point you elsewhere.",
  ],
];

export default function ThankYouPage() {
  return (
    <>
      <InnerHero eyebrow="Request sent" title="Received." lead="Here is what happens next." />
      <section className="band bg-surface-raised">
        <div className="mx-auto max-w-content">
          <div className="max-w-[720px]">
            <div className="border-t border-ink">
              {steps.map(([title, body], i) => (
                <div key={title} className="grid grid-cols-[40px_1fr] gap-3 border-b border-line py-5">
                  <span className="numeral pt-1">{String(i + 1).padStart(2, "0")}</span>
                  <div>
                    <div className="text-xl leading-[1.3] font-semibold text-ink">{title}</div>
                    <p className="mt-1.5 text-[15px] leading-[1.6]">{body}</p>
                  </div>
                </div>
              ))}
            </div>
            <div className="mt-7 flex flex-wrap gap-3">
              <Button href={selfCheckHref} variant="outline" stack>
                Run the SF 1408 self-check meanwhile
              </Button>
              <Link
                href="/"
                className="inline-flex h-12 w-full items-center justify-center px-[22px] text-[15px] font-semibold text-accent hover:underline md:w-auto"
              >
                Back to home
              </Link>
            </div>
            <p className="disclosure mt-6">
              Sending a request does not create a client relationship. PDS provides advisory services and is not
              affiliated with any federal agency.
            </p>
          </div>
        </div>
      </section>
    </>
  );
}
