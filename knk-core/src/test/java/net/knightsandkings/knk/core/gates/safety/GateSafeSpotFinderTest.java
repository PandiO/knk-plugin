package net.knightsandkings.knk.core.gates.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.gates.safety.GateSafeSpotFinder.CellFilter;
import net.knightsandkings.knk.core.gates.safety.GateSafeSpotFinder.Query;
import net.knightsandkings.knk.core.gates.target.GateBox;
import net.knightsandkings.knk.core.teleport.SafeLocationFinder;
import net.knightsandkings.knk.core.teleport.SafeLocationFinder.Spot;
import net.knightsandkings.knk.core.util.BlockProbe;

/**
 * KNG-105/106: the standing spot next to a door. The door in these tests is three blocks wide
 * (x 0..2), three high (y 64..66) and one deep (z 0), facing south (+z); the floor is stone at y 63.
 */
class GateSafeSpotFinderTest {

    private static final GateBox DOOR = new GateBox(0, 64, 0, 3, 67, 1);

    /** Air above a stone floor at y = 63, plus overrides. */
    private static final class FakeWorld implements BlockProbe {
        private final Map<String, String> blocks = new HashMap<>();

        FakeWorld set(int x, int y, int z, String type) {
            blocks.put(x + "," + y + "," + z, type);
            return this;
        }

