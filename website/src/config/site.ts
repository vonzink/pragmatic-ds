/**
 * Company-wide facts used across the site (header, footer, metadata).
 * Publish confirmed company facts only; leave optional contact details empty until supplied.
 */
export const site = {
  name: "Pragmatic Defense Solutions",
  legalName: "Pragmatic Defense Solutions LLC",
  shortName: "PDS",
  url: "https://pragmaticds.com",
  tagline: "People / Strategy / Impact",
  description:
    "Government contract accounting and compliance advisory for small and midsize federal contractors. Veteran-owned, founded by a former DCAA auditor.",
  contact: {
    email: "info@pragmaticds.com",
    /** Display format, e.g. "(303) 555-0100". Hidden on the site while empty. */
    phone: "",
    /** Full company LinkedIn URL. Hidden on the site while empty. */
    linkedin: "",
    /** Hidden on the site while empty. */
    mailingAddress: "",
    location: "Remote, nationwide",
  },
  /**
   * Where the contact form POSTs (JSON). Set NEXT_PUBLIC_FORM_ENDPOINT at build time
   * (e.g. a Formspree or API Gateway URL). While unset, the form opens a pre-filled email instead.
   */
  formEndpoint: process.env.NEXT_PUBLIC_FORM_ENDPOINT ?? "",
} as const;

/** Footer disclosure, verbatim from the design handoff. Do not edit without legal review. */
export const disclosure =
  "Pragmatic Defense Solutions LLC provides advisory services. We are not affiliated with, and are not endorsed by, DCAA, DCMA or any federal agency. Nothing on this site is a determination of accounting system adequacy or legal advice.";

export type NavItem = { label: string; href: string };

export const resourceLinks: (NavItem & { blurb: string })[] = [
  { label: "SF 1408 self-check", blurb: "Score your system against the survey criteria.", href: "/resources/sf-1408-self-check/" },
  { label: "Guides", blurb: "Preparation guides, reviewed and dated.", href: "/resources/#guides" },
  { label: "Official sources", blurb: "DCAA, FAR, DFARS, CAS and SAM.gov links.", href: "/resources/#sources" },
];

/** tel: link for a display-format phone number, e.g. "(303) 555-0100" -> "tel:3035550100". */
export const telHref = (phone: string) => `tel:${phone.replace(/[^\d+]/g, "")}`;
