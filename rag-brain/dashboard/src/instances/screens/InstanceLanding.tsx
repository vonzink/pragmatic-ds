import { Link } from "react-router-dom";
import { useInstanceCatalog } from "../hooks/useInstanceCatalog";
import InstanceCard from "../components/InstanceCard";

/**
 * The page you land on, and the one that decides what this dashboard is about.
 *
 * It lists instances across every brain with no active-brain prerequisite. That is the point of
 * the phase: an operator opens the dashboard and sees the things they work with, rather than
 * first having to know which brain owns the one they want.
 *
 * Four states, kept distinct on purpose. Loading is not empty. Empty is not error. A partial load
 * — some brains answered, one did not — is neither, and says which brain is missing so nobody
 * reads a short list as the whole truth.
 */
export default function InstanceLanding() {
  const { instances, unavailableBrains, loading, error, reload } = useInstanceCatalog();

  return (
    <div className="screen">
      <header className="screen-head">
        <h1>Instances</h1>
        <div className="screen-actions">
          <Link className="btn-primary" to="/instances/new">Create instance</Link>
          <Link className="btn" to="/instance-runs/new">Run independently</Link>
        </div>
      </header>

      {loading && <p className="muted">Loading instances…</p>}

      {error && (
        <div className="error-note" role="alert">
          <p>Could not load brains, so no instances can be listed. {error}</p>
          <button className="btn" onClick={reload}>Try again</button>
        </div>
      )}

      {/* Scoped, not fatal: the instances that did load are still shown above this. */}
      {unavailableBrains.length > 0 && (
        <div className="warn-note" role="status">
          Instances in {unavailableBrains.join(", ")} could not be listed. Everything below is
          from the brains that answered.
        </div>
      )}

      {!loading && !error && instances.length === 0 && unavailableBrains.length === 0 && (
        <div className="empty-note">
          <p>No instances yet.</p>
          <p className="muted">
            An instance pins a parsed-data contract, a corpus, a model and limits, so that every
            run of it is reproducible. Create one to begin.
          </p>
        </div>
      )}

      {instances.length > 0 && (
        <ul className="instance-grid">
          {instances.map((instance) => (
            <InstanceCard
              key={`${instance.brainId}:${instance.slug}`}
              instance={instance}
            />
          ))}
        </ul>
      )}
    </div>
  );
}
