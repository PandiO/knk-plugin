package net.knightsandkings.knk.core.domain.permissions;

/**
 * Result of a single-node permission check against knk-web-api's
 * GET /api/users/{id}/permissions/check endpoint.
 *
 * @param node        The node that was queried.
 * @param result      GRANTED, DENIED, or UNDECLARED.
 * @param source      Which holder produced this result. Null when UNDECLARED.
 * @param matchedNode The actual grant node that matched (may differ from {@code node} when the
 *                     match was a wildcard, e.g. "knk.gate.*"). Null when UNDECLARED.
 */
public record PermissionCheckResult(
    String node,
    PermissionResolution result,
    PermissionHolder source,
    String matchedNode
) {
    /** Convenience for call sites that just need a boolean, e.g. KnkPermissible. */
    public boolean isAllowed() {
        return result == PermissionResolution.GRANTED;
    }
}
