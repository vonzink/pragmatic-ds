import { useCallback, useEffect, useState } from "react";
import {
  HashRouter, NavLink, Navigate, Route, Routes,
  useLocation, useNavigate, useSearchParams,
} from "react-router-dom";
import { ChevronDown, Search } from "lucide-react";
import { AuthError, adminKey, api } from "./api";
import { NavGroup, buildNav, crumbFor } from "./nav";
import CommandPalette from "./CommandPalette";
import { Stats } from "./types";
import Corpus from "./screens/Corpus";
import Overview from "./screens/Overview";
import Brains from "./screens/Brains";
import Connect from "./screens/Connect";
import Connectors from "./screens/Connectors";
import Tools from "./screens/Tools";
import Settings from "./screens/Settings";
import Rules from "./screens/Rules";
import Analyzers from "./screens/Analyzers";
import Vocabulary from "./screens/Vocabulary";
import SourceLinks from "./screens/SourceLinks";
import PageGuides from "./screens/PageGuides";
import TestConsole from "./screens/TestConsole";
import Audit from "./screens/Audit";
import Personality from "./screens/Personality";
import IncomeLab, { labEnabled } from "./screens/IncomeLab";
import { instanceControlEnabled } from "./instances/feature";
import Pending from "./instances/Pending";
import InstanceLanding from "./instances/screens/InstanceLanding";
import InstanceWorkspace from "./instances/screens/InstanceWorkspace";
import IndependentRunBuilder from "./instances/screens/IndependentRunBuilder";
import InstanceWizard from "./instances/wizard/InstanceWizard";
import InstanceRunQueue from "./instances/screens/InstanceRunQueue";
import InstanceModelCatalog from "./instances/screens/InstanceModelCatalog";

// ⌘K on a Mac keyboard, Ctrl K everywhere else — shown on the trigger, handled
// for both in the shell's hotkey.
const metaK = /mac/i.test(typeof navigator !== "undefined" ? navigator.platform ?? "" : "")
  ? "⌘K" : "Ctrl K";

function KeyGate({ onUnlocked }: { onUnlocked: () => void }) {
  const [key, setKey] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function unlock() {
    adminKey.set(key.trim());
    try {
      await api.get<Stats>("/api/ai/admin/stats");
      onUnlocked();
    } catch (e) {
      setError(e instanceof AuthError ? "Key rejected" : (e as Error).message);
    }
  }

  return (
    <div className="gate">
      <div className="card">
        <h1>RAG brain dashboard</h1>
        <p className="muted">Enter the admin API key for this brain.</p>
        <input type="password" value={key} onChange={(e) => setKey(e.target.value)}
               onKeyDown={(e) => e.key === "Enter" && unlock()} placeholder="admin API key" />
        <button className="btn-primary" onClick={unlock} disabled={!key.trim()}>Unlock</button>
        {error && <p className="error-note">{error}</p>}
      </div>
    </div>
  );
}

function Sidebar({ stats, groups, suffix, onLock, onOpenPalette }: {
  stats: Stats | null; groups: NavGroup[]; suffix: string;
  onLock: () => void; onOpenPalette: () => void;
}) {
  const navigate = useNavigate();
  const initial = (stats?.brain.companyName ?? "R").trim().charAt(0).toUpperCase();

  return (
    <aside className="sidebar">
      <div className="brand">
        <span className="mark">{initial}</span>
        <span style={{ minWidth: 0 }}>
          <strong>{stats?.brain.companyName ?? "RAG brain"}</strong>
          <span className="muted">Control plane</span>
        </span>
      </div>

      {/* Brain switcher — the Brains screen is the picker until a dedicated
          popover exists. The active brain travels in ?brain=. */}
      <button className="brain-switch" onClick={() => navigate(`/brains${suffix}`)}>
        <span className="dot" />
        <span style={{ flex: 1, minWidth: 0 }}>
          <span className="name">{stats?.brain.companyName ?? "…"}</span>
          <span className="id">{stats ? `${stats.brain.slug} · ${stats.brain.id.slice(0, 8)}` : "—"}</span>
        </span>
        <ChevronDown size={12} strokeWidth={1.5} aria-hidden="true"
                     style={{ color: "var(--muted-2)", flex: "none" }} />
      </button>

      <button className="jump" onClick={onOpenPalette}>
        <Search size={13} strokeWidth={1.5} aria-hidden="true" />
        <span>Jump to…</span>
        <span className="kbd">{metaK}</span>
      </button>

      <nav className="nav">
        {groups.map((g) => (
          <div key={g.group} style={{ display: "contents" }}>
            <div className="nav-group">{g.group}</div>
            {g.items.map((it) => (
              <NavLink key={it.to} to={`${it.to}${suffix}`} aria-label={it.label}>
                <it.ico className="ico" size={14} strokeWidth={1.5} aria-hidden="true" />
                <span style={{ flex: 1 }}>{it.label}</span>
                {it.count && <span className={`count ${it.tone ?? ""}`}>{it.count}</span>}
              </NavLink>
            ))}
          </div>
        ))}
      </nav>

      <div className="sidebar-foot">
        <div className="api-health">
          <span className={stats ? "dot" : "dot off"} />
          <span>{stats ? "API healthy" : "Connecting…"}</span>
        </div>
        <button className="signout" onClick={onLock}>Lock dashboard</button>
      </div>
    </aside>
  );
}

