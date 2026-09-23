import { useCallback, useEffect, useMemo, useState } from "react";
import { brainsApi, toolAdaptersApi, toolDefinitionsApi } from "../api";
import {
  BrainAdminDto,
  ToolAdapterConfigDto,
  ToolAdapterConfigRequest,
  ToolAdapterRun,
  ToolAuthMode,
  ToolDefinitionDto,
  ToolDefinitionRequest,
  ToolHttpMethod,
  ToolMode,
} from "../types";
import { ErrorNote, Pill, Stat, Status } from "../components";
import { dashboardToolCallCurlSnippet } from "../connect/toolSnippets";

const MODES: ToolMode[] = ["READ", "WRITE", "NAVIGATE"];
const HTTP_METHODS: ToolHttpMethod[] = ["GET", "POST", "PUT", "PATCH", "DELETE"];
const AUTH_MODES: ToolAuthMode[] = ["NONE", "BEARER_TOKEN", "API_KEY_HEADER"];

export function parseHostList(text: string): string[] {
  return text.split(/[\n,]+/).map((s) => s.trim().toLowerCase()).filter(Boolean);
}

export function parsePermissions(text: string): string[] {
  return text.split(/[\n,]+/).map((s) => s.trim()).filter(Boolean);
}

export function formatPermissions(values: string[]): string {
  return values.join("\n");
}

export function parseSchemaText(text: string): Record<string, unknown> {
  const parsed = JSON.parse(text);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("Input schema must be a JSON object");
  }
  return parsed as Record<string, unknown>;
}

function schemaText(schema: Record<string, unknown>): string {
  return JSON.stringify(schema, null, 2);
}

function parseJsonObjectText(text: string, label: string): Record<string, unknown> {
  const parsed = text.trim() ? JSON.parse(text) : {};
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(`${label} must be a JSON object`);
  }
  return parsed as Record<string, unknown>;
}

export function emptyToolForm(): ToolDefinitionRequest {
  return {
    name: "",
    description: "",
    mode: "READ",
    confirmationRequired: false,
    requiredPermissions: [],
    inputSchema: { type: "object", properties: {} },
  };
}

function modeTone(mode: ToolMode): "blue" | "purple" | "gray" {
  if (mode === "READ") return "blue";
  if (mode === "WRITE") return "purple";
  return "gray";
}

interface ToolFormState {
  values: ToolDefinitionRequest;
  permissionsText: string;
  schemaText: string;
}

interface AdapterFormState {
  enabled: boolean;
  httpMethod: ToolHttpMethod;
  urlTemplate: string;
  authMode: ToolAuthMode;
  secretRef: string;
  apiKeyHeader: string;
  staticHeadersText: string;
  requestBodyTemplateText: string;
  timeoutMs: string;
  allowedHostsText: string;
}

function formFromTool(tool: ToolDefinitionDto): ToolFormState {
  return {
    values: {
      name: tool.name,
      description: tool.description,
      mode: tool.mode,
      confirmationRequired: tool.confirmationRequired,
      requiredPermissions: tool.requiredPermissions,
      inputSchema: tool.inputSchema,
    },
    permissionsText: formatPermissions(tool.requiredPermissions),
    schemaText: schemaText(tool.inputSchema),
  };
}

function emptyFormState(): ToolFormState {
  const values = emptyToolForm();
  return {
    values,
    permissionsText: "",
    schemaText: schemaText(values.inputSchema),
  };
}

function emptyAdapterForm(): AdapterFormState {
  return {
    enabled: false,
    httpMethod: "GET",
    urlTemplate: "",
    authMode: "NONE",
    secretRef: "",
    apiKeyHeader: "",
    staticHeadersText: "{}",
    requestBodyTemplateText: "{}",
    timeoutMs: "5000",
    allowedHostsText: "",
  };
}

function adapterFormFromDto(adapter: ToolAdapterConfigDto): AdapterFormState {
  return {
    enabled: adapter.enabled,
    httpMethod: adapter.httpMethod,
    urlTemplate: adapter.urlTemplate,
    authMode: adapter.authMode,
    secretRef: adapter.secretRef ?? "",
    apiKeyHeader: adapter.apiKeyHeader ?? "",
    staticHeadersText: JSON.stringify(adapter.staticHeaders ?? {}, null, 2),
    requestBodyTemplateText: JSON.stringify(adapter.requestBodyTemplate ?? {}, null, 2),
    timeoutMs: String(adapter.timeoutMs),
    allowedHostsText: (adapter.allowedHosts ?? []).join("\n"),
  };
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button onClick={async () => {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    }}>
      {copied ? "Copied" : "Copy"}
    </button>
  );
}

