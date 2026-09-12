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
}
