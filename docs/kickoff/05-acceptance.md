# 05 — Interaction and acceptance: pragmaticds.com

Status: Draft
Date: 2026-10-26
Builds on: `01-product-brief.md` (Approved). The mockup is produced from `05-claude-design-prompt.md` in Claude Design; this document is what the mockup and the build are checked against.

## 1. Mockup sign-off (what the user must look at and approve)

The mockup is approved only when Zach (and Willy for copy) have looked at the frames themselves and confirmed, per page:

| Page | Layout decision to confirm | Where controls live | How status and errors appear |
|---|---|---|---|
| Home | Hero copy + AtAGlance right; six service rows; one graphite band; FAQ + NextStep | Primary CTA in hero (blue) and NextStep; header CTA graphite | n/a |
| SF 1408 service page | Rail + article order; one price card; CtaBand | "Request a readiness review" in hero, price card, CtaBand; "Run the self-check" secondary | n/a |
| Self-check tool | Questions left, sticky results right; Yes/No/Unsure control; empty and filled states | Segmented control per row; "Send these results" and "Print" in results panel | Score and two-level gap list; unanswered rows counted as "not answered," never as pass |
| Pricing | Three tier cards, fixed-fee table, response commitments | "Book a consult" per card | Disclosure directly under the table |
| About | Founder timeline table; do/don't table; referrer section | "Refer a client" link | Disclosure under bio |
| Resources | Tools rows, guides rows, official-sources table | "Open →" per row | n/a |
| Contact | Booking card above form; form field order; fast-lane banner; thank-you page | "Send" full width mobile; booking embed | Inline field errors in `#B3261E` with text, `aria-describedby`; success = thank-you page, never an inline toast only |

Mobile frames (375) for each page must be approved separately. Approval is recorded by changing this file's status and adding the date and the mock link.

## 2. Synthetic cases (realistic, with expected outcomes)

**Case 1 — Ridgeline Avionics (SF 1408, qualified).** 18 employees, QuickBooks Online, first CPFF bid to an Air Force contracting office, survey requested, deadline 2026-11-20. Lands on the SF 1408 page from search, runs the self-check (answers as in `01-product-brief.md` example B), sends results with a readiness review request.
Expected: one inquiry email to the configured inbox, subject "[PDS inquiry] Pre-award survey request · Deadline 2026-11-20 · Ridgeline Avionics LLC"; body contains request type, contract type "CPFF," platform "QuickBooks Online," deadline, message, the self-check summary ("11 of 15 ready; critical: timekeeping, labor distribution, allocation method; documentation: unallowables, limitation of cost"), and source page. Visitor lands on the thank-you page.

**Case 2 — Northgate Environmental (incurred cost, not urgent).** 40 employees, Unanet, FYE 2026-06-30, wants to know when the submission is due.
Expected: deadline tracker shows 2026-12-31, FAR 52.216-7(d)(2)(i) citation, days remaining from today's date, backward timeline, extension note. No form required to see the result.

**Case 3 — Cole Fabrication (audit notice, eight days).** DCAA labor floor check scheduled for 2026-11-03. Clicks "Facing an audit deadline?" on Home.
Expected: contact page opens with request type "Audit or review notice" and deadline 2026-11-03 pre-filled; banner "Under 14 days to your deadline: book a call directly"; booking card above the form.

**Case 4 — Referrer (CPA).** Opens About, clicks "Refer a client."
Expected: contact form with request type "Referral" preselected; the "What to have ready" checklist swaps to the referrer version (client name optional, situation, who the client's accountant is).

**Case 5 — Wrap rate (educational).** Inputs from `01-product-brief.md` example E.
Expected: $69.00 → $106.95 → $122.99, each step and base shown, disclosure present, no data leaves the browser.

## 3. Scenario coverage for the first workflow (SF 1408 inquiry)

| Scenario | What happens | Expected behavior | Proof |
|---|---|---|---|
| Happy path | Case 1 | Email delivered with all fields; thank-you page; source recorded | Real-services test against the form provider's test endpoint; inbox check |
| Corrected input | Visitor enters email "ridgeline@example" then fixes to "cfo@ridgeline.example" | First submit blocked with inline error on the email field only; other fields keep their values; second submit succeeds | Component test + manual |
| Duplicate input | Visitor double-clicks Send, or resubmits the same form within 5 minutes | Button disables on first click; a second identical submission within the window is accepted by the provider but tagged "possible duplicate" in the subject so Willy is not confused | Manual; provider behavior documented |
| Unknown or missing value | Deadline left blank; platform "Other / not sure"; self-check row unanswered | Deadline is optional but the email says "Deadline: not given"; platform free-text appears; unanswered criteria count as "not answered" (shown separately, never as a pass) and the score reads "x of 15 answered ready" | Component test |
| Unauthorized access | There are no accounts. Attempt to post to the form endpoint from another origin or with the honeypot filled | Provider rejects or discards; no email sent; site shows a generic "could not send, email us directly at …" fallback with the address visible | Real-services test |
| Partial failure | Form provider is down or returns 5xx | Form shows the failure message with the direct email address and the booking link; the visitor's typed content is not lost (stays in the fields) | Component test with a mocked 5xx; manual against provider |
| Concurrent change | Zach deploys a copy change while a visitor has the form open | Old page still submits successfully (form fields and endpoint are versioned in config, not inline); visitor sees the new copy only on next load | Manual: open form, deploy, submit |
| Stale result | Visitor runs the self-check, then changes an answer | Score and gap list recompute immediately; the "Send these results" summary reflects the current answers, not the first run; printing uses current state | Component test |
| Stale facts | Phone or LinkedIn removed from config after launch | The elements disappear everywhere on next build; no empty labels | Build-time test |
| Claims lint | Copy contains "DCAA-certified" or "SDVOSB" with the certification flag off | Build fails naming file and string | CI test |

## 4. Non-functional acceptance

- Lighthouse on the SF 1408 page and Home, mobile: Performance ≥ 90, Accessibility 100, Best Practices ≥ 95, SEO ≥ 95.
- Every page passes axe with zero violations; keyboard-only run of Case 1 completes.
- Contrast pairs match the Design Build Guide §2 (already verified AA).
- JSON-LD: Organization on Home, FAQPage wherever an accordion renders, Service on service pages.
- No analytics or form field contents sent to third parties beyond the form provider; privacy policy names the provider.

## 5. Open questions (carried from Phase 1 and this phase)

1. Final starting prices (Willy).
2. Form provider and booking tool (Zach proposes; affects the "unauthorized access" and "partial failure" proofs).
3. Self-check: display-only (recommended) or email results.
4. Deadline tracker: base rule plus one-line extension note (recommended).
5. Replace "Strategy & Capture" entirely (recommended) or keep as a secondary offer.
6. Founder timeline years and degree year for the About page (Willy).
7. Headshot: does one exist that meets the guide (neutral background, not in uniform)?
