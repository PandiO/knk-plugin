package net.knightsandkings.knk.paper.production;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.ports.api.TransportLanesApi;
import net.knightsandkings.knk.core.production.transport.LaneJob;
import net.knightsandkings.knk.core.production.transport.LaneMeasurer;
import net.knightsandkings.knk.core.production.transport.LaneSettings;
import net.knightsandkings.knk.core.production.transport.RoadConnectionBook;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RouterParameters;

/**
 * The lane measurer on the game server (PandiO/knk-workspace#681, DESIGN § 7.6, IMPLEMENTATION_PLAN § P5 "Plugin"), only
 * with {@code production.enabled: true}: a headless job reads the API's lane list every {@code interval-minutes},
 * measures each pair over the plugin's road snapshot ({@code RoadNetworkCache}, read-only) on the {@code KnK-lanes}
 * thread, reports the lanes and sets the producers' road connections. {@code /knk logistics lanes measure} runs it now.
 * The main thread only starts the run: nothing is measured, read or sent on it.
 */
public final class LaneModule {

    private final Plugin plugin;
    private final LaneSettings settings;
    private final ExecutorService measuring;
    private final LaneJob job;
    private BukkitTask task;

    /**
     * @param snapshots  the road snapshot per world (null when the road network is not loaded)
     * @param parameters the router's parameters (navigation's class costs and snap weight), read per lane
     */
    public LaneModule(Plugin plugin, LaneSettings settings, TransportLanesApi api,
                      Function<String, RoadNetworkSnapshot> snapshots, Supplier<RouterParameters> parameters) {
        this.plugin = plugin;
        this.settings = settings;
        this.measuring = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "KnK-lanes");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        LaneMeasurer measurer = new LaneMeasurer(snapshots, parameters, settings.maxSnapDistance(), Clock.systemUTC());
        this.job = new LaneJob(api, measurer, new RoadConnectionBook(), settings, measuring, plugin.getLogger(),
                Clock.systemUTC());
    }

    public void start() {
        if (settings.intervalMinutes() > 0) {
            long period = TimeUnit.MINUTES.toSeconds(settings.intervalMinutes()) * 20;
            // the first run after two minutes: the road network is downloaded at start
            task = Bukkit.getScheduler().runTaskTimer(plugin, () -> job.run("schedule"), 2 * 60 * 20L, period);
        }
        plugin.getLogger().info("Lane measurer is on (production.enabled): " + (settings.intervalMinutes() > 0
                ? "every " + settings.intervalMinutes() + " min" : "only on /knk logistics lanes measure") + ", "
                + settings.batch() + " pairs per run, road connections "
                + (settings.roadConnections() ? "on" : "off") + ".");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        measuring.shutdownNow();
    }

    public CompletableFuture<LaneJob.Summary> measureNow(String why) {
        return job.run(why);
    }

    public LaneJob job() {
        return job;
    }

    public LaneSettings settings() {
        return settings;
    }
}
