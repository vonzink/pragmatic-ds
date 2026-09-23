import React, { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { LinkRef, PageGuideDto, PageGuideRequest, SourceLinkDto } from "../types";
import { ErrorNote, RowMenu, Status } from "../components";

const SURFACES = ["BOTH", "PUBLIC", "INTERNAL"];

function toList(text: string): string[] {
  return text.split(",").map((s) => s.trim()).filter((s) => s.length > 0);
}

function cleanLinks(links: LinkRef[]): LinkRef[] {
  return links
    .map((l) => ({ label: l.label.trim(), url: l.url.trim() }))
    .filter((l) => l.label.length > 0 || l.url.length > 0);
}

function emptyForm(): PageGuideRequest {
  return {
    route: null,
    title: "",
    purpose: "",
    surface: "BOTH",
    userIntents: [],
    allowedGuidance: [],
    internalLinks: [],
    sourceLinkIds: [],
    topics: [],
  };
}

interface FormState {
  form: PageGuideRequest;
  setForm: (f: PageGuideRequest) => void;
  intents: string;
  setIntents: (s: string) => void;
  guidance: string;
  setGuidance: (s: string) => void;
  topics: string;
  setTopics: (s: string) => void;
  links: LinkRef[];
  setLinks: (l: LinkRef[]) => void;
  availableLinks: SourceLinkDto[];
}

function GuideFormFields(s: FormState) {
  return (
    <>
      <input placeholder="Route (optional, e.g. /loans/fha)" value={s.form.route ?? ""}
             onChange={(e) => s.setForm({ ...s.form, route: e.target.value || null })} />
      <input placeholder="Title (e.g. FHA Loans)" value={s.form.title}
             onChange={(e) => s.setForm({ ...s.form, title: e.target.value })} required />
      <textarea placeholder="Purpose (what this page/guide is for)" value={s.form.purpose}
                onChange={(e) => s.setForm({ ...s.form, purpose: e.target.value })} required rows={3} />
      <select value={s.form.surface}
              onChange={(e) => s.setForm({ ...s.form, surface: e.target.value })}>
        {SURFACES.map((x) => <option key={x} value={x}>{x.toLowerCase()}</option>)}
      </select>
      <input placeholder="User intents (comma-separated)" value={s.intents}
             onChange={(e) => s.setIntents(e.target.value)} />
      <input placeholder="Allowed guidance (comma-separated)" value={s.guidance}
             onChange={(e) => s.setGuidance(e.target.value)} />
      <input placeholder="Topics (comma-separated)" value={s.topics}
             onChange={(e) => s.setTopics(e.target.value)} />

      <div style={{ display: "grid", gap: 6 }}>
        <strong style={{ fontSize: 13 }}>Internal links</strong>
        {s.links.map((l, i) => (
          <div key={i} style={{ display: "flex", gap: 6 }}>
            <input placeholder="Label" value={l.label}
                   onChange={(e) => {
                     const next = [...s.links];
                     next[i] = { ...next[i], label: e.target.value };
                     s.setLinks(next);
                   }} />
            <input placeholder="URL (e.g. /apply)" value={l.url}
                   onChange={(e) => {
                     const next = [...s.links];
                     next[i] = { ...next[i], url: e.target.value };
                     s.setLinks(next);
                   }} />
            <button type="button" className="danger"
                    onClick={() => s.setLinks(s.links.filter((_, j) => j !== i))}>
              Remove
            </button>
          </div>
        ))}
        <button type="button" onClick={() => s.setLinks([...s.links, { label: "", url: "" }])}>
          Add link
        </button>
      </div>

      <div style={{ display: "grid", gap: 6 }}>
        <strong style={{ fontSize: 13 }}>Source links (registry)</strong>
        <select multiple value={s.form.sourceLinkIds} style={{ minHeight: 96 }}
                onChange={(e) =>
                  s.setForm({
                    ...s.form,
                    sourceLinkIds: Array.from(e.target.selectedOptions, (o) => o.value),
                  })}>
          {s.availableLinks.map((sl) => (
            <option key={sl.id} value={sl.id}>{sl.name}</option>
          ))}
        </select>
      </div>
    </>
  );
}

export default function PageGuides() {
  const [guides, setGuides] = useState<PageGuideDto[]>([]);
  const [availableLinks, setAvailableLinks] = useState<SourceLinkDto[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [menuId, setMenuId] = useState<string | null>(null);

  // create form
  const [showAdd, setShowAdd] = useState(false);
  const [addBusy, setAddBusy] = useState(false);
  const [addForm, setAddForm] = useState<PageGuideRequest>(emptyForm());
  const [addIntents, setAddIntents] = useState("");
  const [addGuidance, setAddGuidance] = useState("");
  const [addTopics, setAddTopics] = useState("");
  const [addLinks, setAddLinks] = useState<LinkRef[]>([]);

  // edit modal
  const [editing, setEditing] = useState<PageGuideDto | null>(null);
  const [editBusy, setEditBusy] = useState(false);
  const [editForm, setEditForm] = useState<PageGuideRequest>(emptyForm());
  const [editIntents, setEditIntents] = useState("");
  const [editGuidance, setEditGuidance] = useState("");
  const [editTopics, setEditTopics] = useState("");
  const [editLinks, setEditLinks] = useState<LinkRef[]>([]);

  const reload = useCallback(() => {
    api.get<PageGuideDto[]>("/api/ai/admin/page-guides")
      .then(setGuides)
      .catch((e) => setError((e as Error).message));
  }, []);

  useEffect(() => {
    reload();
    api.get<SourceLinkDto[]>("/api/ai/admin/source-links")
      .then(setAvailableLinks)
      .catch((e) => setError((e as Error).message));
  }, [reload]);

  async function submitAdd(e: React.FormEvent) {
    e.preventDefault();
    if (!addForm.title.trim() || !addForm.purpose.trim()) return;
    setAddBusy(true);
    setError(null);
    try {
      const body: PageGuideRequest = {
        ...addForm,
        title: addForm.title.trim(),
        purpose: addForm.purpose.trim(),
        userIntents: toList(addIntents),
        allowedGuidance: toList(addGuidance),
        topics: toList(addTopics),
        internalLinks: cleanLinks(addLinks),
      };
      await api.post("/api/ai/admin/page-guides", body);
      setShowAdd(false);
      setAddForm(emptyForm());
      setAddIntents(""); setAddGuidance(""); setAddTopics(""); setAddLinks([]);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setAddBusy(false);
    }
  }

  function openEdit(g: PageGuideDto) {
    setEditing(g);
    setEditForm({
      route: g.route,
      title: g.title,
      purpose: g.purpose,
      surface: g.surface,
      userIntents: g.user_intents,
      allowedGuidance: g.allowed_guidance,
      internalLinks: g.internal_links,
      sourceLinkIds: g.source_link_ids,
      topics: g.topics,
    });
    setEditIntents(g.user_intents.join(", "));
    setEditGuidance(g.allowed_guidance.join(", "));
    setEditTopics(g.topics.join(", "));
    setEditLinks(g.internal_links.map((l) => ({ ...l })));
  }

  async function submitEdit(e: React.FormEvent) {
    e.preventDefault();
    if (!editing || !editForm.title.trim() || !editForm.purpose.trim()) return;
    setEditBusy(true);
    setError(null);
    try {
      const body: PageGuideRequest = {
        ...editForm,
        title: editForm.title.trim(),
        purpose: editForm.purpose.trim(),
        userIntents: toList(editIntents),
        allowedGuidance: toList(editGuidance),
        topics: toList(editTopics),
        internalLinks: cleanLinks(editLinks),
      };
      await api.patch(`/api/ai/admin/page-guides/${editing.id}`, body);
      setEditing(null);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setEditBusy(false);
    }
  }

  async function remove(g: PageGuideDto) {
    if (!window.confirm(`Delete "${g.title}"? This permanently removes the page guide. This cannot be undone.`)) {
      return;
    }
    setBusy(g.id);
    setError(null);
    try {
      await api.del(`/api/ai/admin/page-guides/${g.id}`);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function setActive(g: PageGuideDto, active: boolean) {
    setError(null);
    try {
      await api.post(`/api/ai/admin/page-guides/${g.id}/${active ? "activate" : "deactivate"}`);
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <header className="screen-head">
        <h1>Page guides</h1>
        <div className="actions">
          <button onClick={() => setShowAdd((v) => !v)} disabled={busy !== null}>
            {showAdd ? "Cancel" : "Add page guide"}
          </button>
        </div>
      </header>
      <ErrorNote message={error} />
      {showAdd && (
        <form className="card" onSubmit={submitAdd} style={{ display: "grid", gap: 8, marginBottom: 12 }}>
          <GuideFormFields
            form={addForm} setForm={setAddForm}
            intents={addIntents} setIntents={setAddIntents}
            guidance={addGuidance} setGuidance={setAddGuidance}
            topics={addTopics} setTopics={setAddTopics}
            links={addLinks} setLinks={setAddLinks}
            availableLinks={availableLinks} />
          <button className="btn-primary" type="submit"
                  disabled={addBusy || !addForm.title.trim() || !addForm.purpose.trim()}>
            {addBusy ? "Saving…" : "Create page guide"}
          </button>
        </form>
      )}
      <div className="tbl-wrap" role="table" aria-label="Page guides"
           style={{ "--cols": "1.6fr 1fr 90px 90px 44px" } as React.CSSProperties}>
        <div className="tbl-head" role="row">
          <span role="columnheader">Title</span>
          <span role="columnheader">Route</span>
          <span role="columnheader">Surface</span>
          <span role="columnheader">Status</span>
          <span role="columnheader" aria-label="Actions" />
        </div>
        {guides.map((g) => (
          <div key={g.id} className={`tbl-row${g.active ? "" : " is-muted"}`} role="row">
            <span role="cell">
              <span className="cell-title">{g.title}</span>
              <span className="cell-sub plain" title={g.purpose}>{g.purpose}</span>
            </span>
            <span role="cell" className="cell-num">
              {g.route ?? <span style={{ color: "var(--muted-2)" }}>topic-matched</span>}
            </span>
            <span role="cell" className="cell-num">{g.surface.toLowerCase()}</span>
            <span role="cell"><Status kind={g.active ? "active" : "inactive"} /></span>
            <span role="cell" className="row-menu-cell">
              <RowMenu id={g.id} openId={menuId} onToggle={setMenuId}>
                <button onClick={() => openEdit(g)}>Edit</button>
                <button onClick={() => setActive(g, !g.active)}>
                  {g.active ? "Deactivate" : "Activate"}
                </button>
                <button className="danger" onClick={() => remove(g)} disabled={busy === g.id}>
                  Delete
                </button>
              </RowMenu>
            </span>
          </div>
        ))}
        {guides.length === 0 && <div className="tbl-foot">No page guides yet.</div>}
      </div>
      {editing && (
        <div className="modal-overlay" onClick={() => setEditing(null)}>
          <form className="card" onClick={(e) => e.stopPropagation()} onSubmit={submitEdit}
                style={{ display: "grid", gap: 8, maxWidth: 520, margin: "8vh auto" }}>
            <h3 style={{ margin: 0 }}>Edit page guide</h3>
            <GuideFormFields
              form={editForm} setForm={setEditForm}
              intents={editIntents} setIntents={setEditIntents}
              guidance={editGuidance} setGuidance={setEditGuidance}
              topics={editTopics} setTopics={setEditTopics}
              links={editLinks} setLinks={setEditLinks}
              availableLinks={availableLinks} />
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn-primary" type="submit"
                      disabled={editBusy || !editForm.title.trim() || !editForm.purpose.trim()}>
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
