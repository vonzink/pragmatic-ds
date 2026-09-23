import { useCallback, useEffect, useMemo, useState } from "react";
import { brainsApi, connectorsApi } from "../api";
import { BrainAdminDto, ConnectorClient, ConnectorClientRequest, ConnectorEvent } from "../types";
import { ErrorNote, Pill, Status } from "../components";
import { federationCurlSnippet, mcpConfigSnippet, peerRegistrationSnippet } from "../connect/connectorSnippets";

const TYPES: ConnectorClient["type"][] = ["MCP_AGENT", "PEER_BRAIN", "SERVER_API", "INTERNAL_APP"];
// Public API scopes vs dashboard (internal-app) scopes. The dashboard scopes are
// required for the Tools screen's connector test calls and the internal-dashboard-brain
// workflow; without them here a connector created in the UI can never call a dashboard tool.
const PUBLIC_SCOPES = ["brains:list", "brain:read", "ask:public", "retrieve:public", "citations:read", "readiness:read"];
const DASHBOARD_SCOPES = ["dashboard:ask", "dashboard:tools:list", "dashboard:tools:read", "dashboard:tools:write"];
// Server-to-server instance execution. Assignable here, granted to nobody by default, and useless
// without the matching INSTANCE_RUN_* permission — the scope opens the surface, the permission
// authorizes the spend, and the split keeps those two grants separate deliberate acts.
const INSTANCE_SCOPES = ["instances:run", "instances:runs:read"];
const ALL_SCOPES_COUNT = PUBLIC_SCOPES.length + DASHBOARD_SCOPES.length + INSTANCE_SCOPES.length;
// The safest useful grant: everything an external agent needs to read and ask,
// nothing that writes or spends.
const READ_ONLY_PRESET = ["brains:list", "brain:read", "ask:public", "retrieve:public", "citations:read", "readiness:read"];

type SnippetTab = "mcp" | "curl" | "peer";

function emptyForm(defaultBrainId: string | null): ConnectorClientRequest {
  return {
    name: "",
    type: "MCP_AGENT",
    brainId: defaultBrainId,
    scopes: ["brains:list", "brain:read", "ask:public", "retrieve:public", "readiness:read"],
    allowedOrigins: [],
    allowedPeerHosts: [],
    allowedTenants: [],
    grantedPermissions: [],
    enabled: true,
  };
}

/** Split a comma/newline separated list into trimmed, non-empty entries. */
export function splitLines(text: string): string[] {
  return text.split(/[\n,]+/).map((s) => s.trim()).filter(Boolean);
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button onClick={async () => {
      try {
        await navigator.clipboard.writeText(text);
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      } catch {
        setCopied(false);
      }
    }}>
      {copied ? "Copied" : "Copy"}
    </button>
  );
}

