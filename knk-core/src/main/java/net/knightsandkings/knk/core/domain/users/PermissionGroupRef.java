package net.knightsandkings.knk.core.domain.users;

/**
 * A PermissionGroup by id and name, as listed in {@link UserSummary#permissionGroups()} (KNG-52).
 */
public record PermissionGroupRef(int id, String name) {
}
