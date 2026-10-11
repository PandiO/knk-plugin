package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.target.GateBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** KNG-78/79: a gate door's region for the gate command targets. */
class GateDoorBoundsTest {

    @Test
    void cuboidRegionCoversBothCornerBlocks() {
        GateBox box = GateDoorBounds.regionBox("{\"type\":\"CUBOID\",\"pos1\":{\"x\":12,\"y\":70,\"z\":5},\"pos2\":{\"x\":10,\"y\":64,\"z\":5}}", 1);
        assertEquals(new GateBox(10, 64, 5, 13, 71, 6), box);
    }

    @Test
    void polygonRegionSpansItsPointsAndYRange() {
        GateBox box = GateDoorBounds.regionBox("{\"type\":\"POLYGON2D\",\"points\":[{\"x\":0,\"z\":0},{\"x\":4,\"z\":1},{\"x\":2,\"z\":-3}],"
            + "\"minY\":60,\"maxY\":62}", 1);
        assertEquals(new GateBox(0, 60, -3, 5, 63, 2), box);
    }

    @Test
    void convexRegionSpansItsVertices() {
        GateBox box = GateDoorBounds.regionBox("{\"type\":\"CONVEX_POLYHEDRON\",\"points\":[{\"x\":1,\"y\":2,\"z\":3},{\"x\":-1,\"y\":5,\"z\":0}]}", 1);
        assertEquals(new GateBox(-1, 2, 0, 2, 6, 4), box);
    }

    @Test
    void missingOrBrokenRegionDataIsIgnored() {
        assertNull(GateDoorBounds.regionBox(null, 1));
        assertNull(GateDoorBounds.regionBox(" ", 1));
        assertNull(GateDoorBounds.regionBox("{not json", 1));
        assertNull(GateDoorBounds.regionBox("{\"type\":\"CUBOID\"}", 1));
    }

    @Test
    void doorRegionIsTheClosedFootprintPlusItsCapturedRegions() {
        GateManager gateManager = mock(GateManager.class);
        CachedGateDoor door = door(7);
        when(gateManager.closedFootprint(7)).thenReturn(List.of(new Vector(10, 64, 20), new Vector(12, 67, 20)));
        door.setOpenedRegionData("{\"type\":\"CUBOID\",\"pos1\":{\"x\":10,\"y\":68,\"z\":18},\"pos2\":{\"x\":12,\"y\":68,\"z\":20}}");

        assertEquals(new GateBox(10, 64, 18, 13, 69, 21), GateDoorBounds.of(door, gateManager));
    }

    @Test
    void aDoorWithoutBlocksOrRegionsFallsBackToItsAnchor() {
        GateManager gateManager = mock(GateManager.class);
        when(gateManager.closedFootprint(7)).thenReturn(List.of());

        assertEquals(GateBox.ofBlock(100, 64, 100), GateDoorBounds.of(door(7), gateManager));
    }

    private static CachedGateDoor door(int id) {
        return new CachedGateDoor(id, 1, "Door", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 5, 3, 1, 500.0, 500.0, true, false, true, 90, "north");
    }
}
