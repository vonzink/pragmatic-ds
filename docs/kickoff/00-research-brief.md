# 00 — Research brief: website for William Austin's GovCon accounting practice

Status: Approved (Phase 0 scale check and decisions 1–6 approved by Zach, 2026-10-26; light kickoff path confirmed)
Date: 2026-10-26
Phase: Pre-kickoff research (feeds Phase 0 scale check and Phase 1 product brief)

## 1. What we are redesigning (decided 2026-10-26: pragmaticds.com)

Two companies share the same two owners and the same founder story, and both have a live site today:

| | Prosperous Logic, LLC | Pragmatic Defense Solutions, LLC |
|---|---|---|
| Live site | https://prosperouslogic.us (single static HTML page, Amplify manual deploy, repo `p-logic`) | https://pragmaticds.com (Next.js 16 static export, Amplify auto-build, repo `pragmatic-ds`) |
| Current positioning | "Five disciplines, one prime": general contracting, accounting, finance, network systems, software (NAICS 236220, 541211, 541611, 541512, 541511) | "Government consulting & capture support" (strategy & capture, program support, compliance & risk, operational impact) |
| Registration | SAM active, UEI Q8B1KBJ3QNZ3, CAGE 25XC9 (SAM.gov email to Brook Zink) | Unknown from files |
| Design assets | Teal/paper static design, Archivo + JetBrains Mono | Full brand board (graphite, Defense Blue, bone; hex "PD" mark), Design Build Guide "Direction 1b" (IBM Plex Sans/Mono), business plan + GTM plan |
| Plan documents | None | `PDS_Expanded_Business_Marketing_Plan.pdf`, `PDS_Go_To_Market_Strategy_SWOT_Budget.pdf` (Oct 19, 2026) |

The request ("website for William Austin, government accountant") plus the attached PDS brand board and PDS plans point at the **DCAA / contract-accounting advisory** offer. That offer is written up for PDS, but the SAM registration in the attached email is for Prosperous Logic, and this session is running in the Prosperous Logic repo. Neither live site currently sells the accounting offer. **Decision (Zach, 2026-10-26):** the accounting advisory site is Pragmatic Defense Solutions at pragmaticds.com, built in the `pragmatic-ds` repo (`website/`). Prosperous Logic keeps its own site and is out of scope here.

## 2. The company and the offer (from the PDS plans and bios)

**Founder credibility (the whole pitch).** William "Willy" Austin, CPA (Colorado). 24 years federal contract audit: 20 years DCAA Denver as Auditor, Incurred Cost Technical Specialist (GS-13), Supervisory Auditor, GAGAS QA Auditor, with tours in Iraq and Afghanistan; 2 years VA OIG; 2 years DOE OIG Incurred Cost Audit Division. U.S. Army Desert Storm combat veteran. Audited incurred cost claims, forward pricing, cost proposals, EVM, cost accounting practices, business systems. Fluent in FAR, DFARS, DEAR, CAS.

**Co-owner.** Brook Zink, 15+ years finance/HR/operations; relevant to the "working alongside your accounting team" delivery model and to operations credibility, not to the audit pitch.

**Positioning (owner-approved wording).** "Government Contract Accounting & Compliance **Advisory Services**" (not "Consulting"). Helps federal contractors understand, document and demonstrate compliant contract accounting practices, working alongside their existing accounting team. Narrow niche by design: no tax, no bookkeeping, no payroll, no generic management consulting.

**Core services.**

| Service | Deliverable |
|---|---|
| SF 1408 pre-award readiness | Criteria gap matrix, corrective actions, mock survey |
| Accounting system assessment | Direct/indirect segregation, job costing, controls review |
| Incurred cost submissions (ICE) | Schedules, reconciliation, readiness assistance |
| Indirect cost rates | Pools, allocation bases, documented methodology |
| DCAA / DCMA audit support | Data-request coordination, evidence review, response support |
| Post-award systems (DFARS 252.242-7006) | Reviews and remediation plans |
| Labor / timekeeping | Policy assessment, control testing, employee training |
| CAS and proposal support | Disclosures, pricing analyses, as qualified |

**Price hypotheses (not approved).** SF 1408 diagnostic $2,750; post-award review $4,000; first-year ICE support $4,500; indirect rate structure $3,250; timekeeping policy/training $1,500; audit support custom/retainer; monthly advisory $750–$2,000. Benchmark: M2 CPA publishes $2,500 / $3,500 / $4,000 / $3,000 / $1,200 for the equivalent services.

