package net.knightsandkings.knk.core.domain.permissions;

/**
 * Identifies which holder a resolved permission grant/deny came from: a User's own direct
 * grant, or a PermissionGroup the user belongs to (directly or via inheritance). Bukkit-free
 * mirror of knk-web-api's PermissionHolder concept (docs/specs/user-features/DESIGN.md §2.1) -
 * not the full entity, just the identity a call site needs to display or reason about a result.
 *
 * @param id   The holder's id (User.Id or PermissionGroup.Id - both share the same
 *             PermissionHolder id space server-side).
 * @param type "User" or "PermissionGroup".
 * @param name Username or group name, when the server included it.
 */
public record PermissionHolder(Integer id, String type, String name) {
}
