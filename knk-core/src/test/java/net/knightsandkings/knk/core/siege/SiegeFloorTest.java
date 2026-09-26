package net.knightsandkings.knk.core.siege;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Playtest 2026-09-26: capture rings and capture distance use the floor under the capture point. */
class SiegeFloorTest {

    private static OptionalDouble floor(double y, Set<Integer> solid) {
        return SiegeFloor.floorY(y, solid::contains, by -> by + 1.0);
    }

    @Test
    void aPointTwoBlocksAboveTheFloorSnapsDown() {
        assertEquals(OptionalDouble.of(64.0), floor(66.0, Set.of(63)));
    }

    @Test
    void aPointStandingOnTheFloorStays() {
        assertEquals(OptionalDouble.of(64.0), floor(64.0, Set.of(63)));
        assertEquals(OptionalDouble.of(64.0), floor(64.9, Set.of(63)));
    }

    @Test
    void aPointInsideASolidBlockIsLiftedToItsTop() {
        assertEquals(OptionalDouble.of(64.0), floor(63.0, Set.of(62, 63)));
    }

    @Test
    void aSlabUsesItsCollisionTop() {
        OptionalDouble slab = SiegeFloor.floorY(64.5, by -> by == 64 || by == 63, by -> by == 64 ? 64.5 : by + 1.0);
        assertEquals(OptionalDouble.of(64.5), slab);
    }

    @Test
    void noFloorWithinReachKeepsThePoint() {
        assertEquals(OptionalDouble.empty(), floor(70.0, Set.of(63)));
        assertEquals(OptionalDouble.empty(), floor(60.0, Set.of(60, 61, 62, 63, 64)));
    }
}
