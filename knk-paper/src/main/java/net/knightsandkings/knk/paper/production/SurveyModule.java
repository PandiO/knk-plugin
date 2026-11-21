package net.knightsandkings.knk.paper.production;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.metrics.PluginMetrics;
import net.knightsandkings.knk.core.ports.api.ProductionSurveyApi;
import net.knightsandkings.knk.core.production.survey.AreaShape;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.core.production.survey.NotableChangeTracker;
import net.knightsandkings.knk.core.production.survey.PlaceholderOverlay;
import net.knightsandkings.knk.core.production.survey.SurveyDue;
import net.knightsandkings.knk.core.production.survey.SurveySettings;

/**
 * Yield-area surveys on the game server (PandiO/knk-workspace#679, PRODUCTION_KINDS § 3.2, A10), only with
 * {@code production.enabled: true}:
 * <ol>
 *   <li>reads the API's survey queue every {@code poll-minutes} (requested areas first, then never surveyed, then a day
 *       old; Configured structures never appear, G5) and on {@code /knk production survey};</li>
 *   <li>runs each survey through {@link SurveyRunner}: snapshots on the main thread within {@code chunks-per-tick}
 *       (timed as {@code production.survey.capture}), counting and reporting off it;</li>
 *   <li>counts block breaks and places in the known areas and asks the API for a {@code NotableChange} survey of an area
 *       after {@code notable-change-blocks} of them;</li>
 *   <li>keeps the list of areas for {@code /knk production area show}.</li>
 * </ol>
 */
public final class SurveyModule implements Listener {

    private final Plugin plugin;
    private final SurveySettings settings;
    private final ProductionSurveyApi api;
    private final SurveyRegionShapes regions = new SurveyRegionShapes();
    private final ExecutorService counting;
    private final SurveyRunner runner;
    private final NotableChangeTracker notable;
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile List<AreaSpec> areas = List.of();
    private volatile String lastPoll = "never";
    private final List<BukkitTask> tasks = new ArrayList<>();

    public SurveyModule(Plugin plugin, SurveySettings settings, ProductionSurveyApi api) {
        this(plugin, settings, api, PlaceholderOverlay.NONE);
    }

