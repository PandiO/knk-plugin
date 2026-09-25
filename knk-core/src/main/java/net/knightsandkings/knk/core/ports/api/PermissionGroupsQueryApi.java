package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;

/**
 * Read-side operations for PermissionGroup - backs /knk user &lt;player&gt; group add's
 * name-to-id resolution and tab-completion. Mutations go through UsersCommandApi's
 * addGroupMembership/removeGroupMembership (a user-to-group link), not this port (which is
 * about the groups themselves, not memberships).
 */
public interface PermissionGroupsQueryApi {
    CompletableFuture<List<PermissionGroupSummary>> list();
}