**Claims discipline (must be enforced on the site).** No promise of contract award, agency acceptance, or "DCAA-certified" anything. No SDVOSB claim until SBA approves (plan says application in progress; the `decals-and-icons` folder and a PDS commit contain SDVOSB badges, so the current status needs confirming before any badge ships). Feature the accurate bio without implying government endorsement. The PDS spec already has a content lint for this.

## 3. Target market and buying triggers

Not "every government contractor." Firms with an immediate regulatory event, a thin internal GovCon accounting function, and a decision-maker who can buy.

| Priority | Who | Trigger | Buyer | Where they are |
|---|---|---|---|---|
| 1 | New cost-type bidders, 3–50 staff | Contracting officer requests a pre-award survey (SF 1408); 7–30 day need | Founder, CFO, controller | High-intent search; GovCon CPAs; APEX Accelerators |
| 2 | Active cost-reimbursement contractors | Incurred cost submission due 6 months after FY end; indirect rate reset | Controller, finance lead | Search guides; CPA referrals; LinkedIn |
| 3 | Firms under audit or system review | DCAA/DCMA notice, questioned costs, labor charging | CFO, contracts director | Audit-focused search; attorney and CPA referrals |
| 4 | Growth-stage primes/subs | First cost-type award; new accounting requirements | CEO, controller | Prime/sub ecosystem; GovCon associations |

**Referral ecosystem (second audience for the site).** Independent CPAs, GovCon attorneys, APEX Accelerators, SBDCs, procurement advisors. They need "we do not replace your CPA" language and a one-page scope map.

What this means for the site: visitors arrive scared and on a deadline. The site has to (1) name their situation in their words, (2) prove this person has sat on the other side of the table, (3) tell them exactly what happens next and roughly what it costs, (4) make contact trivial.

## 4. Example sites and what to take from each