export default function Tools() {
  const [brains, setBrains] = useState<BrainAdminDto[]>([]);
  const [selectedSlug, setSelectedSlug] = useState("");
  const [tools, setTools] = useState<ToolDefinitionDto[]>([]);
  const [editing, setEditing] = useState<ToolDefinitionDto | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<ToolFormState>(emptyFormState());
  const [adapterForm, setAdapterForm] = useState<AdapterFormState>(emptyAdapterForm());
  const [apiBase, setApiBase] = useState(window.location.origin);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [adapterError, setAdapterError] = useState<string | null>(null);
  const [adapterRuns, setAdapterRuns] = useState<ToolAdapterRun[]>([]);

  const selectedBrain = useMemo(
    () => brains.find((b) => b.slug === selectedSlug) ?? null,
    [brains, selectedSlug],
  );

  const counts = useMemo(() => ({
    total: tools.length,
    active: tools.filter((t) => t.active).length,
    read: tools.filter((t) => t.mode === "READ").length,
    write: tools.filter((t) => t.mode === "WRITE").length,
    navigate: tools.filter((t) => t.mode === "NAVIGATE").length,
  }), [tools]);

  const selectedTool = useMemo(() => editing ?? tools[0] ?? null, [editing, tools]);

  const reloadTools = useCallback((slug: string) => {
    if (!slug) return;
    toolDefinitionsApi.list(slug)
      .then(setTools)
      .catch((e) => setError((e as Error).message));
  }, []);

  useEffect(() => {
    brainsApi.list()
      .then((brainList) => {
        setBrains(brainList);
        const nextSlug = brainList.find((b) => b.isDefault)?.slug ?? brainList[0]?.slug ?? "";
        setSelectedSlug((current) => current || nextSlug);
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  useEffect(() => {
    reloadTools(selectedSlug);
  }, [reloadTools, selectedSlug]);

  useEffect(() => {
    if (!selectedSlug || !selectedTool) {
      setAdapterForm(emptyAdapterForm());
      return;
    }
    let active = true;
    setAdapterError(null);
    setAdapterRuns([]);
    toolAdaptersApi.get(selectedSlug, selectedTool.name)
      .then((adapter) => {
        if (active) setAdapterForm(adapter ? adapterFormFromDto(adapter) : emptyAdapterForm());
      })
      .catch((e) => {
        if (active) setAdapterError((e as Error).message);
      });
    toolAdaptersApi.runs(selectedSlug, selectedTool.name)
      .then((runs) => {
        if (active) setAdapterRuns(runs);
      })
      .catch(() => {
        // A failed runs fetch must not break the adapter form.
        if (active) setAdapterRuns([]);
      });
    return () => {
      active = false;
    };
  }, [selectedSlug, selectedTool]);

  function set<K extends keyof ToolDefinitionRequest>(key: K, value: ToolDefinitionRequest[K]) {
    setForm((current) => ({
      ...current,
      values: {
        ...current.values,
        [key]: value,
        confirmationRequired: key === "mode" && value === "WRITE"
          ? true
          : current.values.confirmationRequired,
      },
    }));
  }

  function setAdapter<K extends keyof AdapterFormState>(key: K, value: AdapterFormState[K]) {
    setAdapterForm((current) => ({ ...current, [key]: value }));
  }

  function openCreate() {
    setEditing(null);
    setForm(emptyFormState());
    setFormError(null);
    setShowForm(true);
  }

  function openEdit(tool: ToolDefinitionDto) {
    setEditing(tool);
    setForm(formFromTool(tool));
    setFormError(null);
    setShowForm(true);
  }

  async function save() {
    if (!selectedSlug) return;
    setBusy("save");
    setError(null);
    setFormError(null);
    try {
      const inputSchema = parseSchemaText(form.schemaText);
      const body: ToolDefinitionRequest = {
        ...form.values,
        name: form.values.name.trim(),
        description: form.values.description.trim(),
        confirmationRequired: form.values.mode === "WRITE" || form.values.confirmationRequired,
        requiredPermissions: parsePermissions(form.permissionsText),
        inputSchema,
      };
      if (!body.name || !body.description) {
        throw new Error("Name and description are required");
      }
      if (editing) {
        await toolDefinitionsApi.update(selectedSlug, editing.id, body);
      } else {
        await toolDefinitionsApi.create(selectedSlug, body);
      }
      setShowForm(false);
      setEditing(null);
      reloadTools(selectedSlug);
    } catch (e) {
      setFormError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function setActive(tool: ToolDefinitionDto, active: boolean) {
    if (!selectedSlug) return;
    setBusy(tool.id);
    setError(null);
    try {
      await (active
        ? toolDefinitionsApi.activate(selectedSlug, tool.id)
        : toolDefinitionsApi.deactivate(selectedSlug, tool.id));
      reloadTools(selectedSlug);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function remove(tool: ToolDefinitionDto) {
    if (!selectedSlug) return;
    if (!window.confirm(`Delete "${tool.name}"? This removes the tool manifest for ${selectedSlug}.`)) {
      return;
    }
    setBusy(tool.id);
    setError(null);
    try {
      await toolDefinitionsApi.remove(selectedSlug, tool.id);
      reloadTools(selectedSlug);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function saveAdapter() {
    if (!selectedSlug || !selectedTool) return;
    setBusy("adapter-save");
    setAdapterError(null);
    try {
      const timeoutMs = Number(adapterForm.timeoutMs);
      if (!Number.isFinite(timeoutMs)) {
        throw new Error("Timeout ms must be a number");
      }
      const allowedHosts = parseHostList(adapterForm.allowedHostsText);
      if (adapterForm.enabled && allowedHosts.length === 0) {
        throw new Error("Allowed hosts are required when the adapter is enabled");
      }
      const body: ToolAdapterConfigRequest = {
        enabled: adapterForm.enabled,
        httpMethod: adapterForm.httpMethod,
        urlTemplate: adapterForm.urlTemplate.trim(),
        authMode: adapterForm.authMode,
        secretRef: adapterForm.secretRef.trim(),
        apiKeyHeader: adapterForm.apiKeyHeader.trim(),
        staticHeaders: parseJsonObjectText(adapterForm.staticHeadersText, "Static headers JSON"),
        requestBodyTemplate: parseJsonObjectText(
          adapterForm.requestBodyTemplateText,
          "Request body template JSON",
        ),
        timeoutMs,
        allowedHosts,
      };
      const saved = await toolAdaptersApi.upsert(selectedSlug, selectedTool.name, body);
      setAdapterForm(adapterFormFromDto(saved));
    } catch (e) {
      setAdapterError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function clearAdapter() {
    if (!selectedSlug || !selectedTool) return;
    setBusy("adapter-clear");
    setAdapterError(null);
    try {
      await toolAdaptersApi.remove(selectedSlug, selectedTool.name);
      setAdapterForm(emptyAdapterForm());
    } catch (e) {
      setAdapterError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const snippet = selectedTool && selectedBrain
    ? dashboardToolCallCurlSnippet({ apiBase, slug: selectedBrain.slug, tool: selectedTool })
    : "";

  return (
    <>
      <header className="screen-head">
        <h1>Tools</h1>
        <div className="actions">
          <button className="btn-primary" onClick={openCreate}>New tool</button>
        </div>
      </header>
      <ErrorNote message={error} />

      <div className="card">
        <div className="setting-row">
          <label htmlFor="tools-brain">Brain</label>
          <select id="tools-brain" value={selectedSlug} onChange={(e) => setSelectedSlug(e.target.value)}>
            {brains.map((brain) => (
              <option key={brain.id} value={brain.slug}>{brain.displayName} ({brain.slug})</option>
            ))}
          </select>
        </div>
        <div className="stats-row">
          <Stat label="Total" value={counts.total} />
          <Stat label="Active" value={`${counts.active} active`} />
          <Stat label="Read" value={counts.read} />
          <Stat label="Write" value={counts.write} />
          <Stat label="Navigate" value={counts.navigate} />
        </div>
      </div>

      {showForm && (
        <div className="card" style={{ display: "grid", gap: 8, marginBottom: 12 }}>
          <h2>{editing ? "Edit tool" : "Create tool"}</h2>
          <ErrorNote message={formError} />
          <div className="setting-row">
            <label htmlFor="tool-name">Name</label>
            <input id="tool-name" value={form.values.name}
                   onChange={(e) => set("name", e.target.value)} />
          </div>
          <div className="setting-row">
            <label htmlFor="tool-description">Description</label>
            <input id="tool-description" value={form.values.description}
                   onChange={(e) => set("description", e.target.value)} />
          </div>
          <div className="setting-row">
            <label htmlFor="tool-mode">Mode</label>
            <select id="tool-mode" value={form.values.mode}
                    onChange={(e) => set("mode", e.target.value as ToolMode)}>
              {MODES.map((mode) => <option key={mode} value={mode}>{mode.toLowerCase()}</option>)}
            </select>
          </div>
          <div className="setting-row">
            <label>Confirmation</label>
            <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
              <input type="checkbox"
                     checked={form.values.mode === "WRITE" || form.values.confirmationRequired}
                     disabled={form.values.mode === "WRITE"}
                     onChange={(e) => set("confirmationRequired", e.target.checked)} />
              Confirmation required
            </label>
          </div>
          <div className="setting-row">
            <label htmlFor="tool-permissions">Required permissions</label>
            <textarea id="tool-permissions" value={form.permissionsText}
                      onChange={(e) => setForm((current) => ({ ...current, permissionsText: e.target.value }))}
                      placeholder={"dashboard.loans.read\ndashboard.tasks.read"} />
          </div>
          <div className="setting-row">
            <label htmlFor="tool-schema">Input schema</label>
            <textarea id="tool-schema" value={form.schemaText}
                      onChange={(e) => setForm((current) => ({ ...current, schemaText: e.target.value }))}
                      rows={8} />
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <button className="btn-primary" onClick={save}
                    disabled={busy === "save" || !form.values.name.trim() || !form.values.description.trim()}>
              {busy === "save" ? "Saving..." : editing ? "Save tool" : "Create tool"}
            </button>
            <button onClick={() => setShowForm(false)}>Cancel</button>
          </div>
        </div>
      )}

      <table className="tbl">
        <thead>
          <tr><th>Name</th><th>Mode</th><th>Permissions</th><th>Status</th><th>Updated</th><th></th></tr>
        </thead>
        <tbody>
          {tools.length === 0 && (
            <tr><td colSpan={6} className="muted">No tools configured for this brain.</td></tr>
          )}
          {tools.map((tool) => (
            <tr key={tool.id}>
              <td title={tool.description}>{tool.name}</td>
              <td><Pill tone={modeTone(tool.mode)}>{tool.mode.toLowerCase()}</Pill></td>
              <td>{tool.requiredPermissions.length ? tool.requiredPermissions.join(", ") : "none"}</td>
              <td>
                <Status kind={tool.active ? "active" : "inactive"} />
                {" "}
                {tool.confirmationRequired && <Pill tone="amber">confirm</Pill>}
              </td>
              <td>{tool.updatedAt ?? ""}</td>
              <td className="row-actions">
                <button onClick={() => openEdit(tool)}>Edit</button>
                <button onClick={() => setActive(tool, !tool.active)} disabled={busy === tool.id}>
                  {tool.active ? "Deactivate" : "Activate"}
                </button>
                <button className="danger" onClick={() => remove(tool)} disabled={busy === tool.id}>
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {selectedTool && (
        <div className="card" style={{ display: "grid", gap: 8, marginTop: 12 }}>
          <h2>Adapter</h2>
          <ErrorNote message={adapterError} />
          <div className="setting-row">
            <label htmlFor="adapter-enabled">Adapter enabled</label>
            <input id="adapter-enabled" type="checkbox"
                   checked={adapterForm.enabled}
                   onChange={(e) => setAdapter("enabled", e.target.checked)} />
          </div>
          <div className="setting-row">
            <label htmlFor="adapter-method">HTTP method</label>
            <select id="adapter-method" value={adapterForm.httpMethod}
                    onChange={(e) => setAdapter("httpMethod", e.target.value as ToolHttpMethod)}>
              {HTTP_METHODS.map((method) => <option key={method} value={method}>{method}</option>)}
            </select>
          </div>
          <div className="setting-row">
            <label htmlFor="adapter-url">URL template</label>
            <input id="adapter-url" value={adapterForm.urlTemplate}
                   onChange={(e) => setAdapter("urlTemplate", e.target.value)} />
          </div>
          <div className="setting-row">
            <label htmlFor="adapter-allowed-hosts">Allowed hosts (one per line)</label>
            <textarea id="adapter-allowed-hosts" value={adapterForm.allowedHostsText}
                      onChange={(e) => setAdapter("allowedHostsText", e.target.value)}
                      rows={3}
                      placeholder={"dashboard.example.com\napi.example.com"} />
            <p className="hint">Required when enabled. The rendered request URL host must exactly match one of these, or the call is blocked before any request is sent.</p>
          </div>
          <div className="setting-row">
            <label htmlFor="adapter-auth">Auth mode</label>
            <select id="adapter-auth" value={adapterForm.authMode}
                    onChange={(e) => setAdapter("authMode", e.target.value as ToolAuthMode)}>
              {AUTH_MODES.map((mode) => <option key={mode} value={mode}>{mode}</option>)}
            </select>
          </div>
          <div className="setting-row">
            <label htmlFor="adapter-secret-ref">Secret ref</label>
            <input id="adapter-secret-ref" value={adapterForm.secretRef}
                   onChange={(e) => setAdapter("secretRef", e.target.value)} />
          </div>
          <div className="setting-row">
            <label htmlFor="adapter-api-key-header">API key header</label>
            <input id="adapter-api-key-header" value={adapterForm.apiKeyHeader}
                   onChange={(e) => setAdapter("apiKeyHeader", e.target.value)} />
          </div>
          <div className="setting-row">
            <label htmlFor="adapter-static-headers">Static headers JSON</label>
            <textarea id="adapter-static-headers" value={adapterForm.staticHeadersText}
                      onChange={(e) => setAdapter("staticHeadersText", e.target.value)}
                      rows={5} />
          </div>
          <div className="setting-row">
            <label htmlFor="adapter-body-template">Request body template JSON</label>
            <textarea id="adapter-body-template" value={adapterForm.requestBodyTemplateText}
                      onChange={(e) => setAdapter("requestBodyTemplateText", e.target.value)}
                      rows={5} />
          </div>
          <div className="setting-row">
            <label htmlFor="adapter-timeout">Timeout ms</label>
            <input id="adapter-timeout" value={adapterForm.timeoutMs}
                   onChange={(e) => setAdapter("timeoutMs", e.target.value)} />
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <button className="btn-primary" onClick={saveAdapter} disabled={busy === "adapter-save"}>
              {busy === "adapter-save" ? "Saving..." : "Save adapter"}
            </button>
            <button onClick={clearAdapter} disabled={busy === "adapter-clear"}>Clear adapter</button>
          </div>
          <div className="setting-row">
            <label>Recent runs</label>
            {adapterRuns.length === 0 ? (
              <p className="muted">No adapter runs recorded yet.</p>
            ) : (
              <table className="tbl">
                <thead>
                  <tr><th>When</th><th>Status</th><th>HTTP</th><th>Host</th><th>ms</th><th>Error</th></tr>
                </thead>
                <tbody>
                  {adapterRuns.map((run) => (
                    <tr key={run.id}>
                      <td className="muted">{run.createdAt ?? ""}</td>
                      <td>
                        <Pill tone={run.status === "SUCCEEDED" ? "green" : run.status === "BLOCKED" ? "amber" : "gray"}>
                          {run.status}
                        </Pill>
                      </td>
                      <td>{run.httpStatusCode ?? "—"}</td>
                      <td>{run.targetHost ?? "—"}</td>
                      <td>{run.durationMs}</td>
                      <td>{run.errorType ?? ""}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      )}

      {selectedTool && (
        <div className="card token-reveal">
          <h2>Connector test call</h2>
          <div className="setting-row">
            <label htmlFor="tools-api-base">API base</label>
            <input id="tools-api-base" value={apiBase} onChange={(e) => setApiBase(e.target.value)} />
          </div>
          <div className="snippet">
            <div className="snippet-actions"><CopyButton text={snippet} /></div>
            <pre>{snippet}</pre>
          </div>
        </div>
      )}
    </>
  );
}
