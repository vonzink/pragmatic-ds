"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { site } from "@/config/site";

const TYPES: [string, string][] = [
  ["survey", "Pre-award survey request"],
  ["audit", "Audit or review notice"],
  ["ics", "Incurred cost deadline"],
  ["rates", "Rate question"],
  ["referral", "Referral"],
  ["other", "Something else"],
];

const CONTRACT_TYPES: [string, string][] = [
  ["CPFF", "CPFF"],
  ["TM", "T&M"],
  ["FFP-PP", "FFP with progress payments"],
  ["Grant", "Grant / cooperative agreement"],
  ["Unsure", "Not sure"],
];

const PLATFORMS = [
  "QuickBooks Online",
  "QuickBooks Desktop",
  "Xero",
  "Sage",
  "Unanet",
  "Deltek Costpoint",
  "Spreadsheets",
  "Other",
];

const input =
  "h-11 rounded-sm border border-line bg-surface-raised px-3 text-[15px] font-normal text-ink aria-invalid:border-error";
const fieldLabel = "flex flex-col gap-1.5 text-sm font-medium text-ink";
const twoUp = "grid grid-cols-[repeat(auto-fit,minmax(min(100%,220px),1fr))] gap-4";

type Errors = Partial<Record<"name" | "company" | "email" | "submit", string>>;

/**
 * Booking block + readiness review form. Query params pre-fill it:
 * `?deadline=1` (fast lane), `?type=`, `?summary=` (from the self-check).
 */
