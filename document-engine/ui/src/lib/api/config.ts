/**
 * Base URL every API call is built on.
 *
 * Defaults to the relative `/v1`, which the Vite dev server proxies to
 * http://localhost:9090 and which a production build resolves against whatever
 * origin serves the bundle. Set VITE_API_BASE to an absolute URL only when the
 * UI genuinely has to talk cross-origin — the engine's `local`/`test` profiles
 * allow http://localhost:6173 for exactly that case.
 */
export const API_BASE: string = import.meta.env.VITE_API_BASE ?? '/v1';
