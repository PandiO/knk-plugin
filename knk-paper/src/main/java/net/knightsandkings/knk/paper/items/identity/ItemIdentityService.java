package net.knightsandkings.knk.paper.items.identity;

import net.knightsandkings.knk.core.items.identity.ItemConversionLog;
import net.knightsandkings.knk.core.items.identity.ItemIdentityFacts;
import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution;
import net.knightsandkings.knk.core.items.identity.ItemIdentityResolver;
import net.knightsandkings.knk.core.items.identity.MaterialBlueprintMap;
import net.knightsandkings.knk.core.ports.api.ItemIdentityApi;
import net.knightsandkings.knk.paper.mapper.BlueprintTag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Item identity on the game server (PandiO/knk-workspace#141, production DESIGN §8): keeps knk-web-api's material →
 * blueprint map in memory (fetched at start and every {@code map-refresh-minutes}, never per event), resolves items
 * with {@link ItemIdentityResolver}, normalises stale blueprint tags (the retag rule), and keeps the conversion log,
 * sent to the API and summarised in the server log every {@code report-interval-minutes}.
 * <p>
 * {@link #resolve} is the one resolver every feature uses (stock deposits from P1 on). {@link #convert} is what the
 * conversion hooks ({@link ItemConversionListener}) call.
 */
public final class ItemIdentityService {

    private final Plugin plugin;
    private final ItemIdentityApi api;
    private final ItemIdentitySettings settings;
    private final Logger logger;
    private final ItemConversionLog conversionLog = new ItemConversionLog();
    private volatile MaterialBlueprintMap map = MaterialBlueprintMap.EMPTY;
    private volatile boolean mapLoaded;
    private BukkitTask refreshTask;
    private BukkitTask reportTask;

    public ItemIdentityService(Plugin plugin, ItemIdentityApi api, ItemIdentitySettings settings, Logger logger) {
        this.plugin = plugin;
        this.api = Objects.requireNonNull(api, "api");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = logger != null ? logger : Logger.getLogger(ItemIdentityService.class.getName());
    }

    /** Turns stamping on and schedules the map refresh and the report. Nothing when {@code enabled} is off. */
    public void start() {
        if (!settings.enabled()) {
            BlueprintTag.setStampingEnabled(false);
            return;
        }
        BlueprintTag.setStampingEnabled(true);
        long refreshTicks = TimeUnit.MINUTES.toSeconds(settings.mapRefreshMinutes()) * 20L;
        long reportTicks = TimeUnit.MINUTES.toSeconds(settings.reportIntervalMinutes()) * 20L;
        refreshTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::refreshMap, 0L, refreshTicks);
        reportTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::report, reportTicks, reportTicks);
        logger.info("[ItemIdentity] enabled; conversion hooks " + (settings.convertOnObtain() ? "on" : "off"));
    }

    /** Stops the tasks, sends what is left of the log (bounded wait) and turns stamping off. */
    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (reportTask != null) {
            reportTask.cancel();
            reportTask = null;
        }
        if (settings.enabled()) {
            try {
                reportAsync().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.log(Level.FINE, "[ItemIdentity] final report not sent", e);
            }
        }
        BlueprintTag.setStampingEnabled(false);
    }

    public ItemIdentitySettings settings() {
        return settings;
    }

    public MaterialBlueprintMap map() {
        return map;
    }

    /** Whether the map has been fetched at least once (until then nothing is counted: every item would read "no match"). */
    public boolean mapLoaded() {
        return mapLoaded;
    }

    /** Replaces the map (the refresh task; tests). */
    public void replaceMap(MaterialBlueprintMap next) {
        map = next != null ? next : MaterialBlueprintMap.EMPTY;
        mapLoaded = true;
    }

    /** Fetches the map; a failure keeps the previous one. */
    public CompletableFuture<Void> refreshMap() {
        return api.fetchMaterialMap()
                .thenAccept(next -> {
                    int before = map.size();
                    replaceMap(next);
                    if (before != map.size()) {
                        logger.info("[ItemIdentity] material map: " + map.size() + " materials with a blueprint");
                    }
                })
                .exceptionally(error -> {
                    logger.warning("[ItemIdentity] material map refresh failed, keeping the previous one ("
                            + map.size() + " materials): " + error.getMessage());
                    return null;
                });
    }

    /** The item's identity; never changes the item. */
    public ItemIdentityResolution resolve(ItemStack item) {
        return ItemIdentityResolver.resolve(ItemIdentityFactsReader.read(item), map);
    }

    /**
     * The conversion hook's step: resolves the item, applies the retag rule to it (removes a stale blueprint tag, in
     * place) and counts it. Returns true when the item was changed, so the caller writes it back where its API hands
     * out copies. Unresolvable items are left alone and logged once per reason and material.
     */
    public boolean convert(ItemStack item, ItemConversionLog.Source source) {
        ItemIdentityFacts facts = ItemIdentityFactsReader.read(item);
        if (facts == null) {
            return false;
        }
        ItemIdentityResolution resolution = ItemIdentityResolver.resolve(facts, map);
        boolean changed = resolution.stripBlueprintTag() && BlueprintTag.remove(item);
        if (mapLoaded) {
            conversionLog.record(source, resolution, facts.materialKey(), item.getAmount());
            if (conversionLog.firstUnresolved(resolution, facts.materialKey())) {
                logger.info("[ItemIdentity] left alone (" + resolution.outcome() + "): " + facts.materialKey()
                        + " from " + source.wire() + "; logged once per reason and material");
            }
        }
        return changed;
    }

    /** The retag rule without counting (a crafting preview, which may never be taken). True when the item changed. */
    public boolean convertWithoutCounting(ItemStack item) {
        ItemIdentityFacts facts = ItemIdentityFactsReader.read(item);
        return facts != null && ItemIdentityResolver.resolve(facts, map).stripBlueprintTag() && BlueprintTag.remove(item);
    }

    /** Sends the counts since the last report; on failure they go back into the log. */
    public void report() {
        reportAsync();
    }

    CompletableFuture<Void> reportAsync() {
        List<ItemConversionLog.Count> lines = conversionLog.drain();
        if (lines.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        logger.info("[ItemIdentity] conversions since the last report: " + ItemConversionLog.summary(lines));
        return api.reportConversionCounts(lines).exceptionally(error -> {
            conversionLog.restore(lines);
            logger.warning("[ItemIdentity] could not send conversion counts, kept for the next report: " + error.getMessage());
            return null;
        });
    }

    /** The log (tests). */
    ItemConversionLog conversionLog() {
        return conversionLog;
    }
}