export function ContactForm() {
  const router = useRouter();
  const [fastLane, setFastLane] = useState(false);
  const [type, setType] = useState("");
  const [deadline, setDeadline] = useState("");
  const [message, setMessage] = useState("");
  const [errors, setErrors] = useState<Errors>({});
  const [sending, setSending] = useState(false);

  // Static export: read the query string on the client after hydration.
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    const fast = q.get("deadline") === "1";
    const t = q.get("type");
    const summary = q.get("summary");
    /* eslint-disable react-hooks/set-state-in-effect -- one-time sync from the URL */
    if (fast) {
      const d = new Date();
      d.setDate(d.getDate() + 10);
      setFastLane(true);
      setDeadline(d.toISOString().slice(0, 10));
    }
    if (t && TYPES.some(([v]) => v === t)) setType(t);
    else if (fast) setType("audit");
    if (summary) setMessage(summary);
    /* eslint-enable react-hooks/set-state-in-effect */
  }, []);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const fd = new FormData(e.currentTarget);
    const get = (k: string) => String(fd.get(k) ?? "").trim();
    const next: Errors = {};
    if (!get("name")) next.name = "Enter your name.";
    if (!get("company")) next.company = "Enter your company.";
    if (!get("email")) next.email = "Enter your email.";
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(get("email"))) next.email = "Enter a valid email address.";
    setErrors(next);
    if (Object.keys(next).length) {
      const first = Object.keys(next)[0];
      document.querySelector<HTMLElement>(`[name="${first}"]`)?.focus();
      return;
    }

    const payload = {
      name: get("name"),
      company: get("company"),
      email: get("email"),
      phone: get("phone"),
      received: TYPES.find(([v]) => v === type)?.[1] ?? "",
      contractTypes: fd.getAll("ct").map(String),
      platform: get("platform"),
      deadline: get("deadline"),
      message: get("message"),
      optIn: fd.get("optin") === "on",
    };

    if (site.formEndpoint) {
      setSending(true);
      try {
        const res = await fetch(site.formEndpoint, {
          method: "POST",
          headers: { "Content-Type": "application/json", Accept: "application/json" },
          body: JSON.stringify(payload),
        });
        if (!res.ok) throw new Error(String(res.status));
        router.push("/thank-you/");
      } catch {
        setErrors({ submit: `Something went wrong sending the form. Please email ${site.contact.email} instead.` });
        setSending(false);
      }
      return;
    }

    // No endpoint configured: hand the request to the visitor's mail client.
    const body = [
      `Name: ${payload.name}`,
      `Company: ${payload.company}`,
      `Email: ${payload.email}`,
      payload.phone && `Phone: ${payload.phone}`,
      payload.received && `Received: ${payload.received}`,
      payload.contractTypes.length && `Contract type: ${payload.contractTypes.join(", ")}`,
      payload.platform && `Accounting platform: ${payload.platform}`,
      payload.deadline && `Deadline: ${payload.deadline}`,
      payload.optIn && "Opted in to occasional notes.",
      "",
      payload.message,
    ]
      .filter((l) => l !== false && l !== 0 && l !== undefined)
      .join("\n");
    const subject = `Readiness review request: ${payload.company}`;
    window.location.href = `mailto:${site.contact.email}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
    router.push("/thank-you/");
  }

  return (
    <div className="flex min-w-0 flex-[1.2_1_400px] flex-col gap-6">
      <div className="flex flex-col gap-3">
        {fastLane && (
          <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 rounded-sm border border-ink bg-surface px-5 py-4">
            <div>
              <div className="eyebrow text-error">Deadline fast lane</div>
              <div className="mt-1 text-base font-semibold text-ink">
                Under 14 days to your deadline: book a call directly.
              </div>
            </div>
            <span className="text-sm text-muted">The form below is pre-filled; the booking card comes first.</span>
          </div>
        )}
        <p className="disclosure">
          Booking a call does not create a client relationship. The first call is a short triage conversation at no
          charge.
        </p>
        {/* Replace with the calendar provider's embed once chosen. */}
        <div className="flex aspect-[16/10] flex-col items-center justify-center gap-2 rounded-sm border border-line bg-surface p-5 text-center">
          <span className="eyebrow text-muted">Booking embed</span>
          <span className="text-sm text-muted">[Calendar provider embed · 30-minute triage call]</span>
        </div>
      </div>

      <form noValidate onSubmit={onSubmit} className="card">
        <div className="eyebrow flex flex-wrap justify-between gap-2 border-b border-line-soft px-5 py-3 text-muted">
          <span>Request a readiness review</span>
          <span>Reply within one business day</span>
        </div>
        <div className="flex flex-col gap-5 p-5">
          <div className={twoUp}>
            <Field name="name" label="Name" error={errors.name} autoComplete="name" />
            <Field name="company" label="Company" error={errors.company} autoComplete="organization" />
            <Field name="email" label="Email" type="email" error={errors.email} autoComplete="email" />
            <Field name="phone" label="Phone" type="tel" optional autoComplete="tel" />
          </div>

          <fieldset>
            <legend className="mb-2 text-sm font-medium text-ink">What did you receive?</legend>
            <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,220px),1fr))] gap-px overflow-hidden rounded-sm border border-line bg-line">
              {TYPES.map(([v, label]) => (
                <label
                  key={v}
                  className={`flex min-h-11 cursor-pointer items-center gap-2.5 px-3 text-sm text-ink hover:bg-surface ${type === v ? "bg-surface" : "bg-surface-raised"}`}
                >
                  <input
                    type="radio"
                    name="type"
                    value={v}
                    checked={type === v}
                    onChange={() => setType(v)}
                    className="m-0 h-4 w-4 accent-ink"
                  />
                  {label}
                </label>
              ))}
            </div>
          </fieldset>

          <fieldset>
            <legend className="mb-2 text-sm font-medium text-ink">
              Contract type <span className="font-normal text-muted">(check any)</span>
            </legend>
            <div className="flex flex-wrap gap-2">
              {CONTRACT_TYPES.map(([v, label]) => (
                <label
                  key={v}
                  className="flex min-h-11 cursor-pointer items-center gap-2 rounded-sm border border-line px-3 text-sm text-ink hover:border-ink"
                >
                  <input type="checkbox" name="ct" value={v} className="m-0 h-4 w-4 accent-ink" />
                  {label}
                </label>
              ))}
            </div>
          </fieldset>

          <div className={twoUp}>
            <label className={fieldLabel}>
              Accounting platform
              <select name="platform" defaultValue="" className={input}>
                <option value="">Select one</option>
                {PLATFORMS.map((p) => (
                  <option key={p}>{p}</option>
                ))}
              </select>
            </label>
            <label className={fieldLabel}>
              Deadline date
              <input
                type="date"
                name="deadline"
                value={deadline}
                onChange={(e) => setDeadline(e.target.value)}
                className={input}
              />
            </label>
          </div>

          <label className={fieldLabel}>
            Message
            <textarea
              name="message"
              rows={5}
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              placeholder="What you received, who sent it, and anything already underway."
              className="resize-y rounded-sm border border-line bg-surface-raised px-3 py-2.5 text-[15px] leading-[1.5] font-normal text-ink"
            />
          </label>

          <p className="disclosure">
            Sending this form does not create a client relationship. Please do not include account numbers or employee
            personal data. PDS provides advisory services and is not affiliated with any federal agency.
          </p>
          <label className="flex cursor-pointer items-start gap-2.5 text-sm text-body">
            <input type="checkbox" name="optin" className="mt-[3px] h-4 w-4 flex-none accent-ink" />
            Send me occasional notes on DCAA deadlines and regulation changes. Unsubscribe any time.
          </label>
          {errors.submit && (
            <p role="alert" className="text-[13px] text-error">
              {errors.submit}
            </p>
          )}
          <button
            type="submit"
            disabled={sending}
            className="h-12 w-full cursor-pointer self-start rounded-sm bg-accent px-7 text-[15px] font-semibold text-on-ink hover:bg-accent-hover disabled:opacity-60 md:w-auto"
          >
            {sending ? "Sending…" : "Send"}
          </button>
        </div>
      </form>
    </div>
  );
}

function Field({
  name,
  label,
  type = "text",
  optional = false,
  error,
  autoComplete,
}: {
  name: string;
  label: string;
  type?: string;
  optional?: boolean;
  error?: string;
  autoComplete?: string;
}) {
  return (
    <label className={fieldLabel}>
      <span>
        {label} {optional && <span className="font-normal text-muted">(optional)</span>}
      </span>
      <input
        type={type}
        name={name}
        required={!optional}
        autoComplete={autoComplete}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${name}-error` : undefined}
        className={input}
      />
      {error && (
        <span id={`${name}-error`} className="text-[13px] font-normal text-error">
          {error}
        </span>
      )}
    </label>
  );
}
