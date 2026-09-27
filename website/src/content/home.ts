/**
 * Home page copy. Kept apart from the components so wording can change
 * without touching layout code.
 *
 * Anything in [brackets] is a placeholder that PDS needs to supply.
 * Only publish facts that are true and provable.
 */

export const hero = {
  eyebrow: "Government consulting & capture support",
  title: "Mission focused. Measurable outcomes.",
  lead: "Government consulting and capture support for a stronger, more resilient tomorrow. We help teams win the right work and deliver it well.",
  secondaryCta: { label: "See our services", href: "/#services" },
};

export const atAGlance: { label: string; value: string }[] = [
  { label: "Founded by", value: "[Founder name]" },
  { label: "Experience", value: "[XX] years" },
  { label: "Focus", value: "Federal & DoD" },
  { label: "Where", value: "Remote, nationwide" },
  { label: "Reply time", value: "[1 business day]" },
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
  title: "A proven partner for what's next.",
  paragraphs: [
    "[Short founder story: who started PDS, the background they bring, and why they started it. Two to three sentences.]",
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
