package net.knightsandkings.knk.paper.gates;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * One {@link GateAnimationTask} per loaded world (KNG-113, audit G2): started for every world at enable and for a world
 * loaded later, stopped when its world unloads. Before this, a world loaded after the plugin enabled never animated its
 * gates.
 */
public final class GateWorldTasks implements Listener {

    private static final Logger LOGGER = Logger.getLogger(GateWorldTasks.class.getName());

    private final Function<World, BukkitTask> start;
    private final Map<String, BukkitTask> tasks = new ConcurrentHashMap<>();

    /** @param start schedules a world's animation task (main thread) */
    public GateWorldTasks(Function<World, BukkitTask> start) {
        this.start = Objects.requireNonNull(start, "start");
    }

    /** Starts the world's task unless it already runs. */
    public void start(World world) {
        tasks.computeIfAbsent(key(world), k -> start.apply(world));
    }

    public void stop(World world) {
        BukkitTask task = tasks.remove(key(world));
        if (task != null) {
            task.cancel();
            LOGGER.info("[GateAnimation] Stopped the animation task of unloaded world '" + world.getName() + "'");
        }
    }

    public int running() {
        return tasks.size();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        start(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        stop(event.getWorld());
    }

    private static String key(World world) {
        return world.getName().toLowerCase(Locale.ROOT);
    }
}
