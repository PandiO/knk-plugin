package net.knightsandkings.knk.core.domain.permissions;

/**
 * Outcome of resolving a single permission node for a user, per
 * docs/specs/user-features/DESIGN.md §2.2. Undeclared nodes fail closed (deny).
 */
public enum PermissionResolution {
    GRANTED,
    DENIED,
    UNDECLARED
}
