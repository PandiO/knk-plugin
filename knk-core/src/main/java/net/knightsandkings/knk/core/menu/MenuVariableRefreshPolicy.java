package net.knightsandkings.knk.core.menu;

/**
 * The three-tier variable cache-invalidation policy for a
 * {@code KnkVariableBinding} - the decided policy from DESIGN_REVIEW.md §1,
 * more authoritative for this than ARCHITECTURE_DESIGN.md §4.2's original
 * "cache unconditionally until the session changes" sketch. {@link VariableResolver}
 * consults this before doing any reflection work at all, never after.
 */
public enum MenuVariableRefreshPolicy {
    /** Resolves once and caches forever - for variables backed by immutable template/definition data. */
    STATIC,
    /** The default: re-resolves only when the owning {@link MenuSession} is marked dirty. */
    ON_DIRTY,
    /** Re-resolves once {@code ttlTicks} have elapsed since the last resolution. */
    TTL
}
