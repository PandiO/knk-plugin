package net.knightsandkings.knk.paper.metrics;

import net.knightsandkings.knk.core.metrics.HostMetricsCollector;
import net.knightsandkings.knk.core.metrics.MetricsRegistry;
import net.knightsandkings.knk.core.metrics.MinecraftMetricsCollector;
import net.knightsandkings.knk.core.metrics.PluginMetrics;
import net.knightsandkings.knk.paper.config.KnkConfig;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.logging.Level;

/**
 * Performance metrics (KNG-232): the registry, the main-thread sampler and the {@code /metrics} server, started
 * and stopped as one unit by {@code KnKPlugin}. With {@code metrics.enabled: false} nothing is created and the
 * instruments in {@link PluginMetrics} stay off.
 */
public final class MetricsService {
    private final Plugin plugin;
    private final KnkConfig.MetricsConfig config;
    private MetricsHttpServer server;
    private BukkitTask sampler;

    public MetricsService(Plugin plugin, KnkConfig.MetricsConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        if (!config.enabled()) {
            plugin.getLogger().info("Performance metrics disabled (metrics.enabled: false)");
            return;
        }
        MetricsRegistry registry = new MetricsRegistry();
        MinecraftMetricsCollector minecraft = new MinecraftMetricsCollector();
        registry.register(minecraft);
        registry.register(new HostMetricsCollector());
        registry.register(PluginMetrics.API_CALLS);
        registry.register(PluginMetrics.TASKS);
        try {
            MetricsHttpServer started = new MetricsHttpServer(registry, config.bindAddress(), config.port(), config.token());
            started.start();
            server = started;
        } catch (IOException | RuntimeException e) {
            // Metrics must never stop the game server from starting.
            plugin.getLogger().log(Level.SEVERE, "Performance metrics endpoint did not start: " + e.getMessage(), e);
            return;
        }
        PluginMetrics.setEnabled(true);
        BukkitMetricsSource source = new BukkitMetricsSource();
        long ticks = config.sampleSeconds() * 20L;
        sampler = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                minecraft.sample(source);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Metrics sample failed: " + e, e);
            }
        }, 1L, ticks);
    }

    public void stop() {
        PluginMetrics.setEnabled(false);
        if (sampler != null) {
            sampler.cancel();
            sampler = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
