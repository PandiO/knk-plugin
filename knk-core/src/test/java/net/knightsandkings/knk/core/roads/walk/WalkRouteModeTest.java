package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GridFixture;
import net.knightsandkings.knk.core.roads.route.RouteMode;
import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-road phase 4 (PandiO/knk-workspace#707, DESIGN § 5.4, A09 walk part): a walk leg in each route mode.
 * {@code efficient} and {@code preferred} keep today's walk profile (the same instance); {@code shortest} prices every
 * move as the blocks walked.
 */
class WalkRouteModeTest {

    private static final String G = GridFixture.GRASS;

    /** A wall across a 5 by 5 yard with a door in its middle and a gap at its east end. */
    private static WalkFixture doorOrGap() {
        return new WalkFixture().floor(0, 0, 4, 4, 64, G).layer(65,
            ".....", ".....", "##D#.", ".....", ".....");
    }

    private static boolean visits(WalkPath path, int x, int y, int z) {
        for (int i = 0; i < path.size(); i++) {
            if (path.cell(i) == BlockKey.pack(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static WalkPath walk(MovementProfile profile, RouteMode mode) {
        WalkRequest request = doorOrGap().request(2, 64, 0, 2, 64, 4).withProfile(profile.forMode(mode));
        WalkResult result = new WalkSearch().find(request);
        assertEquals(WalkResult.Status.FOUND, result.status(), result.toString());
        return result.path().orElseThrow();
    }

    @Test
    void shortestWalksThroughTheDoorEfficientAndPreferredGoRoundByTheGap() {
        WalkPath shortest = walk(WalkFixture.GEOMETRY_PLAYER, RouteMode.SHORTEST);
        assertTrue(visits(shortest, 2, 64, 2), "length only: straight through the door");
        assertEquals(4.0, shortest.length(), 1e-9);
        assertEquals(shortest.length(), shortest.cost(), 1e-9, "no door cost");

        WalkPath efficient = walk(WalkFixture.GEOMETRY_PLAYER, RouteMode.EFFICIENT);
        assertFalse(visits(efficient, 2, 64, 2), "the door's +3 makes the gap cheaper");
        assertTrue(visits(efficient, 4, 64, 2), "through the gap");
        assertTrue(efficient.length() > shortest.length());
        assertTrue(efficient.cost() < 4.0 + 3.0);

        WalkPath preferred = walk(WalkFixture.GEOMETRY_PLAYER, RouteMode.PREFERRED);
        assertEquals(efficient.length(), preferred.length(), 0.0, "A11: no preference landmarks - as efficient");
        assertEquals(efficient.cost(), preferred.cost(), 0.0);
    }

    @Test
    void preferredAndEfficientKeepTodaysProfile() {
        // O24: the default walk leg is today's - the very same profile, so the very same search
        for (MovementProfile profile : new MovementProfile[] {MovementProfile.PLAYER, WalkFixture.GEOMETRY_PLAYER,
            MovementProfile.PLAYER.withClearance(5, 0.8)}) {
            assertSame(profile, profile.forMode(RouteMode.PREFERRED));
            assertSame(profile, profile.forMode(RouteMode.EFFICIENT));
            assertSame(profile, profile.forMode(null));
        }
    }

    @Test
    void lengthOnlyDropsEveryExtraCostButKeepsWhatTheMoverCanDo() {
        MovementProfile player = MovementProfile.PLAYER.withClearance(5, 1.2);
        MovementProfile length = player.forMode(RouteMode.SHORTEST);
        assertEquals(0.0, length.dropPenalty(), 0.0);
        assertEquals(0.0, length.jumpCost(), 0.0);
        assertEquals(0.0, length.doorCost(), 0.0);
        assertEquals(1.0, length.waterFactor(), 0.0);
        assertEquals(1.0, length.climbCost(), 0.0);
        assertEquals(0.0, length.wallCost(), 0.0);
        assertEquals(0.0, length.clearanceCost(), 0.0);
        assertEquals(player.headroom(), length.headroom());
        assertEquals(player.maxDrop(), length.maxDrop(), "drops stay passable");
        assertEquals(player.opensDoors(), length.opensDoors(), "doors stay passable");
        assertEquals(player.wades(), length.wades(), "water stays passable");
        assertEquals(player.climbables(), length.climbables(), "ladders stay climbable");
        assertEquals(player.wallClearance(), length.wallClearance(), 0.0, "the shaper's clearance is unchanged");
    }
}
