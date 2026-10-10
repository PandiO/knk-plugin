package net.knightsandkings.knk.core.gates.safety;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.util.Vector;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFrameCalculator;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;

/**
 * The cells a moving gate door's blocks pass through between two animation frames (KNG-106), as
 * {@link GateSpatialIndex#packCell packed} block cells.
 * <p>
 * Every frame in between is visited, not just the frames the animation task places (it places
 * every {@code AnimationTickRate}-th one), and for each block the box spanned by its position in
 * two consecutive frames is added - so a block that moves more than one cell per frame, or swings
 * along an arc (a drawbridge, a rotating door), still covers every cell it crosses. Both the
 * closed-scan blocks and the open-only blocks that animate across the swing (gate item 6.10) count.
 * Closing counts frames down (duration to 0), opening counts them up, like {@code GateAnimationTask}.
 * Bukkit-free apart from {@link Vector}, which knk-core already uses for gate geometry.
 */
public final class GateSweep {

    private GateSweep() {
    }

    /**
     * The frame after {@code frame} in the direction the door is moving: down while closing, up
     * otherwise.
     */
    public static int step(CachedGateDoor gate) {
        return gate.getCurrentState() == AnimationState.CLOSING ? -1 : 1;
    }

    /**
     * {@code frames} frames ahead of {@code frame} in the door's direction, clamped to 0..duration.
     */
    public static int ahead(CachedGateDoor gate, int frame, int frames) {
        int target = frame + step(gate) * Math.max(0, frames);
        return Math.max(0, Math.min(gate.getAnimationDurationTicks(), target));
    }

    /** The resting frame the door is moving towards: 0 while closing, its duration otherwise. */
    public static int endFrame(CachedGateDoor gate) {
        return gate.getCurrentState() == AnimationState.CLOSING ? 0 : gate.getAnimationDurationTicks();
    }

    /** The cells the door's blocks occupy or cross from {@code fromFrame} to {@code toFrame}, both included. */
    public static Set<Long> cells(CachedGateDoor gate, int fromFrame, int toFrame) {
        Set<Long> cells = new HashSet<>();
        if (gate == null) {
            return cells;
        }
        int direction = toFrame >= fromFrame ? 1 : -1;
        for (BlockSnapshot block : gate.getBlocks()) {
            if (block != null) {
                addPath(cells, fromFrame, toFrame, direction, frame -> GateFrameCalculator.calculateBlockPosition(gate, block, frame));
            }
        }
        for (BlockSnapshot openBlock : gate.getOpenBlocks()) {
            if (openBlock == null || gate.getOpenOnlyBlockSynthesizedRelativePosition(openBlock.getId()) == null) {
                continue;
            }
            addPath(cells, fromFrame, toFrame, direction,
                frame -> GateFrameCalculator.calculateOpenOnlyBlockPosition(gate, openBlock, frame));
        }
        return cells;
    }

    @FunctionalInterface
    private interface Position {
        Vector at(int frame);
    }

    private static void addPath(Set<Long> cells, int fromFrame, int toFrame, int direction, Position position) {
        Vector previous = null;
        for (int frame = fromFrame; ; frame += direction) {
            Vector current = safe(position, frame);
            if (current != null) {
                addBox(cells, previous != null ? previous : current, current);
            }
            previous = current;
            if (frame == toFrame) {
                break;
            }
        }
    }

    private static Vector safe(Position position, int frame) {
        try {
            return position.at(frame);
        } catch (RuntimeException ex) {
            // a door whose geometry can't be computed for this frame has nothing to place there either
            return null;
        }
    }

    /** Every cell of the box spanned by the two block positions (the block cells they're placed in). */
    private static void addBox(Set<Long> cells, Vector a, Vector b) {
        int minX = Math.min(a.getBlockX(), b.getBlockX());
        int maxX = Math.max(a.getBlockX(), b.getBlockX());
        int minY = Math.min(a.getBlockY(), b.getBlockY());
        int maxY = Math.max(a.getBlockY(), b.getBlockY());
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        int maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cells.add(GateSpatialIndex.packCell(x, y, z));
                }
            }
        }
    }
}
