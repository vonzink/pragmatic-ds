/**
 * The six services. Shared by the header menu, home rows, footer and service pages.
 * Prices are the minimums set by the founder.
 */
export type Service = {
  num: string;
  slug: string;
  title: string;
  /** One line for the header menu. */
  short: string;
  blurb: string;
  badge?: string;
  items: [string, string, string];
  price: string;
};

export const services: Service[] = [
  {
    num: "01",
    slug: "sf-1408-pre-award-accounting",
    title: "SF 1408 pre-award readiness",
    short: "Checked against the criteria the auditor uses.",
    badge: "Most urgent",
    blurb:
      "A contracting officer has asked for a pre-award accounting system survey. We check your system against the same criteria the auditor will use and tell you exactly what to fix.",
    items: ["Criteria gap matrix", "Corrective action plan", "Mock survey walkthrough"],
    price: "Starting at $3,125",
  },
  {
    num: "02",
    slug: "dcaa-dcma-audit-support",
    title: "DCAA / DCMA audit support",
    short: "Notice triage, document map, response support.",
    blurb:
      "You have a notice. We triage it, map the documents they will ask for, and sit with you through the response.",
    items: ["Notice triage", "Document map", "Response support"],
    price: "Scoped individually",
  },
  {
    num: "03",
    slug: "incurred-cost-submissions",
    title: "Incurred cost submissions",
    short: "Schedules prepared, reconciled, ICE-ready.",
    blurb:
      "Due six months after your fiscal year end. We prepare the schedules, reconcile them, and get the package ready for the ICE model.",
    items: ["Schedule preparation", "Reconciliation", "Adequacy review"],
    price: "Starting at $5,000",
  },
  {
    num: "04",
    slug: "indirect-cost-rates",
    title: "Indirect cost rates",
    short: "Pools, bases and a written methodology.",
    blurb: "Pools, bases and a written methodology you can defend in a rate review.",
    items: ["Pool and base design", "Rate calculation", "Written methodology"],
    price: "Starting at $3,750",
  },
  {
    num: "05",
    slug: "post-award-accounting-systems",
    title: "Post-award accounting systems",
    short: "DFARS 252.242-7006 reviews and remediation.",
    blurb:
      "DFARS 252.242-7006 reviews and remediation plans for contractors who already hold cost-type work.",
    items: ["Criteria review", "Findings", "Remediation plan"],
    price: "Starting at $4,250",
  },
  {
    num: "06",
    slug: "labor-and-timekeeping",
    title: "Labor and timekeeping",
    short: "Policy assessment, control testing, training.",
    blurb: "The control DCAA tests first. Policy assessment, control testing and employee training.",
    items: ["Policy assessment", "Control testing", "Training session"],
    price: "Starting at $1,500",
  },
];

export const serviceHref = (s: Service) => `/${s.slug}/`;

export const selfCheckHref = "/resources/sf-1408-self-check/";
