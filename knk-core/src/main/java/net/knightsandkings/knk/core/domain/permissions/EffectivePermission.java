package net.knightsandkings.knk.core.domain.permissions;

/**
 * One entry in a user's full resolved permission set (see {@link EffectivePermissionSet}) -
 * a single declared node, its resolved value, and which holder it came from.
 */
public record EffectivePermission(String node, boolean value, PermissionHolder source) {
}
