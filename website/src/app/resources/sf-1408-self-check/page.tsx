import type { Metadata } from "next";
import { SelfCheck } from "@/components/SelfCheck";
import { InnerHero } from "@/components/ui/blocks";

export const metadata: Metadata = {
  title: "SF 1408 readiness self-check",
  description:
    "Free educational tool: answer the SF 1408 pre-award survey criteria and see your gaps and what the auditor will ask for. Nothing is stored.",
  alternates: { canonical: "/resources/sf-1408-self-check/" },
};

export default function SelfCheckPage() {
  return (
    <>
      <InnerHero
        eyebrow="Free tool · Educational"
        title="SF 1408 readiness self-check"
        lead="Answer the criteria the auditor evaluates. Get a score, your gaps, and what the auditor will ask to see for each one. Nothing you enter is stored."
      >
        <p className="disclosure mt-3.5 max-w-[720px]">
          Educational only. This is not a determination of accounting system adequacy or legal advice, and Pragmatic
          Defense Solutions LLC is not affiliated with DCAA, DCMA or any federal agency. Criteria paraphrase SF 1408
          Section II; verify against the current form.
        </p>
      </InnerHero>
      <SelfCheck />
    </>
  );
}
