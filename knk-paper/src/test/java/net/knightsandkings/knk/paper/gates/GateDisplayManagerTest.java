package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGate;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for GateDisplayManager's pure resolveFaceDirectionVector helper - kept independent
 * of Bukkit World/TextDisplay/Plugin so it runs without a live server, matching
 * GatePassThroughServiceTest's approach for the same kind of basis-vector math. Written as a
 * regression guard for the diagonal-gate lattice-step fix: this logic reads FaceDirection/nAxis
 * (unit vectors) and must stay correct without ever needing the new uStep/vStep/nStep fields.
 */
class GateDisplayManagerTest {
    private static final double EPSILON = 1e-6;

    @Test
    void resolveFaceDirectionVectorUsesDiagonalFaceDirectionDirectly() {
        CachedGate gate = new CachedGate(
            1, "DiagonalGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 0, 0), 4, 5, 1,
            500.0, 500.0, true, false, true, 90,
            "south-east"
        );

        Vector direction = GateDisplayManager.resolveFaceDirectionVector(gate);

        double invSqrt2 = 1.0 / Math.sqrt(2.0);
        assertEquals(invSqrt2, direction.getX(), EPSILON);
        assertEquals(0, direction.getY(), EPSILON);
        assertEquals(invSqrt2, direction.getZ(), EPSILON);
    }

    @Test
    void resolveFaceDirectionVectorFallsBackToDiagonalNAxisWhenFaceDirectionMissing() {
        CachedGate gate = new CachedGate(
            2, "NoFaceDirectionGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 0, 0), 4, 5, 1,
            500.0, 500.0, true, false, true, 90,
            ""
        );
        double invSqrt2 = 1.0 / Math.sqrt(2.0);
        // Deliberately not setting uStep/vStep/nStep - this fallback must not need them.
        gate.setNAxis(new Vector(invSqrt2, 0, invSqrt2));

        Vector direction = GateDisplayManager.resolveFaceDirectionVector(gate);

        assertEquals(invSqrt2, direction.getX(), EPSILON);
        assertEquals(0, direction.getY(), EPSILON);
        assertEquals(invSqrt2, direction.getZ(), EPSILON);
    }

    @Test
    void resolveFaceDirectionVectorReturnsZeroVectorWhenNothingResolvable() {
        CachedGate gate = new CachedGate(
            3, "NoDirectionGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 0, 0), 4, 5, 1,
            500.0, 500.0, true, false, true, 90,
            ""
        );

        Vector direction = GateDisplayManager.resolveFaceDirectionVector(gate);

        assertEquals(0, direction.getX(), EPSILON);
        assertEquals(0, direction.getY(), EPSILON);
        assertEquals(0, direction.getZ(), EPSILON);
    }
}
