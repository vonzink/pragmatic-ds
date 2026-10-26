# 01 — Product brief: pragmaticds.com redesign

Status: Approved (Zach, 2026-10-26, as written; open questions 1–5 carried into Phase 5)
Date: 2026-10-26
Builds on: `00-research-brief.md` (Approved). Decisions there are not repeated unless they shape a requirement here.

## 1. Users and the decisions the site helps them make

| User | Situation | Decision the site must help them make |
|---|---|---|
| Contractor founder / CFO / controller, 3–50 staff | A contracting officer has requested a pre-award accounting system survey (SF 1408); 7–30 days to respond | "Is our accounting system going to pass, what are the gaps, and who can get us ready in time?" |
| Controller / finance lead at a cost-reimbursement contractor | Incurred cost submission due six months after fiscal year end; indirect rates need resetting | "What do we have to submit, by when, and do we need help?" |
| CFO / contracts director under review | DCAA or DCMA notice, questioned costs, labor charging questions | "What is this audit, what will they ask for, and who has been on the other side of it?" |
| Referrer: CPA, GovCon attorney, APEX Accelerator counselor | A client needs GovCon-specific help the referrer does not do | "Is this person credible, what exactly do they do and not do, and will they step on my relationship?" |
| Willy (owner) | Inbound inquiry arrives | "Is this a qualified lead, how urgent, and what do I quote?" |
| Zach (maintainer) | Copy, prices, facts change | "Can I change this safely without touching layout code?" |

## 2. First workflow: SF 1408 pre-award readiness inquiry

From the visitor's point of view:

1. Searches "SF 1408 pre-award survey help" (or is referred). Lands on `/sf-1408-pre-award-accounting` or Home.
2. Sees in the first screen: a headline in their words ("Pre-award survey requested? Here is what DCAA will check."), the founder's credential in one line (CPA, 24 years federal contract audit, 20 at DCAA), the primary CTA "Request a readiness review," and the secondary "Facing an audit deadline?"
3. Reads the service page: who it fits, the deliverables (criteria gap matrix, corrective actions, mock survey), the process (intake → diagnostic → scoped proposal → evidence review → written findings → remediation → closeout), the starting price, the FAQ, the regulation cited (SF 1408 Section II; DFARS 252.242-7006 for post-award), and a link to the official DCAA checklist.
4. Optionally runs the **SF 1408 readiness self-check**: answers the criteria as Yes / No / Unsure, gets a score, the list of gaps, and for each gap what the auditor will ask for. The result includes the disclosure that this is educational, not a determination of adequacy.
5. Submits the inquiry form: name, company, email, phone (optional), contract type(s), accounting platform, **deadline date**, message, privacy acknowledgment, optional marketing opt-in. Or clicks the booking link and picks a slot.
6. Sees a confirmation page: what happens next, "we reply within one business day," and what to gather meanwhile.
7. Willy receives the submission by email with the deadline and contract type at the top, replies within one business day, and runs a short triage call.

## 3. First useful outcome

A contractor with a live pre-award survey request lands on the SF 1408 page, understands within two minutes what will be checked and what PDS will deliver for roughly what price, and sends a qualified inquiry that reaches Willy with the deadline attached. That is slice 1. Everything else (other service pages, calculators, guides, webinar capture) is breadth on top of this.

## 4. Acceptance examples (synthetic data)

**A. Qualified SF 1408 inquiry.**
Visitor: "Ridgeline Avionics LLC," 18 employees, QuickBooks Online, first cost-plus-fixed-fee bid to an Air Force contracting office, pre-award survey requested, deadline 2026-11-20. Submits the form from the SF 1408 page.
Expected: form accepts; confirmation page shows next steps; Willy's inbox receives one email with subject containing "SF 1408" and "Deadline 2026-11-20," body lists contract type "CPFF," platform "QuickBooks Online," and the message; page source of the form is recorded as "/sf-1408-pre-award-accounting."

**B. Self-check with gaps.**
Same visitor runs the readiness self-check and answers: GAAP-basis accounting: Yes. Direct vs. indirect segregation: Yes. Costs accumulated by contract: Yes. Indirect allocation method documented: Unsure. Costs under general ledger control: Yes. Timekeeping by cost objective: No. Labor distribution to cost objectives: No. Monthly posting: Yes. Unallowable costs excluded (FAR 31): Unsure. Costs by CLIN/unit when required: Yes. Preproduction segregation: N/A. Limitation of cost/funds reporting: Unsure. Progress payment support: N/A. Pricing follow-on data: Yes. System in full operation: Yes.
Expected: result shows 3 "No/Unsure" gaps flagged as critical (timekeeping, labor distribution, indirect allocation method) and 2 as "needs documentation" (unallowables, limitation of cost). Each gap names the SF 1408 Section II item and the evidence an auditor would request (for example, timekeeping: written timekeeping policy, employee time records showing charge codes, supervisor approval trail). The result ends with the educational disclosure and the "Request a readiness review" CTA carrying the self-check summary into the form message. Nothing is stored server-side.

**C. Deadline fast lane.**
Visitor clicks "Facing an audit deadline?" on Home, enters deadline 2026-11-03 (eight days out) and notice type "Pre-award survey."
Expected: lands on the contact form with deadline and notice type pre-filled and a banner "Under 14 days: book a call directly" showing the booking embed above the form.

