package com.pragmaticds.docengine.platform.security;

/**
 * The human roles, exactly the set the {@code app_user.role} CHECK constraint allows
 * (V1__extensions_and_tenancy.sql). Machine principals carry scopes, never a role.
 *
 * <p>The hierarchy is deliberately EXPLICIT rather than ordinal-magic: a reviewer can read
 * {@link #includes(Role)} and see that ADMIN ⊇ REVIEWER ⊇ PROCESSOR ⊇ READONLY without
 * decoding enum positions. RBAC endpoint gates spell out the allowed roles rather than relying
 * on this method, so the two can never silently drift; this helper exists for in-code checks
 * (e.g. tests, future per-document grants).
 */
public enum Role {
    READONLY,
    PROCESSOR,
    REVIEWER,
    ADMIN;

    /**
     * True when a principal holding {@code this} role is also entitled to everything {@code other}
     * is — the containment ADMIN ⊇ REVIEWER ⊇ PROCESSOR ⊇ READONLY, written out.
     */
    public boolean includes(Role other) {
        return switch (this) {
            case ADMIN -> true;
            case REVIEWER -> other == REVIEWER || other == PROCESSOR || other == READONLY;
            case PROCESSOR -> other == PROCESSOR || other == READONLY;
            case READONLY -> other == READONLY;
        };
    }

    /** The Spring Security authority name for this role, e.g. {@code ROLE_REVIEWER}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
