# Vertex AI setup checklist — for the Pragmatic DS Document Engine AI extraction layer

Work top to bottom. **Phase B (compliance) is the long pole — start it first**; the rest can proceed
in parallel. Tick items with `[x]` as you go.

> **Hard rule:** no **real borrower documents** go through Vertex until Phase B is confirmed by your
> compliance side. The engine build runs on **synthetic** statements meanwhile — it does not wait on
> this. Mortgage data is **GLBA NPI, not HIPAA PHI** → the instrument is a **DPA**, not a "BAA."

---

## Phase A — Account & project
- [ ] Sign in to `console.cloud.google.com` with an **org / Workspace** account (not personal Gmail) if you have one — enterprise agreements + an account rep attach to the org.
- [ ] Create a dedicated project (e.g. `pds-ai-prod`). **Record the Project ID** (the id, e.g. `pds-ai-prod-4821` — not the display name): `________________`
- [ ] Attach a **billing account** to the project.

## Phase B — Compliance / data terms (START NOW)
- [ ] Accept the **Google Cloud Data Processing Addendum (DPA)** (console legal agreements, or via a Google Cloud account rep). Request a rep if you don't have one.
- [ ] Get written confirmation of **Vertex AI data governance**: prompts/outputs are **not used to train models** and **not retained for training**, and **data residency** is limited to your chosen region. Confirm current specifics with your rep.
- [ ] Choose and **pin a region** for residency (e.g. `us-central1` or `us-east4`): `________________`
- [ ] Route the DPA + data-governance confirmation to your **compliance/legal** for review — this is the actual gate on real documents.

## Phase C — Enable the API & models
- [ ] APIs & Services → enable **Vertex AI API** (accept any prompted dependencies).
- [ ] Model Garden → enable **Gemini** (e.g. **Gemini 2.5 Flash / Flash-Lite**).
- [ ] Model Garden → **accept Anthropic's terms** to enable **Claude on Vertex** (optional but recommended — one gateway, both models).
- [ ] IAM & Admin → Quotas → check requests-per-minute for those models in your region; request an increase if the default is low.

## Phase D — Auth for the engine (runs on the AWS box, not GCP)
- [ ] IAM & Admin → Service Accounts → create `docengine-vertex`.
- [ ] Grant it **only** the **Vertex AI User** role (`roles/aiplatform.user`) — not Editor/Owner.
- [ ] Create a **JSON key** for that service account and download it. Treat it like a password.
- [ ] Place the JSON **on the engine box**, in the gitignored secrets area (beside `deploy/engine.env`), `chmod 600`. **Never commit it or paste it in chat.** Path on the box: `________________`

## Phase E — Cost guardrails
- [ ] Billing → Budgets → set a **budget + alert** (e.g. notify at $50 / $200).

---

## When ready, hand these to the build (to wire the Vertex adapter)
- [ ] **Project ID** (Phase A)
- [ ] **Region** (Phase B)
- [ ] **Model id(s) enabled** — e.g. `gemini-2.5-flash-lite` and/or the Claude-on-Vertex model id
- [ ] **Service-account JSON key path on the box** (Phase D) — the file stays on the box; just share the path, not the key

## Status gate
- [ ] Compliance sign-off received → **real borrower documents may flow.** Until then: synthetic only.
