package com.pragmaticds.docengine.platform.security;

/** What kind of principal is acting: an interactive human, an internal system, or an API key. */
public enum ActorType {
    /** A human, authenticated via OIDC — carries a {@link Role}. */
    USER,
    /** An internal engine task with no external identity (e.g. a scheduled sweep) — no role. */
    SYSTEM,
    /** A machine caller authenticated by API key — carries scopes, never a role. */
    API_KEY
}