export default function Connectors() {
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [clients, setClients] = useState<ConnectorClient[]>([]);
  const [events, setEvents] = useState<ConnectorEvent[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<ConnectorClientRequest>(emptyForm(null));
  const [peerHosts, setPeerHosts] = useState("");
  const [origins, setOrigins] = useState("");
  const [tenants, setTenants] = useState("");
  const [permissions, setPermissions] = useState("");
  const [apiBase, setApiBase] = useState(window.location.origin);
  const [token, setToken] = useState("");
  const [tokenFor, setTokenFor] = useState<string | null>(null);
  const [tab, setTab] = useState<SnippetTab>("mcp");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const selected = useMemo(() => clients.find((c) => c.id === selectedId) ?? clients[0] ?? null,
    [clients, selectedId]);
  const selectedBrain = useMemo(() => {
    if (selected?.brainId) return brains.find((b) => b.id === selected.brainId) ?? null;
    return brains.find((b) => b.isDefault) ?? brains[0] ?? null;
  }, [brains, selected]);
  // Only reveal a rotated token for the connector it was actually issued for, so
  // selecting a different connector can't bake connector A's token into B's snippet.
  const revealToken = token && tokenFor === selected?.id ? token : "";

  const reload = useCallback(() => {
    Promise.all([brainsApi.list(), connectorsApi.list()])
      .then(([brainList, connectorList]) => {
        setBrains(brainList);
        setClients(connectorList);
        if (!form.brainId && brainList.length) {
          setForm((f) => ({ ...f, brainId: brainList.find((b) => b.isDefault)?.id ?? brainList[0].id }));
        }
      })
      .catch((e) => setError((e as Error).message));
  }, [form.brainId]);

  useEffect(reload, [reload]);

  useEffect(() => {
    if (!selected) {
      setEvents([]);
      return;
    }
    connectorsApi.events(selected.id).then(setEvents).catch(() => setEvents([]));
  }, [selected]);

  function set<K extends keyof ConnectorClientRequest>(key: K, value: ConnectorClientRequest[K]) {
    setForm((f) => ({ ...f, [key]: value }));
  }

  function toggleScope(scope: string) {
    setForm((f) => ({
      ...f,
      scopes: f.scopes.includes(scope) ? f.scopes.filter((s) => s !== scope) : [...f.scopes, scope],
    }));
  }

  function resetForm() {
    setEditingId(null);
    setForm(emptyForm(form.brainId));
    setOrigins(""); setPeerHosts(""); setTenants(""); setPermissions("");
  }

  function startEdit(c: ConnectorClient) {
    setEditingId(c.id);
    setSelectedId(c.id);
    setError(null);
    setForm({
      name: c.name,
      type: c.type,
      brainId: c.brainId,
      scopes: c.scopes,
      allowedOrigins: c.allowedOrigins,
      allowedPeerHosts: c.allowedPeerHosts,
      allowedTenants: c.allowedTenants,
      grantedPermissions: c.grantedPermissions,
      enabled: c.enabled,
    });
    setOrigins(c.allowedOrigins.join(", "));
    setPeerHosts(c.allowedPeerHosts.join(", "));
    setTenants(c.allowedTenants.join(", "));
    setPermissions(c.grantedPermissions.join(", "));
    try {
      window.scrollTo({ top: 0, behavior: "smooth" });
    } catch {
      /* scrollTo is unavailable in some environments (e.g. jsdom) */
    }
  }

  async function save() {
    setBusy("save"); setError(null);
    const body: ConnectorClientRequest = {
      ...form,
      name: form.name.trim(),
      allowedOrigins: splitLines(origins),
      allowedPeerHosts: splitLines(peerHosts),
      allowedTenants: splitLines(tenants),
      grantedPermissions: splitLines(permissions),
    };
    try {
      if (editingId) {
        await connectorsApi.update(editingId, body);
      } else {
        setToken(""); setTokenFor(null);
        await connectorsApi.create(body);
      }
      resetForm();
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function rotate(c: ConnectorClient) {
    setBusy(c.id); setError(null);
    try {
      const res = await connectorsApi.rotateToken(c.id);
      setToken(res.token);
      setTokenFor(c.id);
      setSelectedId(c.id);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function setEnabled(c: ConnectorClient, enabled: boolean) {
    setBusy(c.id); setError(null);
    try {
      await (enabled ? connectorsApi.enable(c.id) : connectorsApi.disable(c.id));
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const snippetParams = { apiBase, slug: selectedBrain?.slug ?? "generic", token: revealToken };
  const snippet = tab === "mcp"
    ? mcpConfigSnippet(snippetParams)
    : tab === "curl"
      ? federationCurlSnippet(snippetParams)
      : peerRegistrationSnippet(snippetParams);

  function scopeGrid(scopes: string[]) {
    return (
      <div className="scope-grid">
        {scopes.map((scope) => (
          <label key={scope} className={form.scopes.includes(scope) ? "scope on" : "scope"}>
            <input type="checkbox" className="sr-only" checked={form.scopes.includes(scope)}
                   onChange={() => toggleScope(scope)} />
            <span className="box" aria-hidden="true" />
            <span className="name">{scope}</span>
          </label>
        ))}
      </div>
    );
  }

  return (
    <>
      <header className="screen-head">
        <h1>Connectors</h1>
        <span className="muted">scoped access for agents, peer brains, and server-side apps</span>
      </header>
      <ErrorNote message={error} />

      <div className="card">
        <h2>{editingId ? "Edit connector" : "Create connector"}</h2>
        <div className="setting-row">
          <label>Name</label>
          <input value={form.name} onChange={(e) => set("name", e.target.value)}
                 placeholder="Cursor agent, partner brain, backend service" />
        </div>
        <div className="setting-row">
          <label>Type</label>
          <select value={form.type} onChange={(e) => set("type", e.target.value as ConnectorClient["type"])}>
            {TYPES.map((type) => <option key={type}>{type}</option>)}
          </select>
        </div>
        <div className="setting-row">
          <label>Brain</label>
          <select value={form.brainId ?? ""} onChange={(e) => set("brainId", e.target.value || null)}>
            <option value="">All active brains</option>
            {brains.map((b) => <option key={b.id} value={b.id}>{b.displayName} ({b.slug})</option>)}
          </select>
        </div>
        <div className="setting-row">
          <label>Scopes</label>
          <div style={{ flex: 1, minWidth: 0, maxWidth: "none" }}>
            <div className="scopes-meta">
              <span className="muted">{form.scopes.length} of {ALL_SCOPES_COUNT} granted</span>
              <button type="button" className="link-button"
                      onClick={() => set("scopes", READ_ONLY_PRESET)}>
                Use a preset: read-only agent
              </button>
            </div>
            <div className="scope-set">
              <div className="scope-head">
                <span className="t">Public API</span>
                <span className="d">Safe for external agents and partner brains.</span>
              </div>
              {scopeGrid(PUBLIC_SCOPES)}
              <div className="scope-head">
                <span className="t">Dashboard tools · internal app</span>
                <span className="d">Required for the Tools screen's connector test calls.</span>
              </div>
              {scopeGrid(DASHBOARD_SCOPES)}
              <div className="scope-head risky">
                <span className="t">Instance runs · server-to-server</span>
                <span className="d">Also needs the matching INSTANCE_RUN_* permission and an allowed tenant to do anything.</span>
              </div>
              {scopeGrid(INSTANCE_SCOPES)}
            </div>
          </div>
        </div>
        <div className="setting-row">
          <label>Peer hosts</label>
          <textarea value={peerHosts} onChange={(e) => setPeerHosts(e.target.value)}
                    placeholder="brain.partner.com, internal.example.com" />
        </div>
        <div className="setting-row">
          <label>Browser origins</label>
          <textarea value={origins} onChange={(e) => setOrigins(e.target.value)}
                    placeholder="https://app.example.com" />
        </div>
        <div className="setting-row">
          <label>Allowed tenants</label>
          <textarea value={tenants} onChange={(e) => setTenants(e.target.value)}
                    placeholder="tenant-acme, tenant-globex" />
          <p className="hint">Required for dashboard tool connectors. The connector may only act for these tenants; a request asserting any other tenantId is rejected.</p>
        </div>
        <div className="setting-row">
          <label>Granted dashboard permissions</label>
          <textarea value={permissions} onChange={(e) => setPermissions(e.target.value)}
                    placeholder="dashboard.loans.read, dashboard.tasks.write" />
          <p className="hint">Dashboard tools are authorized against this list — not against permissions supplied in the request body.</p>
        </div>
        <div className="setting-row">
          <button className="btn-primary" onClick={save}
                  disabled={busy === "save" || !form.name.trim() || form.scopes.length === 0}>
            {busy === "save" ? "Saving..." : editingId ? "Save connector" : "Create connector"}
          </button>
          {editingId && (
            <button onClick={resetForm} disabled={busy === "save"}>Cancel</button>
          )}
        </div>
      </div>

      <table className="tbl">
        <thead>
          <tr><th>Name</th><th>Type</th><th>Brain</th><th>Scopes</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          {clients.map((c) => {
            const brain = brains.find((b) => b.id === c.brainId);
            return (
              <tr key={c.id}>
                <td>{c.name}</td>
                <td><Pill tone="blue">{c.type.toLowerCase()}</Pill></td>
                <td>{brain ? brain.slug : "all"}</td>
                <td>{c.scopes.length}</td>
                <td>
                  <Status kind={c.enabled ? "active" : "inactive"}>{c.enabled ? "enabled" : "disabled"}</Status>
                  {" "}
                  <Pill tone={c.hasToken ? "purple" : "amber"}>{c.hasToken ? "token issued" : "no token"}</Pill>
                </td>
                <td className="row-actions">
                  <button onClick={() => setSelectedId(c.id)}>Select</button>
                  <button onClick={() => startEdit(c)}>Edit</button>
                  <button onClick={() => rotate(c)} disabled={busy === c.id}>
                    {busy === c.id ? "Working..." : "Rotate token"}
                  </button>
                  <button onClick={() => setEnabled(c, !c.enabled)}>
                    {c.enabled ? "Disable" : "Enable"}
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {selected && (
        <div className="card">
          <h2>Access for “{selected.name}”</h2>
          <div className="setting-row">
            <label>Scopes</label>
            <div className="pill-row">
              {selected.scopes.length === 0 && <span className="muted">none</span>}
              {selected.scopes.map((s) => <Pill key={s} tone="blue">{s}</Pill>)}
            </div>
          </div>
          <div className="setting-row">
            <label>Allowed tenants</label>
            <div className="pill-row">
              {selected.allowedTenants.length === 0
                ? <span className="muted">none — dashboard tool calls asserting a tenant are rejected</span>
                : selected.allowedTenants.map((t) => <Pill key={t} tone="purple">{t}</Pill>)}
            </div>
          </div>
          <div className="setting-row">
            <label>Granted permissions</label>
            <div className="pill-row">
              {selected.grantedPermissions.length === 0
                ? <span className="muted">none — dashboard tools requiring a permission are rejected</span>
                : selected.grantedPermissions.map((p) => <Pill key={p} tone="green">{p}</Pill>)}
            </div>
          </div>
          <div className="setting-row">
            <label>Browser origins</label>
            <div className="pill-row">
              {selected.allowedOrigins.length === 0 && <span className="muted">any</span>}
              {selected.allowedOrigins.map((o) => <Pill key={o} tone="gray">{o}</Pill>)}
            </div>
          </div>
          <div className="setting-row">
            <label>Peer hosts</label>
            <div className="pill-row">
              {selected.allowedPeerHosts.length === 0 && <span className="muted">any</span>}
              {selected.allowedPeerHosts.map((h) => <Pill key={h} tone="gray">{h}</Pill>)}
            </div>
          </div>
          <div className="setting-row">
            <button onClick={() => startEdit(selected)}>Edit access</button>
          </div>
        </div>
      )}

      {selected && (
        <div className="card token-reveal">
          <h2>Snippets</h2>
          <p className="muted">
            Connector tokens are shown once after rotation. Rotate the selected connector if you need a fresh token.
          </p>
          {revealToken && (
            <p className="connector-token">
              New token for “{selected.name}”: <code>{revealToken}</code>
            </p>
          )}
          <div className="setting-row">
            <label>API base</label>
            <input value={apiBase} onChange={(e) => setApiBase(e.target.value)} />
          </div>
          <div className="mode-toggle">
            <button className={tab === "mcp" ? "on" : ""} onClick={() => setTab("mcp")}>MCP config</button>
            <button className={tab === "curl" ? "on" : ""} onClick={() => setTab("curl")}>Federation cURL</button>
            <button className={tab === "peer" ? "on" : ""} onClick={() => setTab("peer")}>Peer registration</button>
          </div>
          <div className="snippet">
            <div className="snippet-actions"><CopyButton text={snippet} /></div>
            <pre>{snippet}</pre>
          </div>
        </div>
      )}

      {selected && (
        <div className="card">
          <h2>Recent connector events</h2>
          {events.length === 0 && <p className="muted">No events recorded yet.</p>}
          {events.map((event) => (
            <div key={event.id} className="diff-line">
              <Pill tone={event.status === "200" ? "green" : event.status === "403" ? "amber" : "gray"}>
                {event.status}
              </Pill>
              <span>{event.eventType}{event.scope ? ` - ${event.scope}` : ""}</span>
              <span className="muted">{event.createdAt ?? ""}</span>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
