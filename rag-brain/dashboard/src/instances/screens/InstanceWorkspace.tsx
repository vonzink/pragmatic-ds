import { useEffect, useState } from "react";
import { NavLink, Navigate, Route, Routes, useParams } from "react-router-dom";
import { instanceApi } from "../api";
import type { InstanceDetail } from "../types";
import InstanceBreadcrumbs from "../components/InstanceBreadcrumbs";
import InstanceWorkbench from "./InstanceWorkbench";
import InstanceCompare from "./InstanceCompare";
import InstanceConfiguration from "./InstanceConfiguration";
import InstanceReleases from "./InstanceReleases";

/**
 * One instance, and the four things you do with it.
 *
 * The scope comes from the path — `/instances/:brainId/:instanceSlug/*` — and from nowhere else.
 * No sidebar selection, no ambient "current brain". That is what makes a workspace URL something
 * you can paste to a colleague and have them see the same instance.
 *
 * It is a shell on purpose. Each tab owns its own state and arrives with its own task; the
 * alternative is the thing this phase exists to replace, a single screen that grew until every
 * feature had to know about every other one.
 */
export default function InstanceWorkspace() {
  const { brainId = "", instanceSlug = "" } = useParams();
  const [instance, setInstance] = useState<InstanceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    instanceApi
      .get<InstanceDetail>(
        `/api/ai/admin/instances/${encodeURIComponent(instanceSlug)}`
        + `?brain=${encodeURIComponent(brainId)}`)
      .then((detail) => { if (!cancelled) { setInstance(detail); setLoading(false); } })
      .catch((e) => { if (!cancelled) { setError((e as Error).message); setLoading(false); } });

    return () => { cancelled = true; };
  }, [brainId, instanceSlug]);

  const base = `/instances/${encodeURIComponent(brainId)}/${encodeURIComponent(instanceSlug)}`;

  return (
    <div className="screen workspace">
      <InstanceBreadcrumbs instance={instance} loading={loading} />

      {error && (
        <div className="error-note" role="alert">
          This instance could not be loaded. {error}
        </div>
      )}

      <nav className="tabs" aria-label="Instance sections">
        <NavLink to={`${base}/workbench`}>Workbench</NavLink>
        <NavLink to={`${base}/compare`}>Compare</NavLink>
        <NavLink to={`${base}/configuration`}>Configuration</NavLink>
        <NavLink to={`${base}/releases`}>Releases</NavLink>
      </nav>

      <Routes>
        <Route
          path="workbench"
          element={<InstanceWorkbench
            brainId={brainId} instanceSlug={instanceSlug} instance={instance} />}
        />
        <Route
          path="compare"
          element={<InstanceCompare
            brainId={brainId} instanceSlug={instanceSlug} instance={instance} />}
        />
        <Route
          path="configuration"
          element={<InstanceConfiguration
            brainId={brainId} instanceSlug={instanceSlug} instance={instance} />}
        />
        <Route
          path="releases"
          element={<InstanceReleases brainId={brainId} instanceSlug={instanceSlug} />}
        />
        {/* Bare workspace URL opens the tab people spend their time in. */}
        <Route path="*" element={<Navigate to={`${base}/workbench`} replace />} />
      </Routes>
    </div>
  );
}
