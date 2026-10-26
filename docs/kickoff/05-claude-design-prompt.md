# 05 — Claude Design prompt: pragmaticds.com

Status: Approved as the Claude Design brief (Zach, 2026-10-26). Mockup sign-off pending; record it in 05-acceptance.md §1 when the frames are reviewed.
Date: 2026-10-26
Use: paste everything below the line into Claude Design as the brief. Bracketed items `[like this]` are placeholders Willy must supply; keep them bracketed in the mock so nobody mistakes them for facts. Companion: `05-acceptance.md` (what the mock must let us verify).

---

## The brief

Design a marketing website for **Pragmatic Defense Solutions LLC** (pragmaticds.com), a founder-led, veteran-owned advisory practice in government contract accounting and compliance. Desktop 1280 and mobile 375 for every page listed. This is a redesign of a live single-page site; nothing on the current site needs to be preserved.

### Who we are

The founder, **William Austin, CPA (Colorado)**, spent 24 years auditing federal contractors from the government's side: 20 years at the Defense Contract Audit Agency (DCAA) in Denver as an Auditor, Incurred Cost Technical Specialist, Supervisory Auditor and GAGAS Quality Assurance Auditor, including audit tours in Iraq and Afghanistan for DCAA and the State Department; two years as a GAGAS quality assurance and policy auditor at the VA Office of Inspector General; two years in the Department of Energy OIG Incurred Cost Audit Division. He audited incurred cost claims, forward pricing and cost proposals, earned value management, cost accounting practices and business systems, and works daily in the FAR, DFARS, DEAR and CAS. He is a U.S. Army veteran who served in combat during Desert Storm. Co-owner **Brook Zink** brings 15+ years running finance, HR and operations for growing companies.

PDS helps small and midsize federal contractors (roughly 3 to 50 employees) understand, document and demonstrate compliant contract accounting practices, working alongside the contractor's existing accountant or CPA. **Advisory only.** PDS assesses, advises, prepares and supports. It does not implement or configure accounting software, and it does not do tax, bookkeeping, payroll or general management consulting.

### Who the site is for

Primary: a contractor's founder, CFO or controller who has just received one of these and is on a 7 to 30 day clock:
1. A contracting officer's request for a **pre-award accounting system survey (SF 1408)** on their first cost-type bid.
2. An **incurred cost submission** due six months after fiscal year end, or indirect rates that need to be set or defended.
3. A **DCAA or DCMA notice**: audit entrance, questioned costs, labor floor check, business system review.

Secondary: CPAs, GovCon attorneys and APEX Accelerator counselors who refer clients and need to know PDS will not step on their relationship.

These visitors are competent, stressed and skeptical of marketing. They want to know, fast: what will be checked, whether this person has actually sat on the auditor's side of the table, exactly what they get, roughly what it costs, and what happens in the next 24 hours after they reach out.

### Voice and claims rules (non-negotiable)

- Plain-spoken, competent, calm. Short sentences. No hype, no fear-mongering, no military theatrics.
- Say **"advisory services"**, never "consulting." Say "readiness review," "audit support," "built to DCAA criteria."
- Never promise a contract award, an adequate determination, audit acceptance, or anything "DCAA-certified" or "DCAA-approved." Never imply DCAA, DCMA, VA or DOE endorsement. No agency seals.
- Ownership wording is **"veteran-owned"** and "founded by a disabled veteran." Do not show an SDVOSB badge or the words "service-disabled veteran-owned" anywhere.
- Prices are "starting at" figures for fixed-scope work; audit support is "scoped individually."
- Every tool and every service page carries a short disclosure: educational, not a determination of adequacy, not legal advice, not affiliated with any agency.

### Visual system (fixed; do not introduce new colors, fonts or effects)

**Principles.** Text-forward and tabular: rules, tables and numbered rows do the work that imagery does elsewhere. One accent per view. Bone is the page, white is the card, exactly one graphite band per page. 1px graphite rules structure the page. Disclosures are content, never below 13px.

