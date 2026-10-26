import type { Faq } from "@/components/ui/Accordion";

/**
 * Long-form service page copy. Only SF 1408 has full copy from the design handoff;
 * the other services render the shorter template from `services.ts` until their copy is written.
 */
export type ServiceDetail = {
  eyebrow: string;
  h1: string;
  lead: string;
  chips: string[];
  fits: string[];
  evaluates: { intro: string; rows: { cite: string; text: string; href: string }[] };
  fails: string[];
  help: { phase: string; title: string; text: string }[];
  tier: { title: string; price: string; forLine: string; items: string[] };
  priceNote: string;
  faqTitle: string;
  faqs: Faq[];
  related: { label: string; href: string }[];
  regsCited: string;
  cta: { title: string; body: string };
};

export const serviceDetails: Record<string, ServiceDetail> = {
  "sf-1408-pre-award-accounting": {
    eyebrow: "SF 1408 · Pre-award accounting system survey",
    h1: "Pre-award survey requested? Here is what DCAA will check.",
    lead: "When a contracting officer requests an SF 1408 review, the auditor evaluates whether your accounting system is designed to handle a cost-type contract. We check the same criteria first.",
    chips: ["24 yrs federal contract audit", "20 yrs DCAA", "Starting at [$2,750]", "Reply within one business day"],
    fits: [
      "You are bidding your first cost-type contract and the solicitation says the accounting system will be reviewed before award.",
      "A contracting officer or DCAA has already requested the survey and given you a date.",
      "You have 3 to 50 employees and run a general-purpose accounting system such as QuickBooks, Xero or Sage that was not set up with government cost accounting in mind.",
    ],
    evaluates: {
      intro: "The survey is a design review, not a transaction audit. These are the sources the auditor works from.",
      rows: [
        {
          cite: "SF 1408 Section II",
          text: "The evaluation checklist: GAAP basis, segregation of direct and indirect costs, accumulation by contract, timekeeping, labor distribution, interim cost determination, exclusion of unallowables.",
          href: "https://www.gsa.gov/forms-library/preaward-survey-prospective-contractor-accounting-system",
        },
        {
          cite: "FAR 16.301-3(a)(3)",
          text: "Why the survey exists: a cost-reimbursement contract may be used only when the contractor’s accounting system is adequate for determining costs applicable to the contract.",
          href: "https://www.acquisition.gov/far/16.301-3",
        },
        {
          cite: "DFARS 252.242-7006",
          text: "Post-award: the 18 accounting system criteria that apply once you hold the contract. The survey anticipates them.",
          href: "https://www.acquisition.gov/dfars/252.242-7006-accounting-system-administration.",
        },
        {
          cite: "FAR Part 31",
          text: "Allowability: the cost principles your system has to be able to apply to exclude unallowable costs from billings.",
          href: "https://www.ecfr.gov/current/title-48/chapter-1/subchapter-E/part-31",
        },
      ],
    },
    fails: [
      "Timekeeping that does not tie labor hours to a cost objective at the time the work is performed.",
      "No labor distribution: payroll is booked in total rather than charged to contracts and indirect accounts.",
      "Direct and indirect costs not segregated in the chart of accounts.",
      "An indirect allocation method that exists in practice but is not written down.",
      "Unallowable costs not identified and excluded in the general ledger.",
      "No monthly posting discipline, so interim contract costs cannot be determined.",
    ],
    help: [
      { phase: "Before", title: "Criteria gap matrix", text: "Each SF 1408 criterion, your current state, and the evidence the auditor will want." },
      { phase: "During", title: "Mock survey walkthrough", text: "We run the questions the way the auditor runs them, so the real session has no surprises." },
      { phase: "Findings", title: "Written gap report", text: "Plain-English findings, ranked by what will stop an award." },
      { phase: "After", title: "Corrective action plan and re-check", text: "Owner, step and date for each gap, then a second pass once the work is done." },
    ],
    tier: {
      title: "SF 1408 readiness review",
      price: "[$2,750]",
      forLine: "For first-time cost-type bidders with an existing accounting system.",
      items: [
        "Criteria gap matrix against SF 1408 Section II",
        "Mock survey walkthrough (remote, 90 minutes)",
        "Written gap report",
        "Corrective action plan",
        "One re-check after remediation",
      ],
    },
    priceNote: "Starting prices apply to defined scope. Audit support and remediation beyond the plan are scoped individually.",
    faqTitle: "SF 1408 questions.",
    faqs: [
      {
        q: "Who performs the survey, DCAA or the contracting officer?",
        a: "Usually DCAA at the contracting officer’s request, sometimes DCMA. The criteria are the same either way.",
      },
      {
        q: "Does our system have to be “DCAA-approved” software?",
        a: "No such thing exists. The auditor evaluates how your system is designed and operated, not the brand.",
      },
      {
        q: "How long does the readiness review take?",
        a: "Typically one to two weeks from intake to written report, depending on how quickly we receive documents.",
      },
      {
        q: "What if we fail the survey?",
        a: "The auditor reports the system as not acceptable, or acceptable with corrections. The contracting officer decides what happens next. Our plan is built to close gaps before that point.",
      },
    ],
    related: [
      { label: "Labor and timekeeping", href: "/labor-and-timekeeping/" },
      { label: "Indirect cost rates", href: "/indirect-cost-rates/" },
      { label: "Post-award accounting systems", href: "/post-award-accounting-systems/" },
      { label: "SF 1408 self-check tool", href: "/resources/sf-1408-self-check/" },
    ],
    regsCited: "SF 1408 Section II · FAR 16.301-3 · DFARS 252.242-7006 · FAR Part 31",
    cta: { title: "Survey request in hand?", body: "Send the request and your deadline. We reply within one business day." },
  },
};
