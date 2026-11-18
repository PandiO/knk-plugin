package net.knightsandkings.knk.paper.commands.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.GroupMembershipSummary;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;

/** Strictly higher ({@code actorOutranks}) vs. same rank or lower ({@code actorAtLeast}, PandiO/knk-workspace#171). */
class RankHierarchyTest {

    private final UsersQueryApi users = mock(UsersQueryApi.class);
    private final RankHierarchy ranks = new RankHierarchy(users);

    private void weights(int userId, GroupMembershipSummary... memberships) {
        when(users.getGroupMemberships(userId)).thenReturn(CompletableFuture.completedFuture(List.of(memberships)));
    }

    private static GroupMembershipSummary group(int weight, boolean active) {
        return new GroupMembershipSummary(weight, "g" + weight, weight, false, null, active);
    }

    @Test
    void sameRankMayTeleportButNotOutrank() {
        weights(1, group(50, true));
        weights(2, group(50, true));

        assertTrue(ranks.actorAtLeast(1, 2).join());
        assertFalse(ranks.actorOutranks(1, 2).join());
    }

    @Test
    void lowerRankIsMovedHigherRankIsNot() {
        weights(1, group(50, true), group(90, false)); // the inactive 90 doesn't count
        weights(2, group(10, true));
        weights(3, group(60, true));

        assertTrue(ranks.actorAtLeast(1, 2).join());
        assertFalse(ranks.actorAtLeast(1, 3).join());
        assertEquals(50, ranks.weightOf(1).join());
    }

    @Test
    void yourselfAndNoMemberships() {
        weights(1);
        weights(2);

        assertTrue(ranks.actorAtLeast(1, 1).join());
        assertFalse(ranks.actorOutranks(1, 1).join());
        assertTrue(ranks.actorAtLeast(1, 2).join(), "both bare Default (weight 0)");
    }
}
