import { MouseEvent, ReactNode } from "react";
import { Ellipsis } from "lucide-react";

export function Pill({ tone, children }: {
  tone: "green" | "amber" | "gray" | "blue" | "purple" | "accent"; children: ReactNode;
}) {
  return <span className={`pill ${tone}`}>{children}</span>;
}

export function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
    </div>
  );
}

export function ErrorNote({ message }: { message: string | null }) {
  return message ? <p className="error-note">{message}</p> : null;
}

export function outcomeTone(escalated: boolean): "green" | "amber" {
  return escalated ? "amber" : "green";
}

export function relTime(iso: string | null): string {
  if (!iso) return "";
  const s = (Date.now() - new Date(iso).getTime()) / 1000;
  if (!Number.isFinite(s)) return "";
  if (s < 0) return new Date(iso).toLocaleString();
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  if (s < 86400 * 30) return `${Math.round(s / 86400)}d ago`;
  return new Date(iso).toLocaleDateString();
}

/* One status vocabulary — dot + label — used identically on every screen. */
export function Status({ kind, children }: { kind: string; children?: ReactNode }) {
  return <span className={`status ${kind}`}>{children ?? kind}</span>;
}

/* One ratio vocabulary — a filled track — wherever a share or score is shown.
   Tone falls back to the accent fill; size to the 3px stat-tile track. */
export function Meter({ ratio, tone, size }: {
  ratio: number; tone?: "mid" | "warn" | "bad"; size?: "sm" | "lg";
}) {
  const pct = Math.round(Math.max(0, Math.min(1, ratio)) * 100);
  const cls = ["meter", tone, size].filter(Boolean).join(" ");
  return (
    <span className={cls} role="img" aria-label={`${pct}%`}>
      <span style={{ width: `${pct}%` }} />
    </span>
  );
}

export function scoreTone(score: number): "mid" | "warn" | "bad" | undefined {
  if (score >= 0.8) return undefined;
  if (score >= 0.7) return "mid";
  if (score >= 0.5) return "warn";
  return "bad";
}

/* The design's confidence rendering: the word, a short meter, the mono value —
   never a bare number. */
export function ConfidenceMeter({ value, label = "confidence" }: { value: number; label?: string }) {
  return (
    <span className="conf">
      <span className="lbl">{label}</span>
      <Meter ratio={value} tone={scoreTone(value)} size="sm" />
      <span className="val">{value.toFixed(2)}</span>
    </span>
  );
}

/* Row actions collapsed behind a ⋯ trigger. The screen owns a single openMenuId
   so at most one menu is open; clicking an item or the backdrop closes it. */
export function RowMenu({ id, openId, onToggle, label = "Row actions", children }: {
  id: string;
  openId: string | null;
  onToggle: (id: string | null) => void;
  label?: string;
  children: ReactNode;
}) {
  const open = openId === id;
  const stopAndClose = (e: MouseEvent) => { e.stopPropagation(); onToggle(null); };
  return (
    <span className="row-menu-wrap">
      <button type="button" className="row-menu" aria-haspopup="menu" aria-expanded={open}
              aria-label={label}
              onClick={(e) => { e.stopPropagation(); onToggle(open ? null : id); }}>
        <Ellipsis size={15} strokeWidth={1.5} aria-hidden="true" />
      </button>
      {open && (
        <>
          <span className="row-pop-backdrop" onClick={stopAndClose} />
          {/* Item handlers run first, then the bubble closes the menu. */}
          <span className="row-pop" role="menu" onClick={stopAndClose}>{children}</span>
        </>
      )}
    </span>
  );
}
