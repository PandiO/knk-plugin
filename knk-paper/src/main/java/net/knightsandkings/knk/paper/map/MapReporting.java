package net.knightsandkings.knk.paper.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;

import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;
import net.knightsandkings.knk.core.map.MapReportSender;
import net.knightsandkings.knk.core.map.MapReportingService;
import net.knightsandkings.knk.core.map.OnlinePlayerSnapshotPort;
import net.knightsandkings.knk.core.metrics.PluginMetrics;
import net.knightsandkings.knk.core.map.RegionRef;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionSpec;
import net.knightsandkings.knk.paper.realtime.RealtimeService;

/**
 * Wires the overview map's plugin reports (PandiO/knk-workspace#735, OVERVIEW_MAP_DESIGN § 7.2, § 7.3): the
 * {@link MapReportingService} with the WorldGuard {@link RegionOutlineAdapter} and the game hub as sender. Off unless
 * {@code map.reporting.enabled: true} (O27); off, nothing is hooked in and the plugin behaves as before. Reports travel
 * over the realtime channel, so they also need {@code realtime.enabled: true}.
 * <p>
 * L4 (PandiO/knk-workspace#738, § 5 rule 6, § 9.1): with a player source the live player report runs every
 * {@code map.reporting.interval-ticks} (default 40): the snapshot on the main thread, timed as
 * {@code map.player_snapshot} ({@link PluginMetrics#timed}), the send on an async task.
 */
public final class MapReporting {

    public static final String ENABLED_KEY = "map.reporting.enabled";
    public static final String INTERVAL_KEY = "map.reporting.interval-ticks";

    /** The task name in the plugin metrics ({@code knk_task_duration_seconds}). */
    public static final String PLAYER_SNAPSHOT_TASK = "map.player_snapshot";

    private MapReporting() {
    }

    /** The service for this config; the hub is read through {@code realtime} at send time (it starts later). */
    public static MapReportingService create(FileConfiguration config, Supplier<RealtimeService> realtime, Logger logger) {
        return create(config, realtime, null, logger);
    }

    /**
     * The service for this config with the live player report ({@code players} null: outlines only). The hub is read
     * through {@code realtime} at send time (it starts later).
     */
    public static MapReportingService create(FileConfiguration config, Supplier<RealtimeService> realtime,
                                             OnlinePlayerSnapshotPort players, Logger logger) {
        boolean enabled = config.getBoolean(ENABLED_KEY, false);
        if (!enabled) {
            logger.info("[KnK Map] Map reporting off (" + ENABLED_KEY + "=false)");
        } else if (!config.getBoolean("realtime.enabled", false)) {
            logger.warning("[KnK Map] " + ENABLED_KEY + " is on but realtime.enabled is off: region outlines can't be sent");
        } else {
            logger.info("[KnK Map] Map reporting on: region outlines" + (players == null ? "" : " and live players")
                    + " are sent to the API over the game hub");
        }
        MapReportSender sender = (method, argument) -> {
            RealtimeService service = realtime.get();
            return service != null && service.send(method, argument);
        };
        int intervalTicks = config.getInt(INTERVAL_KEY, MapReportingService.DEFAULT_INTERVAL_TICKS);
        return new MapReportingService(enabled, new RegionOutlineAdapter(MapReporting::loadedManagers), players,
                intervalTicks, sender, logger);
    }

    /**
     * Starts the live player report: every {@code interval-ticks} the snapshot on the main thread (timed), then the
     * send on an async task. Does nothing (returns false) unless {@link MapReportingService#playersEnabled}. Bukkit
     * cancels the task when the plugin is disabled.
     */
    public static boolean startPlayerReports(Plugin plugin, MapReportingService service) {
        if (service == null || !service.playersEnabled()) {
            return false;
        }
        long interval = service.intervalTicks();
        plugin.getServer().getScheduler().runTaskTimer(plugin, PluginMetrics.timed(PLAYER_SNAPSHOT_TASK, () -> {
            MapReportingService.PlayersReport report = service.collectPlayers();
            if (report != null) {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> service.sendPlayers(report));
            }
        }), interval, interval);
        plugin.getLogger().info("[KnK Map] Live player report every " + interval + " ticks");
        return true;
    }

    /** The domain regions a managed-region repair worked on, as the regions to report. */
    public static List<RegionRef> domainRegions(List<ManagedRegionSpec> specs) {
        return specs.stream().map(spec -> new RegionRef(spec.worldName(), spec.regionId())).toList();
    }

    /**
     * The domain sub-regions an access sync's rules list (PandiO/knk-workspace#750), as the regions to report: each in
     * its domain's world (null: whichever loaded world has it, as the sub-region mirror looks it up). A sub-region id
     * equal to the domain's main region is left out (the repair reports that one).
     */
    public static List<RegionRef> subRegions(Collection<DomainAccessRule> rules) {
        List<RegionRef> regions = new ArrayList<>();
        if (rules == null) {
            return regions;
        }
        for (DomainAccessRule rule : rules) {
            if (rule == null) {
                continue;
            }
            for (String subRegionId : rule.subRegionIds()) {
                if (rule.wgRegionId() != null && subRegionId.equalsIgnoreCase(rule.wgRegionId().trim())) {
                    continue;
                }
                regions.add(new RegionRef(rule.worldName(), subRegionId));
            }
        }
        return regions;
    }

    private static List<RegionManager> loadedManagers() {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().getLoaded();
    }
}