**Color.**
- Graphite `#16181A`: headings, primary text, the single graphite band, rules, the header button. Hover `#2A2D31`.
- Bone `#F4F2ED`: page ground, header, hero, footer.
- White `#FFFFFF`: content bands, info cards, tables.
- Lines: `#D8D4CB` card borders and row dividers; `#EAE7E0` inside white cards; `#3A3E43` borders on graphite.
- Body `#3F4447`; Slate `#5A5F5F` for eyebrows, labels, disclosures.
- Defense Blue `#1F5EA8` is the only accent: hero CTA, row numerals, stat values, badges, links. Hover `#1A4F8D`. Badge background `#E6EEF7`. Never a section background.
- Blue Light `#A9CCE9` only on graphite: eyebrows and numerals inside the graphite band.
- Error `#B3261E`.

**Type.** IBM Plex Sans (400, 500, 600) for everything including display; IBM Plex Mono 500 for eyebrows, numerals, badges, labels, prices in tables. H1 50/1.08/-0.025em/600 (mobile 32); inner-page H1 44 (mobile 30); H2 30/1.1 (mobile 24); H3 20 in service rows, 17 in cards; lead 18/1.55 in body color (mobile 16); body 16/1.6, 15 in rows and cards; table and list text 14/1.7; disclosure 13/1.55 slate; eyebrow mono 12/.14em uppercase; badge mono 10.5 uppercase; row numeral mono 13 accent; stat 40/600 accent; nav 14/500; button 15/600.

**Layout.** 4px base. Section padding 56 desktop (hero 72 top), 36 mobile. Gutter 40 desktop, 20 mobile. Max width 1200; article measure 720; lead measure 600. Radius 4 everywhere. No shadows except the header dropdown. Breakpoints 640/768/1024/1280.

**Mark.** Regular hexagon, 3px graphite stroke, the top-right two edges overdrawn in 4px Defense Blue, bold "PD" centered. On graphite the stroke and letters go white, blue stays. Wordmark "Pragmatic Defense Solutions" 15/600 with "LLC" in 400 slate. Tagline "PEOPLE / STRATEGY / IMPACT" in the utility bar.

**Imagery.** None. No hero photo, no stock, no icons in primary layout. One 72px founder headshot on Home (Why PDS) and About. Not in uniform, neutral background.

**Motion.** Hover darkens buttons, underlines links, tints rows bone, gives cards a graphite border. No lift. Focus ring 2px accent, 2px offset. Accordion plus rotates 45°. All motion off under reduced motion.

### Global elements

**Header (sticky).** Utility bar 30px bone: left "PEOPLE / STRATEGY / IMPACT", right "LINKEDIN · [info@pragmaticds.com] · [(XXX) XXX-XXXX]" (hidden on mobile; phone and LinkedIn render only when supplied). Main bar 64px bone with 1px graphite bottom rule: mark + wordmark left; right nav "Services ▾ · Pricing · About · Resources ▾ · Contact" then a **graphite** button "Book a consult." Services dropdown: full-width white panel listing the six service pages with one-line blurbs and "All services →". Mobile: 58px bar, hamburger, full-height bone sheet with accordion groups, "Book a consult" pinned at bottom.

**Footer.** Bone, 1px graphite top rule (dropped under a CtaBand). Four columns: brand (wordmark, one line, mono "VETERAN-OWNED" in accent); SERVICES (six links); COMPANY (About, Pricing, Resources, FAQ, Contact, Site map); CONTACT (email, [phone], [mailing address], "Remote, nationwide · LinkedIn"). Legal strip: Privacy Policy · Terms of Use · Disclosures · Accessibility, then a one-paragraph disclosure, then © line.

**Disclosure strip text (use verbatim in mocks).** "Pragmatic Defense Solutions LLC provides advisory services. We are not affiliated with, and are not endorsed by, DCAA, DCMA or any federal agency. Nothing on this site is a determination of accounting system adequacy or legal advice."

### Pages to design

#### 1. Home

Order, top to bottom: Header → Hero (bone) → Services (white, graphite top rule) → Why PDS (bone) → Common situations (the one graphite band) → FAQ + Next step (white) → Footer.

**Hero.** Grid: copy left (H1 max 760, lead max 600), AtAGlance table right (360px). Eyebrow: "GOVERNMENT CONTRACT ACCOUNTING & COMPLIANCE ADVISORY." H1 options (pick one, show the first): "Built by the auditor who used to check your books." / "We spent 24 years on DCAA's side of the table." / "Know what DCAA will check before DCAA checks it." Lead: "Pre-award surveys, incurred cost submissions, indirect rates and audit response for small and midsize federal contractors. Advisory services that work alongside your existing accountant." Primary CTA (Defense Blue): "Request a readiness review." Secondary (graphite outline): "Facing an audit deadline?"

