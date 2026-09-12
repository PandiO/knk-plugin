package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFrameCalculator;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Predicts gate block collisions against an entity within a small future window.
 */
public class CollisionPredictor {
    private static final int DEFAULT_LOOKAHEAD_FRAMES = 5;

    public static int predictCollision(CachedGateDoor gate, Entity entity, int currentFrame) {
        return predictCollision(gate, entity, currentFrame, DEFAULT_LOOKAHEAD_FRAMES);
    }

    public static int predictCollision(CachedGateDoor gate, Entity entity, int currentFrame, int lookaheadFrames) {
        if (gate == null || entity == null || lookaheadFrames <= 0) {
            return Integer.MAX_VALUE;
        }

        if (gate.getBlocks() == null || gate.getBlocks().isEmpty()) {
            return Integer.MAX_VALUE;
        }

        int totalFrames = gate.getAnimationDurationTicks();
        int startFrame = Math.max(0, currentFrame);
        int endFrame = Math.min(totalFrames, startFrame + lookaheadFrames);

        BoundingBox entityBox = entity.getBoundingBox();

        for (int frame = startFrame; frame <= endFrame; frame++) {
            for (BlockSnapshot block : gate.getBlocks()) {
                Vector position = GateFrameCalculator.calculateBlockPosition(gate, block, frame);
                if (position == null) {
                    continue;
                }
                BoundingBox blockBox = new BoundingBox(
                    position.getX(),
                    position.getY(),
                    position.getZ(),
                    position.getX() + 1.0,
                    position.getY() + 1.0,
                    position.getZ() + 1.0
                );

                if (blockBox.overlaps(entityBox)) {
                    return frame - startFrame;
                }
            }
        }

        return Integer.MAX_VALUE;
    }
}
