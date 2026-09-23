/**
 * The one switch that decides whether any of this exists.
 *
 * Off by default and read through a function rather than inlined at each call site, so that
 * turning the phase on is one change and so tests can drive both states. With it off the nav, the
 * routes, and every call in this folder are absent — not hidden, absent — and the dashboard behaves
 * exactly as it did before.
 */
export function instanceControlEnabled(): boolean {
  return import.meta.env.VITE_INSTANCE_CONTROL_ENABLED === "true";
}
