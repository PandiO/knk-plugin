package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for EntityEvacuator's pure resolveFaceAxis helper - kept independent of Bukkit
 * World/Entity/Location so it runs without a live server, matching GatePassThroughServiceTest's
 * approach for the same kind of basis-vector math. Written as a regression guard for the
 * diagonal-gate lattice-step fix: this logic reads FaceDirection/nAxis (unit vectors) and must
 * stay correct without ever needing the new uStep/vStep/nStep fields.
 */
class EntityEvacuatorTest {
    private static final double EPSILON = 1e-6;

    @Test
    void resolveFaceAxisUsesDiagonalFaceDirectionDirectly() {
        CachedGateDoor gate = new CachedGateDoor(
            1, 1, "DiagonalGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 0, 0), 1, 1, 1,
            500.0, 500.0, true, false, true, 90,
            "SOUTH_EAST"
        );

        Vector axis = EntityEvacuator.resolveFaceAxis(gate);

        assertNotNull(axis);
        double invSqrt2 = 1.0 / Math.sqrt(2.0);
        assertEquals(invSqrt2, axis.getX(), EPSILON);
        assertEquals(0, axis.getY(), EPSILON);
        assertEquals(invSqrt2, axis.getZ(), EPSILON);
    }

    @Test
    void resolveFaceAxisFallsBackToDiagonalNAxisWhenFaceDirectionMissing() {
        CachedGateDoor gate = new CachedGateDoor(
            2, 2, "NoFaceDirectionGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 0, 0), 1, 1, 1,
            500.0, 500.0, true, false, true, 90,
            ""
        );
        double invSqrt2 = 1.0 / Math.sqrt(2.0);
        // Deliberately not setting uStep/vStep/nStep - resolveFaceAxis must not need them.
        gate.setNAxis(new Vector(invSqrt2, 0, invSqrt2));

        Vector axis = EntityEvacuator.resolveFaceAxis(gate);

        assertNotNull(axis);
        assertEquals(invSqrt2, axis.getX(), EPSILON);
        assertEquals(0, axis.getY(), EPSILON);
        assertEquals(invSqrt2, axis.getZ(), EPSILON);
    }

    @Test
    void resolveFaceAxisReturnsNullWhenAxisIsPurelyVertical() {
        CachedGateDoor gate = new CachedGateDoor(
            3, 3, "VerticalOnlyGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 0, 0), 1, 1, 1,
            500.0, 500.0, true, false, true, 90,
            ""
        );
        gate.setNAxis(new Vector(0, 1, 0));

        assertNull(EntityEvacuator.resolveFaceAxis(gate));
    }

    // ===== KNG-106: push-target selection =====

    /** Kept in a field: Location holds its world only weakly (see knk-plugin CLAUDE.md). */
    private final org.bukkit.World world = org.mockito.Mockito.mock(org.bukkit.World.class);

    /** A 3-wide door at x 0..2, y 64..66, z 0, facing south (+z). */
    private CachedGateDoor southDoor(net.knightsandkings.knk.core.gates.GateManager gateManager) {
        CachedGateDoor gate = new CachedGateDoor(
            7, 7, "Door", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 64, 0), 3, 3, 1,
            500.0, 500.0, true, false, true, 90,
            "SOUTH"
        );
        java.util.List<Vector> closed = new java.util.ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 64; y < 67; y++) {
                closed.add(new Vector(x, y, 0));
            }
        }
        org.mockito.Mockito.when(gateManager.closedFootprint(7)).thenReturn(closed);
        return gate;
    }

    private org.bukkit.entity.Entity entityAt(double x, double y, double z) {
        org.bukkit.entity.Entity entity = org.mockito.Mockito.mock(org.bukkit.entity.Entity.class);
        org.mockito.Mockito.when(entity.getLocation()).thenReturn(new org.bukkit.Location(world, x, y, z));
        org.mockito.Mockito.when(entity.getBoundingBox()).thenReturn(new org.bukkit.util.BoundingBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3));
        return entity;
    }

    @Test
    void pushTargetPrefersTheSideTheEntityIsOn() {
        var gateManager = org.mockito.Mockito.mock(net.knightsandkings.knk.core.gates.GateManager.class);
        CachedGateDoor gate = southDoor(gateManager);

        var north = EntityEvacuator.query(entityAt(1.5, 64, 0.3), gate, gateManager);
        var south = EntityEvacuator.query(entityAt(1.5, 64, 0.7), gate, gateManager);

        assertEquals(-1, north.preferredSide());
        assertEquals(1, south.preferredSide());
        assertEquals(64, north.feetY(), EPSILON);
        assertEquals(0, north.door().minZ(), EPSILON);
        assertEquals(1, north.door().maxZ(), EPSILON);
        assertEquals(1, north.axisZ(), EPSILON);
    }

    @Test
    void pushTargetSearchesAroundTheEntityNotTheDoorAnchor() {
        var gateManager = org.mockito.Mockito.mock(net.knightsandkings.knk.core.gates.GateManager.class);
        CachedGateDoor gate = southDoor(gateManager);

        var query = EntityEvacuator.query(entityAt(2.5, 65, 0.3), gate, gateManager);

        assertEquals(2.2, query.near().minX(), EPSILON);
        assertEquals(2.8, query.near().maxX(), EPSILON);
        assertEquals(65, query.feetY(), EPSILON);
        assertEquals(EntityEvacuator.SEARCH_RADIUS, query.radius());
    }

    @Test
    void aRiderIsMovedWithItsMount() {
        org.bukkit.entity.Entity horse = org.mockito.Mockito.mock(org.bukkit.entity.Entity.class);
        org.bukkit.entity.Entity rider = org.mockito.Mockito.mock(org.bukkit.entity.Entity.class);
        org.mockito.Mockito.when(rider.getVehicle()).thenReturn(horse);

        assertEquals(horse, EntityEvacuator.rootVehicle(rider));
        assertEquals(horse, EntityEvacuator.rootVehicle(horse));
    }
}
