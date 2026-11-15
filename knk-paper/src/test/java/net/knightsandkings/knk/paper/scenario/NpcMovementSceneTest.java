package net.knightsandkings.knk.paper.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavPoint;

/**
 * The NPC movement scenes (plan 4c.5, link M1, PandiO/knk-workspace#621): every road-line point and every start or
 * goal stands on a floor of the schematic it is pasted from, with room for a body above, so a change to the stand-ins
 * (tools/scenario-scenes/make_standins.py) that moves a wall over the road fails here and not in a Paper run.
 */
class NpcMovementSceneTest {

    private static final int OX = 30_000;
    private static final int OY = 100;
    private static final int OZ = 30_000;

    // kept in a field, never inline (knk-plugin CLAUDE.md: a Location holds its world weakly)
    private final World world = mock(World.class);

    private ScenarioScene scene() {
        when(world.getName()).thenReturn("world");
        return new ScenarioScene("movement-test", 0, world, OX, OY, OZ,
            new ScenarioServices(null, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null,
                () -> null, () -> null, () -> null, () -> null, () -> null));
    }

    /** Null when a floor point stands on a block with two free blocks above it in the schematic, else why not. */
    private static String standable(SceneSchematic s, NavPoint floor) {
        int x = floor.blockX() - OX;
        int y = floor.blockY() - OY;
        int z = floor.blockZ() - OZ;
        String below = s.blockAt(x, y, z);
        String feet = s.blockAt(x, y + 1, z);
        String head = s.blockAt(x, y + 2, z);
        if (below.equals("minecraft:air")) {
            return "no floor under " + x + " " + y + " " + z;
        }
        if (!feet.equals("minecraft:air") || !head.equals("minecraft:air")) {
            return "no room above " + x + " " + y + " " + z + ": " + feet + ", " + head;
        }
        return null;
    }

    private static NavPoint floorOf(NavPoint feet) {
        return new NavPoint(feet.world(), feet.x(), feet.y() - 1, feet.z());
    }

    private static void assertStandable(SceneSchematic s, List<NavPoint> floors, String what) {
        for (NavPoint p : floors) {
            String why = standable(s, p);
            assertEquals(null, why, what);
        }
    }

    @Test
    void theStairHouseLineAndSpotsStandOnTheStandIn() {
        NpcMovementScene house = NpcMovementScene.stairHouse(scene());
        SceneSchematic s = SceneSchematic.fromResource(house.resource());

        assertTrue(house.standIn());
        assertStandable(s, house.houseLine(), "the road line");
        List<NavPoint> spots = new ArrayList<>(house.houseBottomSpots());
        spots.addAll(house.houseTopSpots());
        assertStandable(s, spots.stream().map(NpcMovementSceneTest::floorOf).toList(), "the starts and goals");
        assertEquals(6, house.houseTopSpots().size());
    }

    @Test
    void theStairHouseAreasLieWhereThePlanSaysInTheRealWorld() {
        NpcMovementScene house = NpcMovementScene.stairHouse(scene());
        SceneSchematic s = SceneSchematic.fromResource(house.resource());
        int[] entrance = s.local(1413, 49, -508); // a feet block
        int[] straight = s.local(1414, 49, -503);

        assertTrue(house.inHouseEntrance(house.feet(entrance[0] + 0.5, entrance[1] - 1, entrance[2] + 0.5)));
        assertTrue(house.onHouseStraight(house.feet(straight[0] + 0.5, straight[1] - 1, straight[2] + 0.5)));
        assertFalse(house.inHouseEntrance(house.feet(20.5, NpcMovementScene.HOUSE_GROUND, 3.5)), "the road east");
        assertEquals("13 2 7", house.local(house.feet(13.5, 2, 7.5)), "box block, floor height");
    }

    @Test
    void theJaggedLineRunsThroughTheMiddleOfTheLocalWidth() {
        NpcMovementScene road = NpcMovementScene.jaggedStraight(scene());
        SceneSchematic s = SceneSchematic.fromResource(road.resource());
        List<NavPoint> line = road.jaggedLine();

        assertEquals(32, line.size());
        assertStandable(s, line, "the road line");
        assertEquals(15.5, line.get(0).x() - OX, 1e-9, "no notch: the middle of x 14..16");
        assertEquals(16.0, line.get(4).x() - OX, 1e-9, "an east notch at z 4: half a block east");
        assertEquals(15.0, line.get(3).x() - OX, 1e-9, "a west notch at z 3: half a block west");
        assertEquals(15.5, line.get(12).x() - OX, 1e-9, "the notch with the lantern post is no room");
        List<NavPoint> ends = new ArrayList<>(road.jaggedEnd(true));
        ends.addAll(road.jaggedEnd(false));
        assertStandable(s, ends.stream().map(NpcMovementSceneTest::floorOf).toList(), "the ends");
    }

    @Test
    void theDoorwayLineGoesThroughTheDoorAndTheRoomsAreMirrored() {
        NpcMovementScene rooms = NpcMovementScene.doorway(scene());
        SceneSchematic s = SceneSchematic.fromResource(rooms.resource());

        assertStandable(s, rooms.doorLine(), "the road line");
        List<NavPoint> spots = new ArrayList<>(rooms.doorRoom(true));
        spots.addAll(rooms.doorRoom(false));
        assertStandable(s, spots.stream().map(NpcMovementSceneTest::floorOf).toList(), "the rooms");
        assertTrue(rooms.inDoorway(rooms.feet(12.5, 1, 7.5)));
        assertFalse(rooms.inDoorway(rooms.feet(11.5, 1, 7.5)));
        assertEquals(24 - (rooms.doorRoom(true).get(0).x() - OX), rooms.doorRoom(false).get(0).x() - OX, 1e-9);
    }

    @Test
    void thePlazaLineIsTheGraphsVAndTheWellIsMeasuredFromItsFaces() {
        NpcMovementScene plaza = NpcMovementScene.plaza(scene());
        SceneSchematic s = SceneSchematic.fromResource(plaza.resource());

        assertStandable(s, plaza.plazaLine(), "the road line");
        assertTrue(plaza.onPlaza(plaza.feet(15.5, 1, 15.5)));
        assertFalse(plaza.onPlaza(plaza.feet(5.5, 1, 15.5)), "on the west road");
        assertEquals(0, plaza.fromWell(plaza.feet(12.0, 1, 12.0)), 1e-9, "on the well");
        assertEquals(2.5, plaza.fromWell(plaza.feet(15.5, 1, 12.0)), 1e-9, "east of its face at x 13");
        assertEquals("minecraft:cobblestone", s.blockAt(11, 2, 11));
    }
}
