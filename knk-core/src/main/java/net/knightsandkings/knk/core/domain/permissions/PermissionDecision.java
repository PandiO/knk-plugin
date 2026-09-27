package net.knightsandkings.knk.core.domain.permissions;

/**
 * The answer to "may this player do that?" once the plugin has asked: {@link #ALLOWED},
 * {@link #DENIED} (a real answer - no grant, a deny, or an undeclared node), or
 * {@link #UNAVAILABLE}: the plugin couldn't find out (knk-web-api unreachable, or the player's
 * account never loaded). Callers still fail closed on UNAVAILABLE, but tell the player the
 * service is down instead of "You don't have permission" (currency smoke test, 2026-09-27).
 */
public enum PermissionDecision {
    ALLOWED,
    DENIED,
    UNAVAILABLE;

    public boolean allowed() {
        return this == ALLOWED;
    }

    public static PermissionDecision of(boolean allowed) {
        return allowed ? ALLOWED : DENIED;
    }
}
