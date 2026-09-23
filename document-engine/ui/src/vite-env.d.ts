/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL for the engine API. Defaults to the proxied, relative `/v1`. */
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
