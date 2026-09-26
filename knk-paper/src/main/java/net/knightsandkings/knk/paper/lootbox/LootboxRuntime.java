package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.ActiveLootboxCache;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The plugin's live lootbox state (docs/specs/lootboxes/DESIGN.md §3.4): the runtime config and the active boxes read
 * from the API every {@code runtime-refresh-seconds}, the {@link ActiveLootboxCache} and the {@link LootboxPresenter}
 * that shows them. Local spawns, claims and despawns update the cache straight away; the periodic read catches boxes
 * other servers or the web app changed. Expired boxes are taken down locally at their {@code expiresAt}.
 * <p>
 * The API calls run on the api-client's executor; everything touching the cache, the presenter or the world runs on
 * the main thread.
 */
public final class LootboxRuntime {

    private static final Logger LOGGER = Logger.getLogger(LootboxRuntime.class.getName());

    private final Plugin plugin;
    private final LootboxesQueryApi queryApi;
    private final Supplier<ConfigurationSection> configSection;
    private final Clock clock;
    private final ActiveLootboxCache cache = new ActiveLootboxCache();
    private final LootboxPresenter presenter;

    private record Snapshot(KnkLootboxRuntimeConfig config, List<KnkLootboxSpawn> active) {
    }

    private volatile LootboxSettings settings;
    private volatile KnkLootboxRuntimeConfig config = KnkLootboxRuntimeConfig.empty();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean refreshing;

    public LootboxRuntime(Plugin plugin, LootboxesQueryApi queryApi, Supplier<ConfigurationSection> configSection, Clock clock) {
        this.plugin = plugin;
        this.queryApi = queryApi;
        this.configSection = configSection;
        this.clock = clock;
        this.settings = LootboxSettings.from(configSection.get());
        this.presenter = new LootboxPresenter(() -> this.settings);
    }

    public void start() {
        stop();
        long refreshTicks = settings.runtimeRefreshSeconds() * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> refresh(), 20L, refreshTicks));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(), 20L, 20L));
    }

    public void stop() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        presenter.removeAll();
    }

    /** {@code /knk lootbox reload}: re-reads config.yml's section and restarts the timers (which refresh at once). */
    public void reloadSettings() {
        settings = LootboxSettings.from(configSection.get());
        start();
    }

    public LootboxSettings settings() {
        return settings;
    }

    public KnkLootboxRuntimeConfig config() {
        return config;
    }

    public ActiveLootboxCache cache() {
        return cache;
    }

    public LootboxPresenter presenter() {
        return presenter;
    }

    public Clock clock() {
        return clock;
    }

    /**
     * Re-reads the runtime config and the active boxes (main thread; the calls run async). The returned future
     * completes on the main thread once both are applied, or exceptionally when a read failed (the last known state
     * is kept then).
     */
    public CompletableFuture<Void> refresh() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (!settings.enabled()) {
            config = KnkLootboxRuntimeConfig.empty();
            presenter.removeAll();
            cache.replaceAll(List.of());
            done.complete(null);
            return done;
        }
        if (refreshing) {
            done.complete(null);
            return done;
        }
        refreshing = true;
        queryApi.getRuntimeConfig()
                .thenCombine(queryApi.getActive(), Snapshot::new)
                .whenComplete((result, ex) -> runOnMainThread(() -> {
                    refreshing = false;
                    if (ex != null) {
                        LOGGER.log(Level.WARNING, "Lootbox refresh failed; keeping the last known boxes: "
                                + LootboxRejectedException.unwrap(ex).getMessage());
                        done.completeExceptionally(ex);
                        return;
                    }
                    config = result.config();
                    applyActive(result.active());
                    done.complete(null);
                }));
        return done;
    }

    /** Main thread: replaces the cached boxes, removes the ones that went away and shows the loaded ones. */
    void applyActive(List<KnkLootboxSpawn> active) {
        List<KnkLootboxSpawn> live = active.stream().filter(s -> !s.isExpired(clock.instant())).toList();
        for (KnkLootboxSpawn gone : cache.replaceAll(live)) {
            presenter.remove(gone.id());
        }
        renderLoaded();
    }

    /** Main thread: a box this server just spawned. */
    public void added(KnkLootboxSpawn spawn) {
        if (spawn == null) {
            return;
        }
        cache.put(spawn);
        render(spawn);
    }

    /** Main thread: a box that was claimed, despawned or expired. */
    public void gone(int spawnId) {
        cache.remove(spawnId);
        presenter.remove(spawnId);
    }

    /** Main thread: shows every cached box in a loaded chunk. */
    public void renderLoaded() {
        for (KnkLootboxSpawn spawn : cache.all()) {
            render(spawn);
        }
    }

    /** Main thread: shows the cached boxes in one chunk that just loaded. */
    public void renderChunk(World world, int chunkX, int chunkZ) {
        for (KnkLootboxSpawn spawn : cache.inChunk(world.getName(), chunkX, chunkZ)) {
            render(spawn);
        }
    }

    private void render(KnkLootboxSpawn spawn) {
        try {
            presenter.render(spawn, config.typeById(spawn.lootboxTypeId()).orElse(null));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not show lootbox " + spawn.id(), e);
        }
    }

    /** Every second: expiry, then the presenter's spin and ambience. */
    private void tick() {
        for (KnkLootboxSpawn expired : cache.removeExpired(clock.instant())) {
            presenter.remove(expired.id());
        }
        presenter.tick();
    }

    private void runOnMainThread(Runnable runnable) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }
}