**AtAGlance** (white card, mono "AT A GLANCE" header, label/value rows): Founded by — William Austin, CPA · Experience — 24 yrs federal contract audit, 20 at DCAA · We do — Assess, advise, prepare, support · We don't — Tax, bookkeeping, software setup · Where — Remote, nationwide (Denver-based) · Reply time — Within one business day.

**Services.** H2 "What we help with." Section flag right: "STARTING PRICES ON /PRICING." Six numbered rows (numeral · title + badge · blurb · 3-item plain list · "Explore →" + starting price):
01 SF 1408 pre-award readiness — badge "MOST URGENT" — "A contracting officer has asked for a pre-award accounting system survey. We check your system against the same criteria the auditor will use and tell you exactly what to fix." — Criteria gap matrix · Corrective action plan · Mock survey walkthrough — Starting at [$2,750].
02 DCAA / DCMA audit support — "You have a notice. We triage it, map the documents they will ask for, and sit with you through the response." — Notice triage · Document map · Response support — Scoped individually.
03 Incurred cost submissions — "Due six months after your fiscal year end. We prepare the schedules, reconcile them, and get the package ready for the ICE model." — Schedule preparation · Reconciliation · Adequacy review — Starting at [$4,500].
04 Indirect cost rates — "Pools, bases and a written methodology you can defend in a rate review." — Pool and base design · Rate calculation · Written methodology — Starting at [$3,250].
05 Post-award accounting systems — "DFARS 252.242-7006 reviews and remediation plans for contractors who already hold cost-type work." — Criteria review · Findings · Remediation plan — Starting at [$4,000].
06 Labor and timekeeping — "The control DCAA tests first. Policy assessment, control testing and employee training." — Policy assessment · Control testing · Training session — Starting at [$1,500].

**Why PDS.** Left: eyebrow "WHY A FORMER AUDITOR"; H2 "We know what the auditor is looking for because we were the auditor."; 16px bio paragraph (two or three sentences from the founder bio above); founder row (72px headshot, "William Austin, CPA", "Founder · 24 years federal contract audit"); disclosure note under it: "Roles and years are stated for background. Past government employment does not imply agency endorsement." Right: StatTable with three stats (24 — years federal contract audit · 20 — years at DCAA · 3 — agencies audited for: DCAA, VA OIG, DOE OIG) and a "HOW WE WORK" strip: 1 INTAKE · 2 DIAGNOSTIC · 3 FINDINGS · 4 REMEDIATION.

**Common situations** (graphite band, Blue Light numerals, H2 "Where clients usually start," flag "WHERE CLIENTS START"). Four cells written in the client's words:
01 "The CO wants a pre-award survey and we bid in three weeks."
02 "Our incurred cost submission is due and our books weren't set up for it."
03 "DCAA questioned our labor charging."
04 "We just won our first cost-type contract and our accountant has never done this."

**FAQ + Next step.** Left: H2 "Questions we get first," accordion with the first item open: "Are you a CPA firm?" ("William Austin is a licensed CPA in Colorado. PDS is an advisory practice, not a public accounting firm, and does not replace your CPA.") Then: "Can you guarantee we pass the survey?" ("No one can. We prepare you against the criteria the auditor uses and tell you plainly where you stand.") · "Do you set up QuickBooks or Unanet?" ("No. We tell you what the system has to do and work with your accountant or vendor on how.") · "How fast can you start?" ("We reply within one business day and can usually begin a diagnostic within a week.") · "What does it cost?" ("Starting prices for fixed-scope work are on the Pricing page. Audit support is scoped individually.") Right: NextStep card: eyebrow "NEXT STEP"; H2 "Tell us what you received."; body "A survey request, an audit notice, a deadline. Send the basics and we will reply within one business day."; primary "Request a readiness review"; secondary "Book a consult."

#### 2. Service page template, shown as /sf-1408-pre-award-accounting

Header → inner hero (bone; breadcrumb "Services / SF 1408 pre-award readiness"; eyebrow "SF 1408 · PRE-AWARD ACCOUNTING SYSTEM SURVEY"; H1 "Pre-award survey requested? Here is what DCAA will check."; lead "When a contracting officer requests an SF 1408 review, the auditor evaluates whether your accounting system is designed to handle a cost-type contract. We check the same criteria first."; CTAs "Request a readiness review" and "Run the self-check"; TrustChips "24 yrs federal contract audit · 20 yrs DCAA · Starting at [$2,750] · Reply within one business day"; 1px graphite bottom rule) → white article band with a sticky "On this page" rail (220px) and a 720px article:

1. **Who this fits.** Three short paragraphs: first cost-type bid; a CO or DCAA has requested the survey; 3 to 50 employees with a general-purpose accounting system.
2. **What the auditor evaluates.** RegCitation table (mono citation · what it covers): SF 1408 Section II · FAR 16.301-3(a)(3) · DFARS 252.242-7006 (post-award) · FAR Part 31 (allowability). Citations link to acquisition.gov and eCFR.
3. **Where contractors fail.** NumberedList, six rows: timekeeping that does not tie labor to cost objectives · no labor distribution · direct and indirect costs not segregated · indirect allocation method undocumented · unallowables not excluded · no monthly posting discipline.
4. **How we help.** HowWeHelp 2×2 table: BEFORE (criteria gap matrix) · DURING (mock survey walkthrough) · FINDINGS (written gap report) · AFTER (corrective action plan and re-check).
5. **Deliverables and price.** One PriceTierCard "SF 1408 readiness review" starting at [$2,750], "for" line "First-time cost-type bidders with an existing accounting system," inclusion list, button "Book a consult." Disclosure beneath: "Starting prices apply to defined scope. Audit support and remediation beyond the plan are scoped individually."
6. **FAQ.** Four items specific to SF 1408.
7. **Related.** Two-column bordered list: Labor and timekeeping · Indirect cost rates · Post-award accounting systems · SF 1408 self-check tool.
8. **LastReviewed** card: "LAST REVIEWED [DATE]", regulations cited, disclosure.
→ CtaBand (graphite): H2 "Survey request in hand?" body "Send the request and your deadline. We reply within one business day." primary "Request a readiness review," secondary "Run the self-check" → Footer.

#### 3. Tool page: /resources/sf-1408-self-check

Inner hero: eyebrow "FREE TOOL · EDUCATIONAL"; H1 "SF 1408 readiness self-check"; lead "Answer the criteria the auditor evaluates. Get a score, your gaps, and what the auditor will ask to see for each one. Nothing you enter is stored."; a 13px disclosure line directly under the lead.

Body, white band, two columns on desktop (questions 1fr, results 360px sticky), stacked on mobile:
- **Questions.** Fifteen rows, each a bordered white row with the criterion text, a mono reference ("SF 1408 II.2.e"), and a three-way segmented control Yes / No / Unsure (48px targets, graphite selected state, accent focus). Criteria (verify against the current form before build): accounting in accordance with GAAP · direct costs segregated from indirect · direct costs identified and accumulated by contract · logical, consistent method for allocating indirect costs · costs under general ledger control · timekeeping identifies labor by cost objective · labor distribution charges direct and indirect labor to cost objectives · interim (at least monthly) determination of contract costs · unallowable costs excluded · costs identified by contract line item and unit when required · preproduction costs segregated from production · financial information for limitation of cost and funds clauses · information to support progress payment requests · reliable data for pricing follow-on work · system is in full operation.
- **Results panel.** White card: mono eyebrow "YOUR RESULT"; a large score ("11 of 15 criteria ready," 40px accent); a two-level gap list: "Critical gaps" (any No on timekeeping, labor distribution, segregation, allocation method, GL control) and "Needs documentation" (any Unsure); each gap row shows the criterion, the reference, and "What the auditor will ask for" in 14px; then primary "Send these results with a readiness review request" (carries a text summary into the contact form) and secondary "Print this page." The disclosure sits at the bottom of the card. Show the results panel in both the empty state ("Answer the criteria to see your result") and a filled state matching: Yes on everything except Unsure on allocation method, No on timekeeping, No on labor distribution, Unsure on unallowables, Unsure on limitation of cost, N/A on preproduction and progress payments.

#### 4. Pricing

Inner hero: H1 "Prices are on this page." lead "Starting prices for fixed-scope work. Audit support is scoped individually because no two notices are alike." → three PriceTierCards on white (SF 1408 readiness review [$2,750] · Indirect rate structure [$3,250] · Post-award system review [$4,000]; middle tier badge "MOST COMMON") → fixed-fee table (PROJECT · STARTING FEE · WHAT IT COVERS) listing all six services plus "Monthly compliance advisory · from [$750]/month" → response commitments as a three-cell bordered table (Reply within one business day · Scoped proposal within three business days · Diagnostic typically starts within one week) → disclosure → FAQ (three items) → CtaBand → Footer.

