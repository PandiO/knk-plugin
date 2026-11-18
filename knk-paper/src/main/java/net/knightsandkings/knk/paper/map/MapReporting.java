package net.knightsandkings.knk.paper.map;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;

import net.knightsandkings.knk.core.map.MapReportSender;
import net.knightsandkings.knk.core.map.MapReportingService;
import net.knightsandkings.knk.core.map.RegionRef;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionSpec;
import net.knightsandkings.knk.paper.realtime.RealtimeService;

/**
 * Wires the overview map's plugin reports (PandiO/knk-workspace#735, OVERVIEW_MAP_DESIGN § 7.2, § 7.3): the
 * {@link MapReportingService} with the WorldGuard {@link RegionOutlineAdapter} and the game hub as sender. Off unless
 * {@code map.reporting.enabled: true} (O27); off, nothing is hooked in and the plugin behaves as before. Reports travel
 * over the realtime channel, so they also need {@code realtime.enabled: true}.
 */
public final class MapReporting {

    public static final String ENABLED_KEY = "map.reporting.enabled";

    private MapReporting() {
    }

    /** The service for this config; the hub is read through {@code realtime} at send time (it starts later). */
    public static MapReportingService create(FileConfiguration config, Supplier<RealtimeService> realtime, Logger logger) {
        boolean enabled = config.getBoolean(ENABLED_KEY, false);
        if (!enabled) {
            logger.info("[KnK Map] Map reporting off (" + ENABLED_KEY + "=false)");
        } else if (!config.getBoolean("realtime.enabled", false)) {
            logger.warning("[KnK Map] " + ENABLED_KEY + " is on but realtime.enabled is off: region outlines can't be sent");
        } else {
            logger.info("[KnK Map] Map reporting on: region outlines are sent to the API over the game hub");
        }
        MapReportSender sender = (method, argument) -> {
            RealtimeService service = realtime.get();
            return service != null && service.send(method, argument);
        };
        return new MapReportingService(enabled, new RegionOutlineAdapter(MapReporting::loadedManagers), sender, logger);
    }

    /** The domain regions a managed-region repair worked on, as the regions to report. */
    public static List<RegionRef> domainRegions(List<ManagedRegionSpec> specs) {
        return specs.stream().map(spec -> new RegionRef(spec.worldName(), spec.regionId())).toList();
    }

    private static List<RegionManager> loadedManagers() {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().getLoaded();
    }
}
