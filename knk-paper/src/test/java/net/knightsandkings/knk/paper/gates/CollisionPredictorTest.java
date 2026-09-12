package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollisionPredictorTest {

    @Test
    void shouldDetectImmediateCollision() {
        CachedGateDoor gate = buildStaticGate("north");
        gate.addBlock(new BlockSnapshot(1, new Vector(0, 0, 0), 1, "stone", 0));

        Entity entity = mock(Entity.class);
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(-0.5, 0, -0.5, 0.5, 1.8, 0.5));

        int framesToCollision = CollisionPredictor.predictCollision(gate, entity, 0);
        assertEquals(0, framesToCollision);
    }

    @Test
    void shouldReturnMaxValueWhenNoCollision() {
        CachedGateDoor gate = buildStaticGate("north");
        gate.addBlock(new BlockSnapshot(1, new Vector(0, 0, 0), 1, "stone", 0));

        Entity entity = mock(Entity.class);
        when(entity.getBoundingBox()).thenReturn(new BoundingBox(10, 0, 10, 11, 1.8, 11));

        int framesToCollision = CollisionPredictor.predictCollision(gate, entity, 0);
        assertEquals(Integer.MAX_VALUE, framesToCollision);
    }

    private CachedGateDoor buildStaticGate(String faceDirection) {
        CachedGateDoor gate = new CachedGateDoor(
            1,
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
            faceDirection
        );

        gate.setMotionVector(new Vector(0, 0, 0));
        gate.setUAxis(new Vector(1, 0, 0));
        gate.setVAxis(new Vector(0, 1, 0));
        gate.setNAxis(new Vector(0, 0, 1));

        return gate;
    }
}
