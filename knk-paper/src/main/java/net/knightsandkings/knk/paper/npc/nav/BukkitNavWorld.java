package net.knightsandkings.knk.paper.npc.nav;

import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import net.knightsandkings.knk.core.navigation.shape.NavWorld;
import net.knightsandkings.knk.paper.regions.RegionIds;

/** {@link NavWorld} on the Bukkit world: loaded chunks only, never loading one. Main thread. */
public final class BukkitNavWorld implements NavWorld {

    private final RegionIds regionIds;

    /** @param regionIds the shared WorldGuard region query, or null (no regions known) */
    public BukkitNavWorld(RegionIds regionIds) {
        this.regionIds = regionIds;
    }

    /** Whether WorldGuard's regions are known here (false: {@link #regionsAt} is always empty). */
    public boolean knowsRegions() {
        return regionIds != null;
    }

    @Override
    public boolean isLoaded(String world, int x, int z) {
        World w = Bukkit.getWorld(world);
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public boolean isStandableFloor(String world, int x, int floorY, int z) {
        World w = Bukkit.getWorld(world);
        if (w == null || floorY <= w.getMinHeight() || floorY + 2 >= w.getMaxHeight()
            || !w.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        Block floor = w.getBlockAt(x, floorY, z);
        Material type = floor.getType();
        return type.isSolid() && !Tag.LEAVES.isTagged(type) && passable(w.getBlockAt(x, floorY + 1, z))
            && passable(w.getBlockAt(x, floorY + 2, z));
    }

    /**
     * Whether a body passes through a block: no collision, or an open door or fence gate (link C1,
     * PandiO/knk-workspace#700). An open door's panel stands at the side of its cell, so a walk through the doorway
     * passes it; before, every door was a wall, and a walk through an open doorway (the keep tower's) was a gap no
     * search could take.
     */
    static boolean passable(Block block) {
        if (block.isPassable()) {
            return true;
        }
        BlockData data = block.getBlockData();
        return (data instanceof org.bukkit.block.data.type.Door || data instanceof org.bukkit.block.data.type.Gate)
            && ((org.bukkit.block.data.Openable) data).isOpen();
    }

    @Override
    public Set<String> regionsAt(String world, int x, int feetY, int z) {
        World w = Bukkit.getWorld(world);
        return regionIds == null || w == null ? Set.of() : regionIds.at(w, x, feetY, z);
    }

    /**
     * Where a body walks through a cell with an open door or fence gate (link C1, PandiO/knk-workspace#700): the middle
     * of the opening the panel leaves, as block-relative {x, z}. An open door's panel takes 3/16 of its cell at one
     * side, so the opening is 13/16 wide and a body 0.6 wide fits only within about 0.1 of its middle; a follower a
     * little off the cell's centre stuck on the keep tower's door panel. {0.5, 0.5} for any other block.
     */
    public static double[] openingCentre(Block block) {
        if (block == null || block.isPassable()) {
            return new double[] {0.5, 0.5};
        }
        BlockData data = block.getBlockData();
        if (!((data instanceof org.bukkit.block.data.type.Door || data instanceof org.bukkit.block.data.type.Gate)
            && ((org.bukkit.block.data.Openable) data).isOpen())) {
            return new double[] {0.5, 0.5};
        }
        return openingCentre(block.getCollisionShape().getBoundingBoxes());
    }

    /** The middle of the free part of a cell beside panels that span it (block-relative {x, z}). */
    static double[] openingCentre(java.util.Collection<org.bukkit.util.BoundingBox> boxes) {
        double x0 = 0;
        double x1 = 1;
        double z0 = 0;
        double z1 = 1;
        for (org.bukkit.util.BoundingBox box : boxes) {
            boolean spansX = box.getMinX() <= 1e-6 && box.getMaxX() >= 1 - 1e-6;
            boolean spansZ = box.getMinZ() <= 1e-6 && box.getMaxZ() >= 1 - 1e-6;
            if (spansX && !spansZ) {
                if (box.getMinZ() <= 1e-6) {
                    z0 = Math.max(z0, box.getMaxZ());
                } else if (box.getMaxZ() >= 1 - 1e-6) {
                    z1 = Math.min(z1, box.getMinZ());
                }
            } else if (spansZ && !spansX) {
                if (box.getMinX() <= 1e-6) {
                    x0 = Math.max(x0, box.getMaxX());
                } else if (box.getMaxX() >= 1 - 1e-6) {
                    x1 = Math.min(x1, box.getMinX());
                }
            }
        }
        return new double[] {x1 > x0 ? (x0 + x1) / 2 : 0.5, z1 > z0 ? (z0 + z1) / 2 : 0.5};
    }

    /**
     * The free height above a floor (implementation plan 4b.2 item 4): from the floor's top (a bottom slab's at half a
     * block) to the bottom of the first block above it a body cannot pass (a top slab's at half a block), at most
     * four blocks up. 0 for a floor an NPC cannot stand on or in an unloaded chunk.
     */
    @Override
    public double freeHeight(String world, int x, int floorY, int z) {
        if (!isStandableFloor(world, x, floorY, z)) {
            return 0;
        }
        World w = Bukkit.getWorld(world);
        int top = w.getMaxHeight();
        return freeHeight(w.getBlockAt(x, floorY, z), k -> floorY + k < top ? w.getBlockAt(x, floorY + k, z) : null);
    }

    /**
     * The free height above {@code floor}, given the blocks above it ({@code above.apply(1)} is the one right above;
     * null past the world's top).
     */
    static double freeHeight(Block floor, java.util.function.IntFunction<Block> above) {
        double floorTop = isSlab(floor.getBlockData(), Slab.Type.BOTTOM) ? 0.5 : 1.0;
        for (int k = 1; k <= 4; k++) {
            Block block = above.apply(k);
            if (block == null) {
                break;
            }
            if (!passable(block)) {
                return k + (isSlab(block.getBlockData(), Slab.Type.TOP) ? 0.5 : 0.0) - floorTop;
            }
        }
        return 5 - floorTop;
    }

    /**
     * The walking surface of a floor (link B4, PandiO/knk-workspace#673): the top of the floor block's step, the box
     * that spans the whole cell (a bottom slab 0.5, an upright stair's step 0.5, an upside-down stair 1.0, a path
     * block 0.9375), at most a whole block; {@code floorY + 1} where the floor is not standable. A stair's upper half
     * is not the waypoint's height: the engine jumps for a target a whole block up within a block, and the step is
     * walked up half a block at a time.
     */
    @Override
    public double surfaceY(String world, double x, int floorY, double z) {
        if (!isStandableFloor(world, (int) Math.floor(x), floorY, (int) Math.floor(z))) {
            return floorY + 1;
        }
        World w = Bukkit.getWorld(world);
        Block floor = w.getBlockAt((int) Math.floor(x), floorY, (int) Math.floor(z));
        return floorY + surfaceTop(floor.getCollisionShape().getBoundingBoxes());
    }

    /**
     * The top of a block's step (block-relative boxes, 0 to 1): the highest top among the boxes that span the whole
     * cell, else the highest of all; clamped to (0, 1], 1 without any box.
     */
    static double surfaceTop(java.util.Collection<org.bukkit.util.BoundingBox> boxes) {
        double spanning = -1;
        double any = -1;
        for (org.bukkit.util.BoundingBox box : boxes) {
            any = Math.max(any, box.getMaxY());
            if (box.getMinX() <= 1e-6 && box.getMaxX() >= 1 - 1e-6 && box.getMinZ() <= 1e-6
                && box.getMaxZ() >= 1 - 1e-6) {
                spanning = Math.max(spanning, box.getMaxY());
            }
        }
        double top = spanning > 0 ? spanning : any;
        return top <= 0 || top > 1 ? 1.0 : top;
    }

    /**
     * Whether a block stops a walking body (implementation plan 4b.2 items 1 and 13): anything not passable, except a
     * step the body walks up (a bottom slab, a stair that is not upside down). False in an unloaded chunk.
     */
    @Override
    public boolean blocksBody(String world, int x, int y, int z) {
        World w = Bukkit.getWorld(world);
        if (w == null || y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        return blocksBody(w.getBlockAt(x, y, z));
    }

    static boolean blocksBody(Block block) {
        if (passable(block)) {
            return false;
        }
        BlockData data = block.getBlockData();
        if (isSlab(data, Slab.Type.BOTTOM)) {
            return false;
        }
        return !(data instanceof Stairs stairs && stairs.getHalf() == Bisected.Half.BOTTOM);
    }

    private static boolean isSlab(BlockData data, Slab.Type type) {
        return data instanceof Slab slab && slab.getType() == type;
    }
}