        FakeWorld fill(int x1, int y1, int z1, int x2, int y2, int z2, String type) {
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        set(x, y, z, type);
                    }
                }
            }
            return this;
        }

        String type(int x, int y, int z) {
            String type = blocks.get(x + "," + y + "," + z);
            if (type != null) {
                return type;
            }
            return y <= 63 ? "STONE" : "AIR";
        }

        @Override
        public boolean isPassable(int x, int y, int z) {
            String type = type(x, y, z);
            return type.equals("AIR") || type.equals("LAVA");
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return type(x, y, z).equals("STONE");
        }

        @Override
        public boolean isHazard(int x, int y, int z) {
            return SafeLocationFinder.HAZARD_MATERIALS.contains(type(x, y, z));
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 320;
        }
    }

    private static FakeWorld closedDoor() {
        return new FakeWorld().fill(0, 64, 0, 2, 66, 0, "STONE");
    }

    private static Query teleportQuery() {
        return new Query(DOOR, null, DOOR.minY(), 0, 1, 0, 4, 3);
    }

    private static Optional<Spot> find(FakeWorld world, Query query) {
        return GateSafeSpotFinder.find(world, query, CellFilter.inside(DOOR));
    }

    private static void assertOutsideDoor(Spot spot) {
        assertFalse(GateSafeSpotFinder.overlaps(DOOR, spot.x(), spot.y(), spot.z()), "feet inside the door: " + spot);
        assertFalse(GateSafeSpotFinder.overlaps(DOOR, spot.x(), spot.y() + 1, spot.z()), "head inside the door: " + spot);
    }

    @Test
    void closedDoorPutsThePlayerInFrontOfOrBehindItOnTheGround() {
        Spot spot = find(closedDoor(), teleportQuery()).orElseThrow();

        assertOutsideDoor(spot);
        assertEquals(64, spot.y());
        assertTrue(spot.z() == 1 || spot.z() == -1, "front or back face: " + spot);
        assertTrue(spot.x() >= 0 && spot.x() <= 2, "within the door's width: " + spot);
    }

    @Test
    void openDoorStillKeepsThePlayerOutOfTheOpening() {
        // the opening is air now, but it is the door's region: closing would crush whoever stands there
        Spot spot = find(new FakeWorld(), teleportQuery()).orElseThrow();

        assertOutsideDoor(spot);
        assertTrue(spot.z() == 1 || spot.z() == -1, "front or back face: " + spot);
    }

    @Test
    void doorAgainstAWallUsesTheOtherFace() {
        FakeWorld world = closedDoor().fill(-6, 64, -1, 8, 66, -1, "STONE");

        Spot spot = find(world, teleportQuery()).orElseThrow();

        assertOutsideDoor(spot);
        assertEquals(1, spot.z());
        assertEquals(64, spot.y());
    }

    @Test
    void bothFacesWalledInFallsBackToBesideTheDoor() {
        // solid rock in front and behind, beyond the search radius: only the door's sides are free
        FakeWorld world = closedDoor().fill(-6, 64, -6, 8, 66, -1, "STONE").fill(-6, 64, 1, 8, 66, 6, "STONE");

        Spot spot = find(world, teleportQuery()).orElseThrow();

        assertOutsideDoor(spot);
        assertTrue(spot.z() == 0 && (spot.x() == -1 || spot.x() == 3), "beside the door: " + spot);
    }

    @Test
    void lavaInFrontIsAvoided() {
        FakeWorld world = closedDoor().fill(-6, 63, 1, 8, 63, 5, "LAVA");

        Spot spot = find(world, teleportQuery()).orElseThrow();

        assertEquals(-1, spot.z());
    }

    @Test
    void aDropInFrontIsAvoided() {
        FakeWorld world = closedDoor().fill(-6, 50, -5, 8, 63, -1, "AIR");

        Spot spot = find(world, teleportQuery()).orElseThrow();

        assertEquals(1, spot.z());
    }

    @Test
    void nothingSafeWithinTheRadiusFindsNothing() {
        FakeWorld world = new FakeWorld().fill(-10, 60, -10, 12, 75, 11, "STONE");

        assertTrue(find(world, teleportQuery()).isEmpty());
    }

    @Test
    void theGroundMustNotBeABlockedCell() {
        // a door whose region reaches into the floor: standing on top of its blocks is not a spot
        GateBox deepDoor = new GateBox(0, 63, 0, 3, 67, 1);
        FakeWorld world = closedDoor().fill(-6, 64, -1, 8, 66, -1, "STONE").fill(-6, 64, 1, 8, 66, 1, "STONE");
        Query query = new Query(deepDoor, null, 64, 0, 1, 0, 4, 3);

        Spot spot = GateSafeSpotFinder.find(world, query, CellFilter.inside(deepDoor)).orElseThrow();

        assertFalse(GateSafeSpotFinder.overlaps(deepDoor, spot.x(), spot.y() - 1, spot.z()), "standing on the door: " + spot);
    }

    @Test
    void preferredSideWinsOverAnEquallyNearOtherSide() {
        // a player standing in the opening, a little to the north (-z) of the door's middle plane
        GateBox player = new GateBox(1.2, 64, 0.1, 1.8, 65.8, 0.45);
        int side = GateSafeSpotFinder.sideOf(DOOR, 0, 1, 1.5, 0.3);
        Query query = new Query(player, DOOR, 64, 0, 1, side, 4, 2);

        Spot spot = find(new FakeWorld(), query).orElseThrow();

        assertEquals(-1, side);
        assertEquals(-1, spot.z());
        assertEquals(1, spot.x());
        assertEquals(64, spot.y());
    }

    @Test
    void preferredSideBlockedPushesToTheOtherSide() {
        GateBox player = new GateBox(1.2, 64, 0.1, 1.8, 65.8, 0.45);
        FakeWorld world = new FakeWorld().fill(-6, 64, -5, 8, 66, -1, "STONE");
        Query query = new Query(player, DOOR, 64, 0, 1, -1, 4, 2);

        Spot spot = find(world, query).orElseThrow();

        assertEquals(1, spot.z());
    }

    @Test
    void extraBlockedCellsAreAvoided() {
        // another door's footprint right in front of this one
        GateBox otherDoor = new GateBox(-6, 64, 1, 9, 67, 2);
        CellFilter blocked = CellFilter.inside(DOOR).or(CellFilter.inside(otherDoor));

        Spot spot = GateSafeSpotFinder.find(closedDoor(), teleportQuery(), blocked).orElseThrow();

        assertEquals(-1, spot.z());
    }

    @Test
    void sideOfUsesTheDoorsMiddlePlane() {
        assertEquals(1, GateSafeSpotFinder.sideOf(DOOR, 0, 1, 1.5, 2));
        assertEquals(-1, GateSafeSpotFinder.sideOf(DOOR, 0, 1, 1.5, -2));
        assertEquals(0, GateSafeSpotFinder.sideOf(DOOR, 0, 1, 1.5, 0.5));
        assertEquals(0, GateSafeSpotFinder.sideOf(DOOR, 0, 0, 1.5, 2));
    }
}