function Topbar({ stats, groups, syncedAt }: {
  stats: Stats | null; groups: NavGroup[]; syncedAt: number | null;
}) {
  const { pathname } = useLocation();
  // Re-render every 30s so the "synced" stamp stays roughly honest.
  const [, setTick] = useState(0);
  useEffect(() => {
    const t = window.setInterval(() => setTick((n) => n + 1), 30_000);
    return () => window.clearInterval(t);
  }, []);

  const synced = syncedAt == null ? null : Math.max(0, Math.round((Date.now() - syncedAt) / 1000));
  const syncedLabel = synced == null ? "syncing…"
    : synced < 60 ? "synced just now"
    : `synced ${Math.round(synced / 60)}m ago`;
  const initials = (stats?.brain.companyName ?? "RB")
    .split(/\s+/).map((w) => w.charAt(0)).join("").slice(0, 2).toUpperCase();

  return (
    <header className="topbar">
      <div className="crumbs">
        <span>{stats?.brain.slug ?? "brain"}</span>
        <span className="sep">/</span>
        <span className="here">{crumbFor(pathname, groups)}</span>
      </div>
      <div style={{ flex: 1 }} />
      <div className="env-pill"><span className="dot" />{import.meta.env.MODE}</div>
      <span className="synced">{syncedLabel}</span>
      <div className="avatar">{initials}</div>
    </header>
  );
}

function Shell({ onLock }: { onLock: () => void }) {
  const [searchParams] = useSearchParams();
  // Active brain selector from the URL (?brain=<slug>); null => backend default brain.
  const brain = searchParams.get("brain");
  // Preserve the full query (brain + scope) as the user navigates between screens,
  // so the selected brain is never dropped by a nav click. Without this every
  // NavLink resets to the default (generic) brain.
  const search = searchParams.toString();
  const suffix = search ? `?${search}` : "";

  const [stats, setStats] = useState<Stats | null>(null);
  const [syncedAt, setSyncedAt] = useState<number | null>(null);
  const [paletteOpen, setPaletteOpen] = useState(false);

  // ⌘K / Ctrl+K toggles the jump-to palette from anywhere in the shell.
  useEffect(() => {
    function onKey(e: globalThis.KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // api.ts injects ?brain=<slug> from the URL, so stats now reflect the selected
  // brain (brand line + nav counts). Refetch whenever the brain changes.
  const loadStats = useCallback(() => {
    api.get<Stats>("/api/ai/admin/stats")
      .then((s) => { setStats(s); setSyncedAt(Date.now()); })
      .catch((e) => { if (e instanceof AuthError) onLock(); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [brain]);
  useEffect(loadStats, [loadStats]);

  const groups = buildNav(instanceControlEnabled(), labEnabled(), stats);

  return (
    <div className="shell">
      <Sidebar stats={stats} groups={groups} suffix={suffix} onLock={onLock}
               onOpenPalette={() => setPaletteOpen(true)} />
      {paletteOpen && (
        <CommandPalette groups={groups} onClose={() => setPaletteOpen(false)} />
      )}
      <div className="main">
        <Topbar stats={stats} groups={groups} syncedAt={syncedAt} />
        <main className="content">
          <Routes>
            <Route path="/overview" element={<Overview stats={stats} />} />
            <Route path="/corpus" element={<Corpus stats={stats} onCorpusChanged={loadStats} />} />
            <Route path="/brains" element={<Brains />} />
            <Route path="/connect" element={<Connect />} />
            <Route path="/connectors" element={<Connectors />} />
            <Route path="/tools" element={<Tools />} />
            <Route path="/personality" element={<Personality stats={stats} />} />
            <Route path="/settings" element={<Settings />} />
            <Route path="/rules" element={<Rules />} />
            <Route path="/analyzers" element={<Analyzers />} />
            <Route path="/vocabulary" element={<Vocabulary />} />
            <Route path="/source-links" element={<SourceLinks />} />
            <Route path="/page-guides" element={<PageGuides />} />
            <Route path="/console" element={<TestConsole slug={stats?.brain.slug ?? ""} />} />
            <Route path="/audit" element={<Audit />} />
            {labEnabled() && <Route path="/lab/income" element={<IncomeLab />} />}
            {/* Absent, not hidden, when the flag is off: an unknown path cannot land on a
                half-built instance screen. `/instances/new` is declared before the workspace
                pattern so the literal wins over :brainId rather than relying on ranking. */}
            {instanceControlEnabled() && (
              <Route path="/instances/new" element={<InstanceWizard />} />
            )}
            {instanceControlEnabled() && (
              <Route path="/instances" element={<InstanceLanding />} />
            )}
            {instanceControlEnabled() && (
              <Route path="/instance-runs/new" element={<IndependentRunBuilder />} />
            )}
            {instanceControlEnabled() && (
              <Route path="/instance-runs/:groupId" element={<Pending screen="Run group" />} />
            )}
            {instanceControlEnabled() && (
              <Route path="/instance-runs" element={<InstanceRunQueue />} />
            )}
            {instanceControlEnabled() && (
              <Route path="/instance-models" element={<InstanceModelCatalog />} />
            )}
            {/* The workspace reads its brain and instance from these segments. It never consults
                the sidebar's ?brain= to know what it is showing. */}
            {instanceControlEnabled() && (
              <Route path="/instances/:brainId/:instanceSlug/*" element={<InstanceWorkspace />} />
            )}
            <Route path="*" element={<Navigate
              to={instanceControlEnabled() ? `/instances${suffix}` : `/corpus${suffix}`}
              replace />} />
          </Routes>
        </main>
      </div>
    </div>
  );
}

export default function App() {
  const [unlocked, setUnlocked] = useState(!!adminKey.get());

  if (!unlocked) return <KeyGate onUnlocked={() => setUnlocked(true)} />;

  return (
    <HashRouter>
      <Shell onLock={() => { adminKey.clear(); setUnlocked(false); }} />
    </HashRouter>
  );
}
