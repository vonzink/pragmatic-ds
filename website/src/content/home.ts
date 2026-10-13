/**
 * Home page copy. Kept apart from the components so wording can change
 * without touching layout code.
 *
 * Only publish facts that are true and provable.
 */

import { site } from "@/config/site";

export const hero = {
  eyebrow: "Government consulting & capture support",
  title: "Mission focused. Measurable outcomes.",
  lead: "Veteran-owned government consulting and capture support. We help teams pursue the right opportunities, navigate contract requirements, and deliver the work.",
  secondaryCta: { label: "See our services", href: "/#services" },
};

export const atAGlance: { label: string; value: string }[] = [
  { label: "Ownership", value: site.ownership },
  ...site.owners.map((owner) => ({ label: owner.role, value: owner.name })),
  { label: "Focus", value: "Federal & DoD" },
  { label: "Where", value: "Remote, nationwide" },
];

export type Service = {
  title: string;
  summary: string;
  includes: string[];
};

export const services: Service[] = [
  {
    title: "Strategy & Capture",
    summary:
      "Find the opportunities that fit, decide early which to pursue, and build a capture plan that positions you to win.",
    includes: ["Opportunity qualification", "Capture planning", "Competitive assessment", "Bid / no-bid support"],
  },
  {
    title: "Program Support",
    summary:
      "Hands-on help running programs after award, so schedules, deliverables, and stakeholders stay on track.",
    includes: ["Program management", "Schedule & deliverable tracking", "Stakeholder reporting", "Transition support"],
  },
  {
    title: "Compliance & Risk",
    summary:
      "Understand the requirements that apply to your contracts and put practical controls in place before they become problems.",
    includes: ["Requirements review", "Risk identification", "Process documentation", "Readiness reviews"],
  },
  {
    title: "Operational Impact",
    summary:
      "Measure what matters and improve how the work gets done, with clear metrics leadership can act on.",
    includes: ["Performance metrics", "Process improvement", "Leadership reporting", "Lessons learned"],
  },
];

export const approach = {
  eyebrow: "How we work",
  title: "A plain, repeatable process.",
  flag: "People / Strategy / Impact",
  steps: [
    { title: "Assess", body: "Learn the mission, the contract, and where things stand today." },
    { title: "Plan", body: "Agree on priorities, owners, and what success looks like." },
    { title: "Execute", body: "Do the work alongside your team, not from the sidelines." },
    { title: "Measure", body: "Report results against the plan and adjust as needed." },
  ],
};

export const about = {
  eyebrow: "About PDS",
  title: "The people behind PDS.",
  paragraphs: [
    "Pragmatic Defense Solutions is a veteran-owned company led by William Austin, its primary owner and a disabled veteran, alongside co-owner Brook Zink.",
    "Our work spans strategy and capture, program support, compliance and risk, and operational improvement for government contractors.",
    "We keep things pragmatic: clear advice, honest timelines, and work that holds up after we leave.",
  ],
  principles: [
    { title: "People", body: "Strong teams deliver strong programs. We invest in the people doing the work." },
    { title: "Strategy", body: "Every recommendation ties back to the mission and the contract." },
    { title: "Impact", body: "We measure success by outcomes, not activity." },
  ],
};

export const contact = {
  eyebrow: "Next step",
  title: "Tell us what's on your plate.",
  body: "Send a short note about your program or opportunity. We'll reply to set up a short introductory call.",
};
