package net.knightsandkings.knk.paper.npc.nav;

import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

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
}