| Site | What they do well | What to borrow | What to avoid |
|---|---|---|---|
| [M2 CPA](https://m2cpa.com/) (direct competitor) | Hero: "The CPA firm that used to audit government contractors." Led by a former DCAA supervisory auditor. Mega-menu of 16 named GovCon services, published starting prices, Guides, Q&A, "Book a Call." | Named-service pages that match search intent; published pricing; founder-as-auditor hero. | They also sell tax and bookkeeping, which dilutes. Willy's story is deeper (24 yrs, GS-13 incurred cost specialist, OIG work) and the site should lean on that difference. |
| [Redstone GCI](https://www.redstonegci.com/) | Authority machine: blog, "GovCon Scoop" newsletter, training, webinars, large team of ex-government auditors. | A small, consistent content program (2 expert-reviewed articles/month per the plan); a newsletter capture. | Scale and breadth we don't have; don't imitate the 40-item service menu. |
| [ReliAscent](https://www.reliascent.com/) | Resources hub: white papers, FAR & DCAA videos, "What is DCAA compliance?" explainer, mock DCAA audits as a product. | A "What to expect from a DCAA audit" explainer per audit type; "mock survey" as a named product. | Combative "not like other firms" copy. |
| [Capital Edge](https://www.capitaledgeconsulting.com/) | Lifecycle framing: Start / Scale / Advance. | Organize by contractor stage or trigger, not by regulation. | Enterprise tone. |
| [Bureauify indirect rate calculator](https://bureauify.com/tools/indirect-rate-calculator) | Free calculator with worked wrap-rate example and benchmark multipliers; ranks for "indirect rate calculator." | Tool-as-lead-magnet pattern; worked example shown inline. | Unverified benchmark numbers presented as fact. |
| [Deltek SF 1408 guide](https://www.deltek.com/en/government-contracting/guide/dcaa/audit/sf-1408), [Unanet SF 1408 guide](https://info.unanet.com/sf1408-timekeeping-guide), [GRF CPA SF 1408 explainer](https://www.grfcpa.com/resource/sf-1408-checklist-explained-the-key-to-dcaa-compliance/) | Gated or ungated SF 1408 explainers as lead magnets. | A downloadable SF 1408 preparation guide (already in the PDS plan). | Gating everything; an auditor's version should be more credible than a software vendor's. |
| Official: DCAA Checklists & Tools (SF 1408 pre-award checklist, ICE model), [DOI IBC audit resources](https://ibc.doi.gov/node/57), [BDO ICE checklist](https://bdo.com/getmedia/9dcbc3f1-9314-4ef5-b6c5-2326ff955204/gc_incurred-cost-submission_checklist.pdf?ext=.pdf) | Primary sources contractors are told to use. | Link out to the official checklist and ICE model from every relevant page; "we prepare you against the same checklist DCAA uses." | Copying government content or implying affiliation. DCAA.mil blocks scripted fetches; link, don't scrape. |

Also noted in search, lower relevance: NeoSystems, Cherry Bekaert, Warren Averett (large firms); Gov-Con Solutions (Broomfield, CO, a local competitor worth a look); Financial Advisory Systems and Bay Business Group (small ex-DCAA practices); Left Brain Professionals and Cheryl Jefferson & Associates (small-business GovCon CPAs; sites didn't fetch, review manually).

## 5. Proposed site: pages, tools, links

### Pages (from the PDS plan, confirmed by competitor structure)

- Home: trigger-led hero, "at a glance" credibility table, four to six numbered service rows, common situations grid, "why an ex-auditor," process steps, FAQ, next step.
- Service pages (one per priority service): `/sf-1408-pre-award-accounting`, `/incurred-cost-submissions`, `/dcaa-dcma-audit-support`, `/indirect-cost-rates`, `/post-award-accounting-systems`, `/labor-timekeeping-compliance`. Template: problem and deadline → who it fits → deliverables → process → FAQ → short inquiry form. Each cites the governing regulation (FAR 31.2, FAR 52.216-7, DFARS 252.242-7006, CAS) and links the official checklist.
- Pricing: starting prices per fixed-scope service, "custom" for audit support, what's included, what's excluded.
- About: Willy's full bio, Brook's bio, ownership and certification facts as approved, "advisory, not a replacement for your CPA."
- Resources: tools (below), guides, FAQ, links.
- Contact: form with contract type, accounting platform, deadline date; booking embed; one-business-day response promise.
- Legal: privacy, terms, disclosures, accessibility.

### Tools (client-side, no backend, each one a lead capture and an SEO target)

1. **SF 1408 readiness self-check.** The checklist's evaluation criteria as yes/no/unsure questions; produces a readiness score and the specific gaps, maps each gap to what the auditor will ask for. CTA: "Get the gap matrix reviewed."
2. **Indirect rate and wrap rate calculator.** Fringe, overhead, G&A pools and bases → rates → fully burdened hourly rate; shows the math the way DCAA expects it allocated. Optional "what if" for a rate change.
3. **Incurred cost submission deadline tracker.** Enter fiscal year end → due date (6 months after FYE under FAR 52.216-7(d)(2)), countdown, and the preparation timeline working backward.
4. **"Which audit is this?" triage.** Pick the notice or request type (pre-award survey, post-award accounting system review, incurred cost audit, forward pricing, floor check / labor interview, business system review) → plain-English explanation, what they will ask for, typical timeline, what to do first.
5. **Audit data-request checklist generator.** Select audit type → printable list of documents to assemble. Pairs with tool 4.
6. **Provisional billing rate worksheet** (later; only if Willy confirms scope).
7. **GovCon accounting glossary** (ICE, PBR, FPRP/FPRA, CAS, TINA, allowability vs. allocability, floor check), each entry linking to the service that handles it.

Every tool carries the same disclosure: educational, not a determination of adequacy, not affiliated with DCAA.

### Link library (outbound, curated with one-line "why this matters")

DCAA Checklists & Tools (SF 1408 pre-award checklist, ICE model, adequacy checklists); DCAA Contract Audit Manual; FAR Part 31 and FAR 52.216-7 on eCFR; DFARS 252.242-7006 accounting system criteria; CAS (48 CFR 99); DCMA; SAM.gov; SBA VetCert; APEX Accelerators directory; SBIR.gov (if SBIR firms are in scope); Colorado PTAC/APEX.

### Content program

Downloadable SF 1408 preparation guide (lead magnet); two expert-reviewed articles per month on SF 1408, ICE, indirect rates, audit response; monthly 30-minute webinar; FAQ built from real triage calls. Author box on every article: "Reviewed by William Austin, CPA, former DCAA Supervisory Auditor."

### Conversion and operations

Primary CTA "Request a readiness review"; secondary "Facing an audit deadline?" fast lane that asks for the deadline date first. Form fields per the PDS Design Build Guide `ContactForm` (contract type checkboxes, accounting system, deadline, honeypot, opt-in). Booking embed. Response within one business day. CRM tracking of source, urgency, proposal conversion (plan §06). Analytics on tool use and form starts.

## 6. Design direction already decided (reuse, don't restart)

The PDS brand board and Design Build Guide (Direction 1b) are complete and consistent: graphite `#16181A`, Defense Blue `#1F5EA8` (one accent per view), Blue Light `#A9CCE9` (dark grounds only), bone `#F4F2ED` page ground, slate `#5A5F5F` body; IBM Plex Sans 400/500/600 + IBM Plex Mono 500; 4px radius, no shadows, 1px rules, compact tabular density, numbered service rows, hex "PD" mark. Tagline "People / Strategy / Impact"; primary message "Mission Focused. Measurable Outcomes." Components are already named (Hero, AtAGlance, ServiceRows, ScenarioGrid, StatTable, ProcessSteps, FaqAccordion, PriceTable, RegCitation, DisclosureNote, ContactForm, BookingEmbed).

If the answer to open question #1 is Prosperous Logic, the brand decision reopens (the current PL design is teal/paper and the name doesn't say "defense" or "accounting").

## 7. Phase 0 scale check (draft answers; confirm)

- **Users and roles.** Public visitors (contractor finance leads, founders, referrers). One internal role: whoever edits content. No logged-in users in slice 1. The "secure client portal" in the plan is a later, separate product thesis.
- **Sensitive data.** Contact form only (name, company, email, phone, deadline, free text). No financial data stored by the site; calculators run in the browser and store nothing. Form submissions need a real delivery path (the current PL site only drafts an email in the visitor's mail app).
- **Running parts.** One static site. Form delivery via a hosted form service or one small function. No database.
- **Lifetime and maintenance.** Years; maintained by Zach with Willy reviewing copy.
- **Deadline/budget.** Plan: homepage + first three landing pages by day 60, remaining pages by day 90; launch allowance $4k–$10k external, lower if built in-house.

Verdict: small project. Recommend the light path: Phase 1 (product brief with acceptance examples) and Phase 5 (mockup and acceptance), then build.

## 8. Draft prompt for Claude Design (pending answers to open questions)

> Design a marketing website for a founder-led government contract accounting advisory practice. The founder is a CPA with 24 years of federal contract audit experience, 20 of them at the Defense Contract Audit Agency (DCAA) as an auditor, incurred cost technical specialist, supervisory auditor and quality assurance auditor, plus OIG audit work at the VA and Department of Energy. He is an Army combat veteran. The practice helps small and midsize federal contractors (3–50 employees) prepare for pre-award accounting system surveys (SF 1408), incurred cost submissions, indirect rate design, post-award accounting system reviews, timekeeping compliance, and DCAA/DCMA audit response. It is advisory only: no tax, bookkeeping or payroll, and it works alongside the client's existing accountant.
>
> Audience: contractor founders, CFOs and controllers who have just received a pre-award survey request, an audit notice, or have an incurred cost submission due, and are on a 7–30 day clock. Secondary: CPAs and attorneys who refer.
>
> Voice: calm, precise, plain English, auditor's confidence without bravado. Never promise award or acceptance; never imply DCAA affiliation; "advisory services," not "consulting."
>
> Visual system (fixed): graphite #16181A, Defense Blue #1F5EA8 as the single accent, Blue Light #A9CCE9 on dark grounds only, bone #F4F2ED page ground, slate #5A5F5F body. IBM Plex Sans for everything, IBM Plex Mono for eyebrows, numerals and labels. 4px radius, 1px rules, no shadows, compact tabular density, numbered service rows. Hex "PD" mark. Tagline "People / Strategy / Impact."
>
> Pages to design: Home; one service page template (shown as the SF 1408 page); Pricing; About; Resources hub; one interactive tool page (SF 1408 readiness self-check with a score and gap list); Contact with a deadline-first form. Desktop 1280 and mobile 375.
>
> Home must show, in order: trigger-led hero with primary CTA "Request a readiness review" and secondary "Facing an audit deadline?"; an at-a-glance credibility table; numbered service rows; a "common situations" grid written in the client's words; a "why a former auditor" section; four process steps; FAQ; closing CTA. Every page carries a short disclosure strip.

## 9. Decisions and open questions

Decided 2026-10-26 (Zach):

1. Site: pragmaticds.com (`pragmatic-ds/website`). Prosperous Logic stays separate.
2. Launch service set: **SF 1408 readiness** is the flagship and the first page built. Audit support, incurred cost submissions and indirect rates follow as the next three service pages.
3. Scope: PDS **assesses and advises only**; it does not implement or configure accounting systems. No implementation pages or tools.
4. Certification claims at launch: **veteran-owned** and "disabled veteran founder" only. No SDVOSB badge or wording until SBA VetCert approval is confirmed in writing.
5. Pricing: publish **starting ("from") prices** for fixed-scope services; audit support is custom scope. Exact numbers need Willy's approval before publishing (plan figures are test prices).

6. Contact path: **hosted form plus booking**. A deadline-first form delivers to an inbox, and an embedded booking link is the fast lane. Phone number and LinkedIn URL still to be supplied by Willy (hidden while empty, as the site config already does).
7. Kickoff path: **light path** confirmed. Phase 1 product brief and Phase 5 mockup/acceptance, then build. No ownership map, ADRs or engineering baseline for this static site.

## Sources reviewed

Local: `pragmatic-ds/PDS_Expanded_Business_Marketing_Plan.pdf`, `pragmatic-ds/PDS_Go_To_Market_Strategy_SWOT_Budget.pdf`, `pragmatic-ds/# Pragmatic Defense Solutions — Design Build Guide.md`, `pragmatic-ds/website/src/content/home.ts`, `p-logic/Government/William Austin Bio.docx`, `p-logic/Government/Brook_Zink_Bio.docx`, `p-logic/README.md`, `p-logic/website/index.html`, live https://prosperouslogic.us and https://pragmaticds.com (Oct 26, 2026).

Web: [M2 CPA](https://m2cpa.com/) · [Redstone GCI](https://www.redstonegci.com/) · [Redstone consulting](https://redstonegci.com/consulting) · [ReliAscent](https://www.reliascent.com/) · [Capital Edge](https://www.capitaledgeconsulting.com/) · [Bureauify calculator](https://bureauify.com/tools/indirect-rate-calculator) · [EveryCalculators indirect rate](https://everycalculators.com/government-contract-indirect-rate-calculator.html) · [Deltek SF 1408](https://www.deltek.com/en/government-contracting/guide/dcaa/audit/sf-1408) · [Unanet SF 1408 guide](https://info.unanet.com/sf1408-timekeeping-guide) · [Unanet white paper](https://info.unanet.com/hubfs/Unanet%20White%20Paper%20SF1408.pdf) · [GRF CPA SF 1408](https://www.grfcpa.com/resource/sf-1408-checklist-explained-the-key-to-dcaa-compliance/) · [GRF indirect rates](https://www.grfcpa.com/resource/accurate-projection-of-indirect-rates-ensures-compliance-and-profitability/) · [DOI IBC audit resources](https://ibc.doi.gov/node/57) · [BDO ICE checklist](https://bdo.com/getmedia/9dcbc3f1-9314-4ef5-b6c5-2326ff955204/gc_incurred-cost-submission_checklist.pdf?ext=.pdf) · [Deltek ICE checklist](https://more.deltek.com/DCAA-ICE-Model-Submission-Checklist) · [NeoSystems DCAA](https://www.neosystemscorp.com/solutions-services/managed-accounting/dcaa-compliance/) · [Cherry Bekaert GovCon](https://www.cbh.com/services/advisory/government-contracting-consulting-services/) · [Warren Averett GovCon](https://warrenaverett.com/industries/government-contractors/) · [Gov-Con Solutions](https://www.govevents.com/orgs/1167/gov-con-solutions-inc) · [Beancount DCAA guide](https://beancount.io/blog/2026/05/12/dcaa-audit-compliance-government-contractors-timekeeping-indirect-cost-rates-sf-1408-pre-award-incurred-cost-submission-guide) · [Hyperbots SF 1408](https://www.hyperbots.com/glossary/sf-1408-checklist) · [NeoSystems SF 1408](https://www.neosystemscorp.com/form-sf-1408/)
