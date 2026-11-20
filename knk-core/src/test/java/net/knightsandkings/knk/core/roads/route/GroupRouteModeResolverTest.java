package net.knightsandkings.knk.core.roads.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.domain.users.GroupMembershipSummary;

/** Off-road L7b (PandiO/knk-workspace#852): the highest-weight group with a default route mode wins. */
class GroupRouteModeResolverTest {

    private static PermissionGroupSummary group(int id, int weight, RouteMode mode) {
        return new PermissionGroupSummary(id, "G" + id, weight, false, 1.0, null, null, null, mode);
    }

    private static GroupMembershipSummary member(int groupId, boolean active) {
        return new GroupMembershipSummary(groupId, "G" + groupId, 0, false, null, active);
    }

    private final List<PermissionGroupSummary> groups = List.of(
        group(1, 0, null),                  // Default: no route mode
        group(2, 10, RouteMode.EFFICIENT),  // Noble
        group(3, 30, RouteMode.SHORTEST),   // Royal
        group(4, 50, null),                 // Staff: no route mode
        group(5, 30, RouteMode.PREFERRED)); // ties Royal's weight

    @Test
    void theHighestWeightGroupWithADefaultWins() {
        assertEquals(Optional.of(RouteMode.SHORTEST),
            GroupRouteModeResolver.resolve(List.of(member(1, true), member(2, true), member(3, true), member(4, true)), groups));
        assertEquals(Optional.of(RouteMode.EFFICIENT),
            GroupRouteModeResolver.resolve(List.of(member(4, true), member(2, true)), groups),
            "a heavier group without a default doesn't hide a lighter one's");
    }

    @Test
    void withoutADefaultTheAnswerIsEmpty() {
        assertEquals(Optional.empty(), GroupRouteModeResolver.resolve(List.of(member(1, true), member(4, true)), groups));
        assertEquals(Optional.empty(), GroupRouteModeResolver.resolve(List.of(), groups));
        assertEquals(Optional.empty(), GroupRouteModeResolver.resolve(null, groups));
        assertEquals(Optional.empty(), GroupRouteModeResolver.resolve(List.of(member(3, true)), List.of()));
        assertEquals(Optional.empty(), GroupRouteModeResolver.resolve(List.of(member(99, true)), groups), "unknown group");
    }

    @Test
    void inactiveMembershipsDontCount() {
        assertEquals(Optional.of(RouteMode.EFFICIENT),
            GroupRouteModeResolver.resolve(List.of(member(3, false), member(2, true)), groups));
    }

    @Test
    void aWeightTieGoesToTheLowerGroupIdWhateverTheOrder() {
        assertEquals(Optional.of(RouteMode.SHORTEST),
            GroupRouteModeResolver.resolve(List.of(member(5, true), member(3, true)), groups));
        assertEquals(Optional.of(RouteMode.SHORTEST),
            GroupRouteModeResolver.resolve(List.of(member(3, true), member(5, true)), groups));
    }
}
