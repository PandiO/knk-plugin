package net.knightsandkings.knk.paper.territory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;

import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.structures.StructureSummary;
import net.knightsandkings.knk.core.map.MapReportSender;
import net.knightsandkings.knk.core.map.RegionOutlinePort;
import net.knightsandkings.knk.core.ports.api.StructuresQueryApi;
import net.knightsandkings.knk.core.ports.api.TerritoryApi;
import net.knightsandkings.knk.core.territory.TerritoryService;
import net.knightsandkings.knk.core.territory.TerritorySettings;
import net.knightsandkings.knk.core.territory.TerritoryTracker;
import net.knightsandkings.knk.paper.map.RegionOutlineAdapter;

/**
 * Wires territory on Paper (PandiO/knk-workspace#689; territory DESIGN § 1-4): the {@link TerritoryService} with the
 * WorldGuard outlines ({@link RegionOutlineAdapter}, as the overview map reads them), the {@link TerritoryListener}, the
 * first build a few seconds after start (WorldGuard has loaded its regions by then), a periodic check every
 * {@code production.territory.refresh-minutes}, and a debounced refresh on the {@code TerritoryChanged} realtime event.
 * Built only with {@code production.enabled}; without it nothing of territory runs (A21).
 */
public final class TerritoryModule {

    /** Ticks after start before the first build (the regions of every world are loaded by then). */
    static final long FIRST_BUILD_DELAY_TICKS = 100L;

    /** Ticks a burst of {@code TerritoryChanged} events is merged over (the API debounces too). */
    static final long EVENT_DEBOUNCE_TICKS = 40L;

    static final int STRUCTURE_PAGE_SIZE = 200;
    static final int MAX_STRUCTURE_PAGES = 500;

    private final Plugin plugin;
    private final TerritorySettings settings;
    private final TerritoryService service;
    private final TerritoryTracker tracker;
    private final TerritoryListener listener;
    private final Logger logger;
    private BukkitTask periodic;
    private BukkitTask pendingEvent;

    public TerritoryModule(Plugin plugin, TerritorySettings settings, TerritoryApi api, StructuresQueryApi structures,
                           MapReportSender outlineSender) {
        this(plugin, settings, api, new RegionOutlineAdapter(TerritoryModule::loadedManagers), structures, outlineSender);
    }

    /** With an outline port of the caller's (scenarios use the live one too, but read it through their own scene). */
    public TerritoryModule(Plugin plugin, TerritorySettings settings, TerritoryApi api, RegionOutlinePort outlines,
                           StructuresQueryApi structures, MapReportSender outlineSender) {
        this.plugin = plugin;
        this.settings = settings;
        this.logger = plugin.getLogger();
        Executor main = runnable -> {
            if (Bukkit.isPrimaryThread()) {
                runnable.run();
            } else if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, runnable);
            }
        };
        Executor async = runnable -> {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
            } else {
                runnable.run();
            }
        };
        this.service = new TerritoryService(settings, api, outlines,
                structures == null ? null : structureSource(structures), outlineSender, main, async, logger);
        this.tracker = new TerritoryTracker(service::cache);
        this.listener = new TerritoryListener(tracker, settings, Bukkit.getPluginManager());
        // a rebuild re-checks every online player where they stand, so a redrawn border reaches standing players too
        service.addListener(cache -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                listener.at(player, player.getLocation());
            }
        });
    }

    /** Registers the listener and schedules the first build and the periodic check. */
    public void start() {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, () -> service.refresh("startup", false), FIRST_BUILD_DELAY_TICKS);
        if (settings.refreshMinutes() > 0) {
            long period = settings.refreshMinutes() * 60L * 20L;
            periodic = Bukkit.getScheduler().runTaskTimer(plugin, () -> service.refresh("periodic check", false),
                    FIRST_BUILD_DELAY_TICKS + period, period);
        }
        logger.info("[KnK Territory] On (production.enabled): first build in " + FIRST_BUILD_DELAY_TICKS / 20
                + " s, checks every " + settings.refreshMinutes() + " min, report " + (settings.report() ? "on" : "off")
                + ", map outlines " + (settings.mapOutlines() ? "on" : "off"));
    }

    public void stop() {
        if (periodic != null) {
            periodic.cancel();
            periodic = null;
        }
        if (pendingEvent != null) {
            pendingEvent.cancel();
            pendingEvent = null;
        }
        org.bukkit.event.HandlerList.unregisterAll(listener);
        tracker.clear();
    }

    /** {@code TerritoryChanged} (any thread): one refresh {@value #EVENT_DEBOUNCE_TICKS} ticks after the last event. */
    public void onTerritoryChanged() {
        if (!plugin.isEnabled()) {
            return;
        }
        Runnable schedule = () -> {
            if (pendingEvent != null) {
                pendingEvent.cancel();
            }
            pendingEvent = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pendingEvent = null;
                service.refresh("TerritoryChanged", false);
            }, EVENT_DEBOUNCE_TICKS);
        };
        if (Bukkit.isPrimaryThread()) {
            schedule.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, schedule);
        }
    }

    public TerritoryService service() {
        return service;
    }

    public TerritoryTracker tracker() {
        return tracker;
    }

    public TerritoryListener listener() {
        return listener;
    }

    /** Every structure with a region, read page by page. */
    static TerritoryService.StructureSource structureSource(StructuresQueryApi structures) {
        return () -> fetchPage(structures, 1, new ArrayList<>());
    }

    private static CompletableFuture<List<TerritoryService.StructureRef>> fetchPage(StructuresQueryApi api, int page,
                                                                                   List<TerritoryService.StructureRef> collected) {
        return api.search(new PagedQuery(page, STRUCTURE_PAGE_SIZE, null, null, false, Map.of())).thenCompose(result -> {
            List<StructureSummary> items = result == null || result.items() == null ? List.of() : result.items();
            for (StructureSummary structure : items) {
                if (structure != null && structure.id() != null && structure.wgRegionId() != null
                        && !structure.wgRegionId().isBlank()) {
                    // P2 (#677): townId from the search, so rural structures (no district) are placed too
                    collected.add(new TerritoryService.StructureRef(structure.id(), structure.districtId(),
                            structure.townId(), structure.worldName(), structure.wgRegionId()));
                }
            }
            int total = result == null ? 0 : result.totalCount();
            if (items.isEmpty() || page * STRUCTURE_PAGE_SIZE >= total || page >= MAX_STRUCTURE_PAGES) {
                return CompletableFuture.completedFuture(collected);
            }
            return fetchPage(api, page + 1, collected);
        });
    }

    static List<RegionManager> loadedManagers() {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().getLoaded();
    }
}