**D. Incurred cost deadline tracker.**
Visitor enters fiscal year end 2026-06-30.
Expected: due date shown as 2026-12-31 with citation FAR 52.216-7(d)(2)(i) ("within the 6-month period following the expiration of each of its fiscal years"), days remaining computed from today, and a backward timeline (data pull, schedules, reconciliation, review, submit) with the disclosure that the contracting officer may grant extensions and the tracker is not legal advice.

**E. Wrap rate calculator.**
Inputs: direct labor $50.00/hr; fringe pool $380,000 on direct labor base $1,000,000 (38.0%); overhead pool $759,000 on base of direct labor plus fringe $1,380,000 (55.0%); G&A pool $320,850 on total cost input base $2,139,000 (15.0%).
Expected: burdened labor $69.00; with overhead $106.95; with G&A $122.99 per hour. The display shows each step and the base used for each rate, uses decimal arithmetic with rounding to the cent only at display, and carries the disclosure that rates must come from the contractor's own books and are not a DCAA determination.

**F. Claims lint.**
Content containing "DCAA-certified," "guaranteed to pass," "SDVOSB," or "Service-Disabled Veteran-Owned" fails the build until the certification flag is enabled in config.
Expected: build fails with the offending string and file named.

**G. Unverified facts hidden.**
Phone and LinkedIn left empty in site config.
Expected: header, footer and contact page show no phone or LinkedIn element at all, not a placeholder.

**H. Referrer path.**
A CPA opens `/about`.
Expected: within the first screen, "advisory services, not a replacement for your CPA," the scope list of what PDS does not do (tax, bookkeeping, payroll, system implementation), and a "Refer a client" link that opens the contact form with inquiry type "Referral" preselected.

**I. Mobile.**
All of A–H at 375px width: no horizontal scroll, form fields 48px tall, primary CTA visible without scrolling on Home and the SF 1408 page.

## 5. Non-goals (not in this project)

- Client portal, document upload, logins, or any stored client data.
- Implementation or configuration of accounting systems (decision 3).
- Tax, bookkeeping, payroll, or general management consulting pages.
- Prosperous Logic content, links, or cross-branding (decision 1).
- SDVOSB badge or wording (decision 4) until SBA approval is confirmed and the config flag is flipped.
- Paid search campaigns, CRM configuration, webinar platform. The site must expose the hooks (UTM-safe URLs, form source field, newsletter capture) but those programs are run separately.
- A proprietary readiness portal (plan §5 long-term).
- Blog platform decisions beyond "articles are Markdown files in the repo."

## 6. Sensitive or regulated behavior

| Item | Rule on the site | Authoritative reference |
|---|---|---|
| SF 1408 criteria in the self-check | Mirror the current form's Section II evaluation items verbatim in meaning; link the official form and DCAA's pre-award checklist; label as educational | SF 1408 (GSA form), DCAA "Pre-award Accounting System Adequacy Checklist"; FAR 16.301-3(a)(3) |
| Post-award accounting system criteria | Cite the 18 criteria by clause; do not restate them as PDS criteria | DFARS 252.242-7006(c) |
| Incurred cost submission due date | Six months after fiscal year end; note extensions are at the contracting officer's discretion | FAR 52.216-7(d)(2)(i) |
| Allowability language | "Unallowable under FAR Part 31" only; no promises about what DCAA will accept | FAR 31.201-2, 31.205 |
| Rate math in the calculator | Deterministic code, decimal arithmetic, inputs are the visitor's; output labeled educational | None (not a determination) |
| Government endorsement | Bio states roles and years; never implies DCAA, DCMA, VA or DOE endorsement; no agency seals | Plan §05; 18 U.S.C. 701 and 713 (seals) |
| Certification claims | Veteran-owned and "disabled veteran founder" only; SDVOSB claim gated by config flag and SBA approval evidence | SBA VetCert; 13 CFR Part 128 |
| CPA designation | "William Austin, CPA (Colorado)"; no firm-level "CPA firm" claim unless licensed as such | Colorado State Board of Accountancy rules on firm names |
| Visitor data | Form data goes to the form service and Willy's inbox only; no analytics on form field contents; privacy policy states retention | Privacy policy (to draft); form vendor DPA |
| Accessibility | WCAG 2.1 AA; the Design Build Guide §9 already specifies focus, contrast, reduced motion | WCAG 2.1 AA |
| AI | None on the site. Copy is human-written and Willy-reviewed; tools are deterministic | Kickoff rule: AI does not calculate decision numbers |

Willy must review: every service description, every price, the bio, the self-check questions and the per-gap "what the auditor will ask for" text, and all FAQ answers.

## 7. Open questions

1. Starting prices to publish (decision 5 says publish; the numbers in the plan are test prices and need Willy's sign-off).
2. Which form service and which booking tool (recommend one hosted form provider with spam protection and email delivery, plus Willy's calendar link; both can be swapped in config).
3. Does Willy want the self-check to email the visitor their results (adds an email capture and a privacy obligation) or display only? Recommend display only for launch.
4. Fiscal-year and extension nuances for the deadline tracker: show only the base rule, or also the common extension pattern? Recommend base rule plus a one-line note.
5. The current `pragmaticds.com` says "consulting and capture support" with four generic services. Replace entirely on launch, or keep "Strategy & Capture" as a secondary offer? The plan excludes generic consulting; recommend replace.
