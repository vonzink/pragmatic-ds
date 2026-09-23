package com.pragmaticds.rag.domain;

/**
 * Trust tier of an external source link (spec §6.1, §6.4). Stored as the bare
 * enum name (UPPER_SNAKE) via @Enumerated(EnumType.STRING). Only the three
 * external tiers live on the link row; company-rule and page-guide tiers come
 * from elsewhere (Phase 7).
 */
public enum LinkAuthority {
    PRIMARY,      // authoritative source: agency selling/servicing guides, HUD handbook
    SECONDARY,    // approved supporting source
    BACKGROUND    // general background / context only
}
