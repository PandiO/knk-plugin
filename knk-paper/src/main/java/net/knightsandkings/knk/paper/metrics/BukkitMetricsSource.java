package net.knightsandkings.knk.paper.metrics;

import net.knightsandkings.knk.core.metrics.MinecraftMetricsCollector;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/** Reads the live server for {@link MinecraftMetricsCollector#sample}. Main thread only. */
public final class BukkitMetricsSource implements MinecraftMetricsCollector.Source {

    @Override
    public double[] tps() {
        return Bukkit.getTPS();
    }

    @Override
    public long[] tickTimes() {
        return Bukkit.getTickTimes();
    }

    @Override
    public int playersOnline() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public List<MinecraftMetricsCollector.WorldCounts> worlds() {
        List<MinecraftMetricsCollector.WorldCounts> counts = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            counts.add(new MinecraftMetricsCollector.WorldCounts(world.getName(), world.getChunkCount(),
                world.getEntityCount(), world.getTileEntityCount(), world.getPlayerCount()));
        }
        return counts;
    }
}
