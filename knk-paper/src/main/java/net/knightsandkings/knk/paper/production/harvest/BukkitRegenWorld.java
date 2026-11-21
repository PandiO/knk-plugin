package net.knightsandkings.knk.paper.production.harvest;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;

import net.knightsandkings.knk.core.production.harvest.RegenWorld;

/** The regeneration queue's view of the live worlds (PandiO/knk-workspace#134). Main thread. */
final class BukkitRegenWorld implements RegenWorld {

    @Override
    public boolean chunkLoaded(String world, int chunkX, int chunkZ) {
        World w = Bukkit.getWorld(world);
        return w != null && w.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public String blockData(String world, int x, int y, int z) {
        World w = Bukkit.getWorld(world);
        return w == null ? null : w.getBlockAt(x, y, z).getBlockData().getAsString();
    }

    @Override
    public boolean occupied(String world, int x, int y, int z) {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return false;
        }
        BoundingBox box = new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
        for (Entity entity : w.getNearbyEntities(box)) {
            if (entity instanceof LivingEntity && entity.getBoundingBox().overlaps(box)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void set(String world, int x, int y, int z, String blockData) {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return;
        }
        Block block = w.getBlockAt(x, y, z);
        block.setBlockData(Bukkit.createBlockData(blockData), false);
    }
}
