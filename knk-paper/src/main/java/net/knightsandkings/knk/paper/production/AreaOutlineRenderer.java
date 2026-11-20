package net.knightsandkings.knk.paper.production;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.production.survey.AreaOutline;
import net.knightsandkings.knk.core.production.survey.AreaShape;

/**
 * {@code /knk production area show} (PRODUCTION_KINDS § 3.3; DESIGN § 15 item 5: particle outlines are a Paper detail):
 * draws an area's outline with dust particles only the viewer sees, at their feet height (within the area's y range),
 * every half second for a while. Exclusive areas are orange, shared ones green. One display per player; a new one
 * replaces it.
 */
public final class AreaOutlineRenderer {

    /** Particles per refresh at most. */
    static final int MAX_POINTS = 600;
    /** Only points this close to the viewer are drawn. */
    static final double VIEW_DISTANCE = 64;

    private final Plugin plugin;
    private final Map<UUID, BukkitTask> shows = new HashMap<>();

    public AreaOutlineRenderer(Plugin plugin) {
        this.plugin = plugin;
    }

    public record Outline(AreaShape shape, boolean exclusive) {
    }

    /** Main thread. */
    public void show(Player player, List<Outline> outlines, int seconds) {
        stop(player);
        int[] remaining = {Math.max(1, seconds) * 2};
        UUID id = player.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null || remaining[0]-- <= 0) {
                stop(id);
                return;
            }
            for (Outline outline : outlines) {
                draw(viewer, outline);
            }
        }, 0, 10);
        shows.put(id, task);
    }

    public void stop(Player player) {
        stop(player.getUniqueId());
    }

    public void stopAll() {
        shows.values().forEach(BukkitTask::cancel);
        shows.clear();
    }

    private void stop(UUID id) {
        BukkitTask task = shows.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    private static void draw(Player viewer, Outline outline) {
        Location at = viewer.getLocation();
        AreaShape shape = outline.shape();
        double y = Math.max(shape.minY(), Math.min(shape.maxY() + 1, at.getY())) + 0.3;
        Particle.DustOptions dust = new Particle.DustOptions(outline.exclusive() ? Color.ORANGE : Color.LIME, 1.2f);
        for (double[] point : AreaOutline.points(shape, 1, MAX_POINTS)) {
            double dx = point[0] - at.getX();
            double dz = point[1] - at.getZ();
            if (dx * dx + dz * dz <= VIEW_DISTANCE * VIEW_DISTANCE) {
                viewer.spawnParticle(Particle.DUST, point[0], y, point[1], 1, 0, 0, 0, 0, dust);
            }
        }
    }
}
