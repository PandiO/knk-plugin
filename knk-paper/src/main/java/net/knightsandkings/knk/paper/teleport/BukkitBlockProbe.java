package net.knightsandkings.knk.paper.teleport;

import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.World;

import net.knightsandkings.knk.core.teleport.BlockProbe;
import net.knightsandkings.knk.core.teleport.SafeLocationFinder;

/**
 * {@link BlockProbe} over a Bukkit world, for {@link SafeLocationFinder}. Main thread only; the
 * engine loads the destination chunk ({@code getChunkAtAsync}) before probing it.
 */
public final class BukkitBlockProbe implements BlockProbe {

    private final World world;

    public BukkitBlockProbe(World world) {
        this.world = Objects.requireNonNull(world, "world must not be null");
    }

    @Override
    public boolean isPassable(int x, int y, int z) {
        return world.getBlockAt(x, y, z).isPassable();
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType().isSolid();
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        Material type = world.getBlockAt(x, y, z).getType();
        return SafeLocationFinder.HAZARD_MATERIALS.contains(type.name());
    }

    @Override
    public int minY() {
        return world.getMinHeight();
    }

    @Override
    public int maxY() {
        return world.getMaxHeight();
    }
}
