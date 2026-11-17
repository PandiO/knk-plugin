package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * A scripted player walking into NPCs (link M7, PandiO/knk-workspace#627): the fake player of #540 is moved a little
 * every tick along a line, as a player walking at about 2.4 blocks a second, so its body overlaps an NPC's in the
 * server's entity ticks and pushes it the way a real player's does. The fake player is teleported in these short
 * steps: its movement packets are refused on Paper 26.3 ("Invalid move player packet"), and a teleport this short
 * changes nothing for the collision, which the server works out from the bodies' boxes each tick.
 */
final class ScenarioPushes {

    /** Blocks per tick the player walks (a player walks about 4.3 blocks a second; this is a slow push). */
    static final double STEP = 0.12;

    private ScenarioPushes() {
    }

    /** Walks the player from {@code from} to {@code to} (scene positions, feet), one {@link #STEP} per tick. */
    static BukkitTask sweep(ScenarioScene scene, ScenarioPlayer player, double[] from, double[] to) {
        player.teleport(from[0], from[1], from[2]);
        double len = Math.hypot(to[0] - from[0], to[2] - from[2]);
        int steps = (int) Math.ceil(len / STEP);
        int[] i = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(scene.services().plugin(), () -> {
            if (!player.online() || i[0] >= steps) {
                task[0].cancel();
                return;
            }
            i[0]++;
            double t = Math.min(1, i[0] / (double) steps);
            player.teleport(from[0] + (to[0] - from[0]) * t, from[1], from[2] + (to[2] - from[2]) * t);
        }, 1L, 1L);
        return task[0];
    }

    /** Ticks a sweep takes, with a few to spare. */
    static int ticks(double[] from, double[] to) {
        return (int) Math.ceil(Math.hypot(to[0] - from[0], to[2] - from[2]) / STEP) + 5;
    }

    /** A scene position as feet. */
    static NavPoint feet(ScenarioScene scene, double x, double y, double z) {
        var l = scene.location(x, y, z);
        return new NavPoint(scene.world().getName(), l.getX(), l.getY(), l.getZ());
    }

    static String blocks(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
