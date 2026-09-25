package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.users.GroupMembershipSummary;
import net.knightsandkings.knk.core.domain.users.UserDetail;
import net.knightsandkings.knk.core.domain.users.UserListItem;
import net.knightsandkings.knk.core.domain.users.UserSummary;

public interface UsersQueryApi {
    CompletableFuture<UserDetail> getById(int id);
    CompletableFuture<UserSummary> getByUuid(UUID uuid);
    CompletableFuture<UserSummary> getByUsername(String username);
    CompletableFuture<Page<UserListItem>> search(PagedQuery query);

    /**
     * This user's current PermissionGroup memberships (docs/specs/user-features/DESIGN.md §2.1
     * multi-membership model). Backs /knk user group add|remove's listing and RankHierarchy's
     * weight comparison - both need the full set, not just the single resolved premium tier
     * UserSummary already carries.
     */
    CompletableFuture<List<GroupMembershipSummary>> getGroupMemberships(int userId);
}