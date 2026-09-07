package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGate;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EntityPusherTest {

    @Test
    void shouldPushEntityInFaceDirection() {
        CachedGate gate = new CachedGate(
            1,
            "TestGate",
            "SLIDING",
            "VERTICAL",
            "PLANE_GRID",
            60,
            1,
            new Vector(0, 0, 0),
            1,
            1,
            1,
            500.0,
            500.0,
            true,
            false,
            true,
            90,
            "east"
        );

        Entity entity = mock(Entity.class);

        EntityPusher.pushEntity(entity, gate);

        ArgumentCaptor<Vector> captor = ArgumentCaptor.forClass(Vector.class);
        verify(entity).setVelocity(captor.capture());

        Vector velocity = captor.getValue();
        assertTrue(velocity.getX() > 0, "Expected push to have positive X for east direction");
    }

    @Test
    void shouldPushEntityDiagonallyForDiagonalFaceDirection() {
        // Regression guard: pushEntity resolves direction from FaceDirection (a unit vector),
        // never from the new lattice uStep/vStep/nStep - a diagonal push must stay a clean
        // 45-degree direction, unaffected by introducing those fields.
        CachedGate gate = new CachedGate(
            2, "DiagonalGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 0, 0), 1, 1, 1,
            500.0, 500.0, true, false, true, 90,
            "south-east"
        );

        Entity entity = mock(Entity.class);
        EntityPusher.pushEntity(entity, gate);

        ArgumentCaptor<Vector> captor = ArgumentCaptor.forClass(Vector.class);
        verify(entity).setVelocity(captor.capture());

        Vector velocity = captor.getValue();
        assertTrue(velocity.getX() > 0, "Expected positive X for south-east push");
        assertTrue(velocity.getZ() > 0, "Expected positive Z for south-east push");
        assertEquals(velocity.getX(), velocity.getZ(), 1e-9,
            "South-east push should be a clean 45-degree diagonal");
    }

    @Test
    void shouldFallBackToDiagonalNAxisWhenFaceDirectionIsMissing() {
        // Regression guard: the nAxis fallback (used when FaceDirection is unset) must keep
        // working from the unit nAxis alone - it must not require the new nStep field.
        CachedGate gate = new CachedGate(
            3, "NoFaceDirectionGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 0, 0), 1, 1, 1,
            500.0, 500.0, true, false, true, 90,
            ""
        );
        double invSqrt2 = 1.0 / Math.sqrt(2.0);
        gate.setNAxis(new Vector(invSqrt2, 0, invSqrt2));

        Entity entity = mock(Entity.class);
        EntityPusher.pushEntity(entity, gate);

        ArgumentCaptor<Vector> captor = ArgumentCaptor.forClass(Vector.class);
        verify(entity).setVelocity(captor.capture());

        Vector velocity = captor.getValue();
        assertTrue(velocity.getX() > 0, "Expected positive X from diagonal nAxis fallback");
        assertEquals(velocity.getX(), velocity.getZ(), 1e-9,
            "Diagonal nAxis fallback should stay a clean 45-degree diagonal");
    }
}
