package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.DrawbridgeKeyframes;
import net.knightsandkings.knk.core.util.VectorMath;
import org.bukkit.util.Vector;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** KNG-193 test doors: a cardinal 4x6 drawbridge with its keyframes built, for the knk-paper readers of the keyframes. */
final class DrawbridgeKeyframeFixtures {
    private DrawbridgeKeyframeFixtures() {
    }

    /** A cardinal 4 wide x 6 tall drawbridge at (100,64,100), hinge along +x, swinging toward +z over 60 frames. */
    static CachedGateDoor cardinalDrawbridge(AnimationState state) {
        CachedGateDoor door = new CachedGateDoor(21, 21, "Cardinal Drawbridge", "DRAWBRIDGE", "ROTATION", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 4, 6, 1, 500.0, 500.0, true, false, true, 90, "south");
        door.setWorldName("world");
        door.setUAxis(new Vector(1, 0, 0));
        door.setVAxis(new Vector(0, 1, 0));
        door.setNAxis(new Vector(0, 0, 1));
        door.setUStep(new Vector(1, 0, 0));
        door.setVStep(new Vector(0, 1, 0));
        door.setNStep(new Vector(0, 0, 1));
        door.setHingeAxis(new Vector(1, 0, 0));
        door.setMotionVector(new Vector(0, 0, 0));
        door.setSublatticeIndex(VectorMath.sublatticeIndex(door.getUStep()));
        int id = 1;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 6; j++) {
                door.addBlock(new BlockSnapshot(id++, new Vector(i, j, 0), 1, "minecraft:spruce_planks", id));
            }
        }
        door.setCurrentState(state);
        door.setCurrentFrame(state == AnimationState.OPEN || state == AnimationState.CLOSING ? 60 : 0);
        return door;
    }

    /** The same door with its keyframed plank built (identity orientation; no Bukkit server in tests). */
    static CachedGateDoor keyframedCardinalDrawbridge(AnimationState state) {
        CachedGateDoor door = cardinalDrawbridge(state);
        DrawbridgeKeyframes.Result result = DrawbridgeKeyframes.build(door, 0, DrawbridgeKeyframes.Orienter.IDENTITY);
        assertNotNull(result.plan(), result.fallbackReason());
        door.setDrawbridgeKeyframes(result.plan());
        return door;
    }
}
