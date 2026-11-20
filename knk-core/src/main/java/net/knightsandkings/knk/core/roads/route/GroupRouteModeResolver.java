package net.knightsandkings.knk.core.roads.route;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.domain.users.GroupMembershipSummary;

/**
 * Off-road L7b (PandiO/knk-workspace#852, decisions 41 and 43): a player's default route mode from their permission
 * groups. Of the player's active memberships whose group has a {@code DefaultRouteMode}, the one with the highest
 * weight wins (a tie goes to the lower group id, so the answer never depends on list order); without one the answer is
 * empty and {@code /navigate} keeps {@link RouteMode#DEFAULT}, today's behaviour.
 *
 * <p>Bukkit-free and stateless; the per-player cache is the plugin's.
 */
public final class GroupRouteModeResolver {

    private GroupRouteModeResolver() {
    }

    /**
     * @param memberships the player's group memberships ({@code GET api/UserPermissionGroups?userId=}); inactive ones
     *                    are ignored
     * @param groups      every group ({@code GET api/PermissionGroups}), for their default route modes
     */
    public static Optional<RouteMode> resolve(Collection<GroupMembershipSummary> memberships,
                                              Collection<PermissionGroupSummary> groups) {
        if (memberships == null || memberships.isEmpty() || groups == null || groups.isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, PermissionGroupSummary> byId = groups.stream().filter(Objects::nonNull)
            .collect(Collectors.toMap(PermissionGroupSummary::id, Function.identity(), (a, b) -> a));
        return memberships.stream()
            .filter(Objects::nonNull)
            .filter(GroupMembershipSummary::isActive)
            .map(m -> byId.get(m.groupId()))
            .filter(g -> g != null && g.defaultRouteMode() != null)
            .max(Comparator.comparingInt(PermissionGroupSummary::weight)
                .thenComparing(PermissionGroupSummary::id, Comparator.reverseOrder()))
            .map(PermissionGroupSummary::defaultRouteMode);
    }
}
