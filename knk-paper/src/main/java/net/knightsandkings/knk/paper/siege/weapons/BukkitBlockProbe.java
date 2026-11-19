package net.knightsandkings.knk.paper.siege.weapons;

import net.knightsandkings.knk.core.siege.weapons.SpotSiteCheck;
import org.bukkit.World;
import org.bukkit.block.Block;

/** {@link SpotSiteCheck.BlockProbe} on a Bukkit world: solid = motion-blocking, clear = empty or replaceable. */
public record BukkitBlockProbe(World world) implements SpotSiteCheck.BlockProbe {

    @Override
    public boolean isSolid(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType().isSolid();
    }

    @Override
    public boolean isClear(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        return block.isEmpty() || block.isReplaceable();
    }
}
