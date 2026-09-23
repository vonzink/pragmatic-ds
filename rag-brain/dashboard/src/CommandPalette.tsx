import { KeyboardEvent, useEffect, useMemo, useRef, useState } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { Brain as BrainIcon, Search } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { brainsApi } from "./api";
import { BrainAdminDto } from "./types";
import { NavGroup } from "./nav";

/* The ⌘K jump-to palette: every nav destination plus a switch-brain entry per
   active brain. Purely client-side filtering; the only request is the lazy
   brains list, made once per open. */

interface Entry {
  id: string;
  icon: LucideIcon;
  label: string;
  hint: string;
  keywords: string;
  run: () => void;
}

export default function CommandPalette({ groups, onClose }: {
  groups: NavGroup[]; onClose: () => void;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const search = searchParams.toString();
  const suffix = search ? `?${search}` : "";

  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const [brains, setBrains] = useState<BrainAdminDto[] | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  // Lazy: the brains list is only worth a request once the palette is open.
  useEffect(() => {
    brainsApi.list().then(setBrains).catch(() => setBrains([]));
  }, []);

  // Focus the search on open; hand focus back to the summoner on close.
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    inputRef.current?.focus();
    return () => prev?.focus?.();
  }, []);

  const entries = useMemo<Entry[]>(() => {
    const nav: Entry[] = groups.flatMap((g) =>
      g.items.map((it) => ({
        id: `nav:${it.to}`,
        icon: it.ico,
        label: it.label,
        hint: g.group,
        keywords: `${it.label} ${g.group} ${it.to}`,
        run: () => navigate(`${it.to}${suffix}`),
      })));
    const currentBrain = searchParams.get("brain");
    const brainEntries: Entry[] = (brains ?? [])
      .filter((b) => b.isActive && b.slug !== currentBrain)
      .map((b) => ({
        id: `brain:${b.slug}`,
        icon: BrainIcon,
        label: `Switch to ${b.displayName}`,
        hint: "brain",
        keywords: `switch brain ${b.displayName} ${b.slug}`,
        // Same screen, different tenant: only the ?brain= selector changes.
        run: () => {
          const params = new URLSearchParams(searchParams);
          params.set("brain", b.slug);
          navigate({ pathname: location.pathname, search: `?${params.toString()}` });
        },
      }));
    return [...nav, ...brainEntries];
  }, [groups, brains, suffix, searchParams, location.pathname, navigate]);

  const results = useMemo(() => {
    const t = q.trim().toLowerCase();
    if (!t) return entries;
    const starts = entries.filter((e) => e.label.toLowerCase().startsWith(t));
    const rest = entries.filter((e) =>
      !e.label.toLowerCase().startsWith(t) && e.keywords.toLowerCase().includes(t));
    return [...starts, ...rest];
  }, [entries, q]);

  useEffect(() => { setSel(0); }, [q]);
  const selIndex = Math.min(sel, Math.max(0, results.length - 1));

  // Keep the selected row in view as arrows move it (guarded: jsdom has no scrollIntoView).
  useEffect(() => {
    const el = listRef.current?.querySelector('[aria-selected="true"]') as HTMLElement | null;
    el?.scrollIntoView?.({ block: "nearest" });
  }, [selIndex, results]);

  function activate(entry?: Entry) {
    const chosen = entry ?? results[selIndex];
    if (!chosen) return;
    chosen.run();
    onClose();
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === "Escape") {
      e.preventDefault(); onClose();
    } else if (e.key === "ArrowDown") {
      e.preventDefault(); setSel((s) => Math.min(s + 1, results.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault(); setSel((s) => Math.max(s - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault(); activate();
    }
  }

  return (
    <div className="palette-overlay" onClick={onClose}>
      <div className="palette" role="dialog" aria-modal="true" aria-label="Jump to"
           onClick={(e) => e.stopPropagation()} onKeyDown={onKeyDown}>
        <div className="palette-search">
          <Search size={15} strokeWidth={1.5} aria-hidden="true" />
          <input ref={inputRef} value={q} onChange={(e) => setQ(e.target.value)}
                 placeholder="Jump to…" aria-label="Jump to" />
        </div>
        <div className="palette-list" ref={listRef} role="listbox" aria-label="Destinations">
          {results.map((e, i) => (
            <div key={e.id} role="option" aria-selected={i === selIndex}
                 className={i === selIndex ? "palette-item sel" : "palette-item"}
                 onMouseEnter={() => setSel(i)}
                 onClick={() => activate(e)}>
              <e.icon size={14} strokeWidth={1.5} aria-hidden="true" />
              <span>{e.label}</span>
              <span className="hint">{e.hint}</span>
            </div>
          ))}
          {results.length === 0 && (
            <div className="palette-empty">Nothing matches "{q}".</div>
          )}
        </div>
        <div className="palette-foot">
          <span><span className="kbd">↑↓</span> navigate</span>
          <span><span className="kbd">↵</span> open</span>
          <span><span className="kbd">esc</span> close</span>
        </div>
      </div>
    </div>
  );
}