#### 5. About

Inner hero with AtAGlance right column (SERVE · WORK · WHERE · SYSTEMS WE KNOW · REPLY TIME) → "Why PDS exists" two-column → Founder timeline: white bordered table, rows year range (mono accent) · title (600) · one line; rows: 1990–1991 U.S. Army, Desert Storm · [YYYY] B.S. Public Accounting, Mesa State College · [YYYY–YYYY] DCAA Denver: Auditor → Incurred Cost Technical Specialist → Supervisory Auditor → GAGAS QA Auditor; tours in Iraq and Afghanistan · [YYYY–YYYY] VA OIG, GAGAS QA / Policy Auditor · [YYYY–YYYY] DOE OIG, Incurred Cost Audit Division · [YYYY] Founded Pragmatic Defense Solutions. 72px headshot in the section header; disclosure under it → Brook Zink row (name, "Co-owner · Operations," two sentences) → "What we do and don't do" as a two-column bordered table (DO: assess, advise, prepare, support, train / DON'T: tax, bookkeeping, payroll, software implementation, general consulting) → "For CPAs and attorneys" short section with a "Refer a client →" link → four principles as a bordered four-column table (People · Strategy · Impact · Plain English) → disclosures → CtaBand → Footer.

#### 6. Resources hub

Inner hero: H1 "Tools, guides and the official sources." → "Tools" as ServiceRows-style rows (numeral · title · one line · "Open →"): SF 1408 self-check · Indirect rate and wrap rate calculator · Incurred cost submission deadline tracker · "Which audit is this?" triage · Audit data-request checklist · GovCon accounting glossary → "Guides" rows (title, topic eyebrow, "Last reviewed," "Read →"), with a downloadable "SF 1408 preparation guide" row first → "Official sources" as a RegCitation-style table with one-line "why it matters": DCAA Checklists & Tools · ICE model · DCAA Contract Audit Manual · FAR Part 31 · FAR 52.216-7 · DFARS 252.242-7006 · CAS (48 CFR 99) · DCMA · SAM.gov · SBA VetCert · APEX Accelerators → AuthorBox ("Reviewed by William Austin, CPA") → disclosure → CtaBand → Footer.

#### 7. Contact

Inner hero: H1 "What did you receive?" lead "A survey request, an audit notice, a deadline. Send the basics and we reply within one business day." → white band, two columns (left 1fr, right 1.2fr): left = "What to have ready" bordered checklist (the request or notice · contract type and agency · accounting platform · fiscal year end · deadline date) + "Other ways to reach us" rows (email, [phone], LinkedIn) + a 13px note "Vendors and sales: email only, please."; right = BookingEmbed card (16:10 placeholder, disclosure line above) then ContactForm card: Name · Company · Email · Phone (optional) in a 2×2 grid; "What did you receive?" radio group (Pre-award survey request · Audit or review notice · Incurred cost deadline · Rate question · Referral · Something else); contract type checkboxes (CPFF · T&M · FFP with progress payments · Grant/cooperative agreement · Not sure); accounting platform select; **deadline date**; message textarea; disclosure; privacy link; marketing opt-in unchecked; primary "Send" full width on mobile. Also show the **deadline fast-lane state**: when "Facing an audit deadline?" brought the visitor here, the deadline and request type are pre-filled and a bordered banner above the form reads "Under 14 days to your deadline: book a call directly" with the booking card raised above the form. Thank-you page: inner hero "Received." + three numbered rows (We reply within one business day · We triage on a short call · You get a scoped proposal within three business days).

### Mobile

375px for every page. Single column; buttons full width, stacked, 48px tall; hit targets 44px; service rows collapse to numeral + title + blurb + "Explore →"; the graphite band becomes a single-column list; the self-check results panel moves below the questions with a sticky "See result" bar; the On-this-page rail becomes a collapsible "On this page" at the top of the article. No horizontal scroll anywhere.

### What to hand back

Desktop and mobile frames for the seven pages above, the header dropdown and mobile menu states, the self-check empty and filled states, the contact fast-lane state, and the thank-you page. Keep all bracketed placeholders bracketed. Use the token names and component names from this brief so the build can map them one to one.
