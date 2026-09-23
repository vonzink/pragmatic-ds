package com.pragmaticds.docengine.platform.web;

/**
 * {@code If-None-Match} comparison for every conditional read on this service, per RFC 9110:
 * {@code *}, or any of a comma-separated list, weak or strong.
 *
 * <p>In {@code platform} because it is HTTP, not domain, and three modules need it. It served only
 * the two L-layer reads at first; {@code /fields.md} meanwhile shipped a naive
 * {@code etag.equals(ifNoneMatch)} that never answered 304 to a client sending a weak validator or
 * a list — both of which are ordinary, and neither of which looks like a bug from the server side.
 *
 * <p>Weak comparison is right for both callers — a 304 says "your copy of this page's whole-window
 * representation is current", and each tag is derived from the page id, the content digest and (for
 * L2) the structure contract, so there is no representation-level distinction left for strong
 * comparison to make. A bare {@code *} matches whenever the window HAS a validator, which is RFC
 * 9110's stated meaning ("if the origin server has a current representation"); both endpoints
 * confine validators to their whole-page window, so {@code *} is confined with them.
 */
public final class EntityTags {

    private EntityTags() {}

    public static boolean anyMatch(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed)) {
                return true;
            }
            if (trimmed.startsWith("W/")) {
                trimmed = trimmed.substring(2);
            }
            if (etag.equals(trimmed)) {
                return true;
            }
        }
        return false;
    }
}
