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

import net.knightsandkings.knk.core.npc.nav.NavWorld;
import net.knightsandkings.knk.paper.regions.RegionIds;

/** {@link NavWorld} on the Bukkit world: loaded chunks only, never loading one. Main thread. */
public final class BukkitNavWorld implements NavWorld {

    private final RegionIds regionIds;

    /** @param regionIds the shared WorldGuard region query, or null (no regions known) */
    public BukkitNavWorld(RegionIds regionIds) {
        this.regionIds = regionIds;
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
        return type.isSolid() && !Tag.LEAVES.isTagged(type) && w.getBlockAt(x, floorY + 1, z).isPassable()
            && w.getBlockAt(x, floorY + 2, z).isPassable();
    }

    @Override
    public Set<String> regionsAt(String world, int x, int feetY, int z) {
        World w = Bukkit.getWorld(world);
        return regionIds == null || w == null ? Set.of() : regionIds.at(w, x, feetY, z);
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
            if (!block.isPassable()) {
                return k + (isSlab(block.getBlockData(), Slab.Type.TOP) ? 0.5 : 0.0) - floorTop;
            }
        }
        return 5 - floorTop;
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
        if (block.isPassable()) {
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