    /**
     * @param placeholders blocks waiting to regenerate (player harvesting, PandiO/knk-workspace#134): surveys count them as
     *                     their original
     */
    public SurveyModule(Plugin plugin, SurveySettings settings, ProductionSurveyApi api, PlaceholderOverlay placeholders) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.counting = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "KnK-survey");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        this.runner = new SurveyRunner(settings, api, name -> {
            World world = Bukkit.getWorld(name);
            return world == null ? null : SurveyWorld.of(world);
        }, regions, placeholders == null ? PlaceholderOverlay.NONE : placeholders, counting, this::onMain, plugin.getLogger(), Clock.systemUTC());
        this.notable = new NotableChangeTracker(settings.notableChangeBlocks(),
                TimeUnit.MINUTES.toMillis(settings.notableCooldownMinutes()));
        runner.addListener(outcome -> notable.surveyed(outcome.due().area().areaId()));
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, PluginMetrics.timed("production.survey.capture", runner::tick), 1, 1));
        if (settings.pollMinutes() > 0) {
            long period = TimeUnit.MINUTES.toSeconds(settings.pollMinutes()) * 20;
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> poll("schedule"), 30 * 20L, period));
        }
        long areasPeriod = TimeUnit.MINUTES.toSeconds(Math.max(1, settings.areasRefreshMinutes())) * 20;
        if (settings.areasRefreshMinutes() > 0 || notable.enabled()) {
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAreas, 20 * 20L, areasPeriod));
        }
        plugin.getLogger().info("Yield-area surveys are on (production.enabled): queue every " + settings.pollMinutes()
                + " min, " + settings.chunksPerTick() + " chunks per tick, notable change after "
                + settings.notableChangeBlocks() + " blocks.");
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        runner.clear();
        counting.shutdownNow();
    }

    public SurveyRunner runner() {
        return runner;
    }

    public SurveySettings settings() {
        return settings;
    }

    public ProductionSurveyApi api() {
        return api;
    }

    public String lastPoll() {
        return lastPoll;
    }

    public NotableChangeTracker notableTracker() {
        return notable;
    }

    /** The areas last read from the API. */
    public List<AreaSpec> areas() {
        return areas;
    }

    /** Main thread: an area's volume (a region is read from WorldGuard), or the reason it has none. */
    public AreaShape shapeOf(AreaSpec area) {
        World world = area.worldName() == null ? null : Bukkit.getWorld(area.worldName());
        int minY = world == null ? -64 : world.getMinHeight();
        int maxY = world == null ? 320 : world.getMaxHeight();
        return area.resolve(regions, minY, maxY);
    }

    /** Reads the queue now (unless a read is running) and queues what is due. */
    public CompletableFuture<Integer> poll(String why) {
        if (!polling.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0);
        }
        CompletableFuture<Integer> queued = new CompletableFuture<>();
        api.surveyDue(settings.batch()).whenComplete((due, error) -> onMain(() -> {
            polling.set(false);
            if (error != null) {
                lastPoll = "failed (" + why + "): " + SurveyRunner.describe(error);
                plugin.getLogger().warning("Survey queue read failed: " + SurveyRunner.describe(error));
                queued.complete(0);
                return;
            }
            int added = 0;
            for (SurveyDue item : due) {
                if (runner.enqueue(item)) {
                    added++;
                }
            }
            lastPoll = java.time.Instant.now() + " (" + why + "): " + due.size() + " due, " + added + " queued";
            queued.complete(added);
        }));
        return queued;
    }

    /** Re-reads the area list (for area show and the notable-change counters). */
    public CompletableFuture<List<AreaSpec>> refreshAreas() {
        return api.areas().whenComplete((list, error) -> onMain(() -> {
            if (error != null) {
                plugin.getLogger().fine("Production area list read failed: " + SurveyRunner.describe(error));
                return;
            }
            areas = List.copyOf(list);
            List<NotableChangeTracker.TrackedArea> tracked = new ArrayList<>();
            for (AreaSpec area : list) {
                if (!area.enabled()) {
                    continue;
                }
                try {
                    tracked.add(new NotableChangeTracker.TrackedArea(area.areaId(), area.structureId(), area.worldName(),
                            shapeOf(area)));
                } catch (IllegalArgumentException ignored) {
                    // a region that doesn't exist: nothing to count
                }
            }
            notable.setAreas(tracked, settings.maxAreaChunks());
        }));
    }

    /**
     * "Survey now" for a structure: asks the API to put its areas at the head of the queue, then reads the queue.
     * {@code onArea} hears each of those areas' outcomes.
     */
    public CompletableFuture<List<AreaSpec>> surveyNow(int structureId, Integer areaId, Consumer<SurveyRunner.Outcome> onArea) {
        return api.requestSurvey(structureId, areaId).thenApply(requested -> {
            if (onArea != null && !requested.isEmpty()) {
                java.util.Set<Integer> ids = new java.util.HashSet<>();
                requested.forEach(area -> ids.add(area.areaId()));
                runner.addListener(new Consumer<>() {
                    @Override
                    public void accept(SurveyRunner.Outcome outcome) {
                        if (ids.remove(outcome.due().area().areaId())) {
                            onArea.accept(outcome);
                        }
                        if (ids.isEmpty()) {
                            runner.removeListener(this);
                        }
                    }
                });
            }
            onMain(() -> poll("survey now"));
            return requested;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        changed(event.getBlockPlaced());
    }

    private void changed(Block block) {
        if (!notable.enabled()) {
            return;
        }
        for (NotableChangeTracker.TrackedArea area : notable.record(block.getWorld().getName(), block.getX(), block.getY(),
                block.getZ(), System.currentTimeMillis())) {
            runner.markNotable(area.areaId());
            api.requestSurvey(area.structureId(), area.areaId()).whenComplete((requested, error) -> onMain(() -> {
                if (error != null) {
                    runner.unmarkNotable(area.areaId());
                    plugin.getLogger().info("Notable change in production area #" + area.areaId() + ": survey not requested ("
                            + SurveyRunner.describe(error) + ")");
                } else {
                    poll("notable change in area #" + area.areaId());
                }
            }));
        }
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
