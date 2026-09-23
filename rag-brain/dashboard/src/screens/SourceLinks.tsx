import React, { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { SourceLinkDto, SourceLinkRequest } from "../types";
import { ErrorNote, Pill, RowMenu, Status } from "../components";

const AUTHORITIES = ["PRIMARY", "SECONDARY", "BACKGROUND"];
const SURFACES = ["BOTH", "PUBLIC", "INTERNAL"];

function toList(text: string): string[] {
  return text.split(",").map((s) => s.trim()).filter((s) => s.length > 0);
}

function emptyForm(): SourceLinkRequest {
  return {
    name: "",
    url: "",
    domain: null,
    authority: "PRIMARY",
    topics: [],
    freshnessRequired: false,
    allowedUse: [],
    doNotUseFor: [],
    surface: "BOTH",
  };
}

function authorityTone(authority: string): "blue" | "purple" | "gray" {
  if (authority === "PRIMARY") return "blue";
  if (authority === "SECONDARY") return "purple";
  return "gray";
}

export default function SourceLinks() {
  const [links, setLinks] = useState<SourceLinkDto[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [menuId, setMenuId] = useState<string | null>(null);

  // create form
  const [showAdd, setShowAdd] = useState(false);
  const [addBusy, setAddBusy] = useState(false);
  const [addForm, setAddForm] = useState<SourceLinkRequest>(emptyForm());
  const [addTopics, setAddTopics] = useState("");
  const [addAllowed, setAddAllowed] = useState("");
  const [addDoNot, setAddDoNot] = useState("");

  // edit modal
  const [editing, setEditing] = useState<SourceLinkDto | null>(null);
  const [editBusy, setEditBusy] = useState(false);
  const [editForm, setEditForm] = useState<SourceLinkRequest>(emptyForm());
  const [editTopics, setEditTopics] = useState("");
  const [editAllowed, setEditAllowed] = useState("");
  const [editDoNot, setEditDoNot] = useState("");

  const reload = useCallback(() => {
    api.get<SourceLinkDto[]>("/api/ai/admin/source-links")
      .then(setLinks)
      .catch((e) => setError((e as Error).message));
  }, []);

  useEffect(reload, [reload]);

  async function submitAdd(e: React.FormEvent) {
    e.preventDefault();
    if (!addForm.name.trim() || !addForm.url.trim()) return;
    setAddBusy(true);
    setError(null);
    try {
      const body: SourceLinkRequest = {
        ...addForm,
        name: addForm.name.trim(),
        url: addForm.url.trim(),
        topics: toList(addTopics),
        allowedUse: toList(addAllowed),
        doNotUseFor: toList(addDoNot),
      };
      await api.post("/api/ai/admin/source-links", body);
      setShowAdd(false);
      setAddForm(emptyForm());
      setAddTopics(""); setAddAllowed(""); setAddDoNot("");
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setAddBusy(false);
    }
  }

  function openEdit(l: SourceLinkDto) {
    setEditing(l);
    setEditForm({
      name: l.name,
      url: l.url,
      domain: l.domain,
      authority: l.authority,
      topics: l.topics,
      freshnessRequired: l.freshness_required,
      allowedUse: l.allowed_use,
      doNotUseFor: l.do_not_use_for,
      surface: l.surface,
    });
    setEditTopics(l.topics.join(", "));
    setEditAllowed(l.allowed_use.join(", "));
    setEditDoNot(l.do_not_use_for.join(", "));
  }

  async function submitEdit(e: React.FormEvent) {
    e.preventDefault();
    if (!editing || !editForm.name.trim() || !editForm.url.trim()) return;
    setEditBusy(true);
    setError(null);
    try {
      const body: SourceLinkRequest = {
        ...editForm,
        name: editForm.name.trim(),
        url: editForm.url.trim(),
        topics: toList(editTopics),
        allowedUse: toList(editAllowed),
        doNotUseFor: toList(editDoNot),
      };
      await api.patch(`/api/ai/admin/source-links/${editing.id}`, body);
      setEditing(null);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setEditBusy(false);
    }
  }

  async function remove(l: SourceLinkDto) {
    if (!window.confirm(`Delete "${l.name}"? This permanently removes the registry entry. This cannot be undone.`)) {
      return;
    }
    setBusy(l.id);
    setError(null);
    try {
      await api.del(`/api/ai/admin/source-links/${l.id}`);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function setActive(l: SourceLinkDto, active: boolean) {
    setError(null);
    try {
      await api.post(`/api/ai/admin/source-links/${l.id}/${active ? "activate" : "deactivate"}`);
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <header className="screen-head">
        <h1>Source links</h1>
        <div className="actions">
          <button onClick={() => setShowAdd((v) => !v)} disabled={busy !== null}>
            {showAdd ? "Cancel" : "Add source link"}
          </button>
        </div>
      </header>
      <ErrorNote message={error} />
      {showAdd && (
        <form className="card" onSubmit={submitAdd} style={{ display: "grid", gap: 8, marginBottom: 12 }}>
          <input placeholder="Name (e.g. Fannie Mae Selling Guide)" value={addForm.name}
                 onChange={(e) => setAddForm({ ...addForm, name: e.target.value })} required />
          <input placeholder="URL" value={addForm.url}
                 onChange={(e) => setAddForm({ ...addForm, url: e.target.value })} required />
          <input placeholder="Domain (e.g. fanniemae.com)" value={addForm.domain ?? ""}
                 onChange={(e) => setAddForm({ ...addForm, domain: e.target.value || null })} />
          <select value={addForm.authority}
                  onChange={(e) => setAddForm({ ...addForm, authority: e.target.value })}>
            {AUTHORITIES.map((a) => <option key={a} value={a}>{a.toLowerCase()}</option>)}
          </select>
          <select value={addForm.surface}
                  onChange={(e) => setAddForm({ ...addForm, surface: e.target.value })}>
            {SURFACES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
          </select>
          <input placeholder="Topics (comma-separated)" value={addTopics}
                 onChange={(e) => setAddTopics(e.target.value)} />
          <input placeholder="Allowed use (comma-separated)" value={addAllowed}
                 onChange={(e) => setAddAllowed(e.target.value)} />
          <input placeholder="Do not use for (comma-separated)" value={addDoNot}
                 onChange={(e) => setAddDoNot(e.target.value)} />
          <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <input type="checkbox" checked={addForm.freshnessRequired}
                   onChange={(e) => setAddForm({ ...addForm, freshnessRequired: e.target.checked })} />
            Freshness required
          </label>
          <button className="btn-primary" type="submit"
                  disabled={addBusy || !addForm.name.trim() || !addForm.url.trim()}>
            {addBusy ? "Saving…" : "Create source link"}
          </button>
        </form>
      )}
      <div className="tbl-wrap" role="table" aria-label="Source links"
           style={{ "--cols": "1.5fr 1fr 100px 84px 92px 84px 44px" } as React.CSSProperties}>
        <div className="tbl-head" role="row">
          <span role="columnheader">Name</span>
          <span role="columnheader">Topics</span>
          <span role="columnheader">Authority</span>
          <span role="columnheader">Surface</span>
          <span role="columnheader">Freshness</span>
          <span role="columnheader">Status</span>
          <span role="columnheader" aria-label="Actions" />
        </div>
        {links.map((l) => (
          <div key={l.id} className={`tbl-row${l.active ? "" : " is-muted"}`} role="row">
            <span role="cell">
              <span className="cell-title" title={l.url}>{l.name}</span>
              {/* The field most worth surfacing in a compliance context. */}
              {l.do_not_use_for.length > 0
                ? <span className="cell-sub danger">do not use for: {l.do_not_use_for.join(", ")}</span>
                : <span className="cell-sub">{l.domain ?? l.url}</span>}
            </span>
            <span role="cell" className="chips" style={{ margin: 0 }}>
              {l.topics.slice(0, 3).map((t) => <Pill key={t} tone="gray">{t}</Pill>)}
              {l.topics.length > 3 && <span className="cell-sub">+{l.topics.length - 3}</span>}
              {l.topics.length === 0 && <span className="cell-num">—</span>}
            </span>
            <span role="cell"><Pill tone={authorityTone(l.authority)}>{l.authority.toLowerCase()}</Pill></span>
            <span role="cell" className="cell-num">{l.surface.toLowerCase()}</span>
            <span role="cell" className={`cell-num${l.freshness_required ? " good" : ""}`}>
              {l.freshness_required ? "required" : "—"}
            </span>
            <span role="cell"><Status kind={l.active ? "active" : "inactive"} /></span>
            <span role="cell" className="row-menu-cell">
              <RowMenu id={l.id} openId={menuId} onToggle={setMenuId}>
                <button onClick={() => openEdit(l)}>Edit</button>
                <button onClick={() => setActive(l, !l.active)}>
                  {l.active ? "Deactivate" : "Activate"}
                </button>
                <button className="danger" onClick={() => remove(l)} disabled={busy === l.id}>
                  Delete
                </button>
              </RowMenu>
            </span>
          </div>
        ))}
        {links.length === 0 && <div className="tbl-foot">No source links yet.</div>}
      </div>
      {editing && (
        <div className="modal-overlay" onClick={() => setEditing(null)}>
          <form className="card" onClick={(e) => e.stopPropagation()} onSubmit={submitEdit}
                style={{ display: "grid", gap: 8, maxWidth: 460, margin: "10vh auto" }}>
            <h3 style={{ margin: 0 }}>Edit source link</h3>
            <input placeholder="Name" value={editForm.name}
                   onChange={(e) => setEditForm({ ...editForm, name: e.target.value })} required />
            <input placeholder="URL" value={editForm.url}
                   onChange={(e) => setEditForm({ ...editForm, url: e.target.value })} required />
            <input placeholder="Domain" value={editForm.domain ?? ""}
                   onChange={(e) => setEditForm({ ...editForm, domain: e.target.value || null })} />
            <select value={editForm.authority}
                    onChange={(e) => setEditForm({ ...editForm, authority: e.target.value })}>
              {AUTHORITIES.map((a) => <option key={a} value={a}>{a.toLowerCase()}</option>)}
            </select>
            <select value={editForm.surface}
                    onChange={(e) => setEditForm({ ...editForm, surface: e.target.value })}>
              {SURFACES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
            </select>
            <input placeholder="Topics (comma-separated)" value={editTopics}
                   onChange={(e) => setEditTopics(e.target.value)} />
            <input placeholder="Allowed use (comma-separated)" value={editAllowed}
                   onChange={(e) => setEditAllowed(e.target.value)} />
            <input placeholder="Do not use for (comma-separated)" value={editDoNot}
                   onChange={(e) => setEditDoNot(e.target.value)} />
            <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
              <input type="checkbox" checked={editForm.freshnessRequired}
                     onChange={(e) => setEditForm({ ...editForm, freshnessRequired: e.target.checked })} />
              Freshness required
            </label>
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn-primary" type="submit"
                      disabled={editBusy || !editForm.name.trim() || !editForm.url.trim()}>
                {editBusy ? "Saving…" : "Save"}
              </button>
              <button type="button" onClick={() => setEditing(null)}>Cancel</button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}
