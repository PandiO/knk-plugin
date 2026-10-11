package net.knightsandkings.knk.paper.gates;

import java.util.Set;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import net.knightsandkings.knk.core.gates.safety.GateSweep;
import net.knightsandkings.knk.core.gates.safety.GateSafeSpotFinder.CellFilter;
import net.knightsandkings.knk.core.util.BlockKey;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;

/**
 * Which cells a moving gate door is about to fill (KNG-106), so {@code GateAnimationTask} can move
 * entities out of the way <em>before</em> the blocks are placed rather than after.
 * <p>
 * The cells come from {@link GateSweep}: every frame between the previous update and the next two
 * updates in the door's direction (down while closing, up while opening), including the cells a
 * block crosses between frames (a drawbridge's or rotating door's arc). When that window reaches
 * the resting frame, the resting frame's own cells (rasterized gap-fill included) are added too.
 * Before KNG-106 this predicted frames upwards only, so a closing door - the case that crushes
 * people - was checked against frames it had already passed.
 */
public class CollisionPredictor {
    /** How many updates ahead count as "about to be placed". */
    static final int LOOKAHEAD_UPDATES = 2;
    private static final double EPSILON = 1e-3;

    private CollisionPredictor() {
    }

    /**
     * The cells the door's blocks occupy or cross from the previous update's frame through
     * {@link #LOOKAHEAD_UPDATES} updates after {@code currentFrame}.
     */
    public static Set<Long> upcomingCells(CachedGateDoor gate, int currentFrame, boolean rasterizationEnabled) {
        int tickRate = Math.max(1, gate.getAnimationTickRate());
        int from = GateSweep.ahead(gate, currentFrame, 0) - GateSweep.step(gate) * tickRate;
        from = Math.max(0, Math.min(gate.getAnimationDurationTicks(), from));
        int to = GateSweep.ahead(gate, currentFrame, LOOKAHEAD_UPDATES * tickRate);
        return withRestingCells(gate, GateSweep.cells(gate, from, to), to, rasterizationEnabled);
    }

    /** Every cell the door will still fill from {@code currentFrame} until it comes to rest. */
    public static Set<Long> remainingCells(CachedGateDoor gate, int currentFrame, boolean rasterizationEnabled) {
        int end = GateSweep.endFrame(gate);
        return withRestingCells(gate, GateSweep.cells(gate, currentFrame, end), end, rasterizationEnabled);
    }

    private static Set<Long> withRestingCells(CachedGateDoor gate, Set<Long> cells, int frame, boolean rasterizationEnabled) {
        if (frame == GateSweep.endFrame(gate)) {
            for (var position : GateRestingFramePlacer.restingFramePositions(gate, frame, rasterizationEnabled)) {
                cells.add(GateSpatialIndex.packCell(position));
            }
        }
        return cells;
    }

    /** Whether any block cell the box overlaps (touching a face doesn't count) is one of {@code cells}. */
    public static boolean overlaps(BoundingBox box, Set<Long> cells) {
        if (box == null || cells.isEmpty()) {
            return false;
        }
        int minX = (int) Math.floor(box.getMinX() + EPSILON);
        int maxX = (int) Math.floor(box.getMaxX() - EPSILON);
        int minY = (int) Math.floor(box.getMinY() + EPSILON);
        int maxY = (int) Math.floor(box.getMaxY() - EPSILON);
        int minZ = (int) Math.floor(box.getMinZ() + EPSILON);
        int maxZ = (int) Math.floor(box.getMaxZ() - EPSILON);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (cells.contains(GateSpatialIndex.packCell(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** {@link #overlaps} for an entity's current box. */
    public static boolean overlaps(Entity entity, Set<Long> cells) {
        return entity != null && overlaps(entity.getBoundingBox(), cells);
    }

    /** The cells as a filter for the safe-spot search. */
    public static CellFilter asFilter(Set<Long> cells) {
        return (x, y, z) -> cells.contains(GateSpatialIndex.packCell(x, y, z));
    }

    /** The box holding every cell (block corners), or null for none; used to look for entities near the cells. */
    public static BoundingBox boundsOf(Set<Long> cells) {
        BoundingBox box = null;
        for (long cell : cells) {
            int x = BlockKey.x(cell);
            int y = BlockKey.y(cell);
            int z = BlockKey.z(cell);
            BoundingBox block = new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
            box = box == null ? block : box.union(block);
        }
        return box;
    }
}
