package net.knightsandkings.knk.paper.roads;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadSeed;
import net.knightsandkings.knk.core.domain.roads.RoadSeedLocation;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileUpsertResult;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.ports.api.RoadNetworkCommandApi;
import net.knightsandkings.knk.core.ports.api.RoadNetworkQueryApi;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.roads.build.BuildWarning;
import net.knightsandkings.knk.core.roads.route.CoverageCheck;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.regions.RegionIds;
import net.knightsandkings.knk.paper.utils.TickBudget;
import net.kyori.adventure.text.Component;

/**
 * The ordered, resumable tile build queue behind {@code /knk road build here|tile|radius|dirty|all}
 * (DESIGN §9; plan Phase 3 "RoadBuildQueue"): one {@link RoadBuildJob} at a time, the next one started only
 * while the server is not lagging ({@link TickBudget}, R11), progress in the requesting admin's action bar,
 * a build summary in their chat per tile. The pending list is persisted ({@link BuildQueueState}) and
 * resumed after a restart, skipping tiles the API says were built after the queue started.
 *
 * <p>The tile builder runs on this queue's own single daemon thread rather than the api-client pool: a
 * multi-second CPU job would otherwise stall API callbacks (Phase 3 decision).
 */
public final class RoadBuildQueue {
    private static final Logger LOGGER = Logger.getLogger(RoadBuildQueue.class.getName());
    public static final long TICK_PERIOD = 20L;
    public static final int MAX_SUMMARY_ITEMS = 8;

    private final Plugin plugin;
    private final NavigationConfig config;
    private final RoadNetworkQueryApi queryApi;
    private final RoadNetworkCommandApi commandApi;
    private final RoadNetworkCache cache;
    private final GateManager gateManager;
    private final RegionIds regionIds;
    private final RegionDomainResolver regionResolver;
    private final Executor mainThread;
    private final TickBudget tickBudget;
    private final Path stateFile;
    private final BuildQueueState state;
    private ExecutorService buildThread;
    private BukkitTask ticker;
    private RoadBuildJob running;
    private int failures;

    public RoadBuildQueue(Plugin plugin, NavigationConfig config, RoadNetworkQueryApi queryApi, RoadNetworkCommandApi commandApi,
                          RoadNetworkCache cache, GateManager gateManager, RegionIds regionIds, RegionDomainResolver regionResolver,
                          Executor mainThread, TickBudget tickBudget) {
        this.plugin = plugin;
        this.config = Objects.requireNonNull(config, "config");
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi");
        this.commandApi = Objects.requireNonNull(commandApi, "commandApi");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.gateManager = gateManager;
        this.regionIds = regionIds;
        this.regionResolver = regionResolver;
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.tickBudget = tickBudget == null ? TickBudget.server() : tickBudget;
        this.stateFile = cache.files().root().resolve("build-queue.json");
        this.state = BuildQueueState.load(stateFile).orElseGet(BuildQueueState::new);
    }

    // ===== lifecycle =====

    /** Starts the ticker; a persisted queue is resumed once the API's tile list says what was built meanwhile. */
    public void start() {
        buildThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "knk-road-build");
            t.setDaemon(true);
            return t;
        });
        if (!state.isEmpty()) {
            Set<String> worlds = new HashSet<>();
            state.pending().forEach(t -> worlds.add(t.world()));
            List<CompletableFuture<List<RoadTile>>> lists = worlds.stream().map(queryApi::tiles).toList();
            CompletableFuture.allOf(lists.toArray(CompletableFuture[]::new)).whenComplete((v, ex) -> mainThread.execute(() -> {
                if (ex == null) {
                    List<RoadTile> all = new ArrayList<>();
                    lists.forEach(f -> all.addAll(f.join()));
                    int skipped = state.skipBuiltSince(all);
                    LOGGER.info("[Roads] Resuming the build queue \"" + state.label() + "\": " + state.pendingCount() + " tile(s) left, "
                        + skipped + " built before the restart");
                } else {
                    LOGGER.warning("[Roads] Resuming the build queue without the API's tile list: " + RoadMessages.describeError(ex));
                }
                persist();
                notifyRequester(RoadMessages.info("Resuming your road build \"" + state.label() + "\": " + state.pendingCount() + " tile(s) left."));
            }));
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
    }

    /** Stops the ticker and the build thread; the pending list stays on disk for the next start. */
    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        if (running != null) {
            running.cancel();
        }
        if (buildThread != null) {
            buildThread.shutdownNow();
            buildThread = null;
        }
        persist();
    }

    // ===== enqueue =====

    /** Queues tiles for {@code sender}; {@code label} names the request in messages ("radius 1500"). */
    public void enqueue(CommandSender sender, Collection<TileKey> tiles, String label) {
        if (tiles.isEmpty()) {
            sender.sendMessage(RoadMessages.warn("Nothing to build for " + label + "."));
            return;
        }
        UUID requester = sender instanceof Player p ? p.getUniqueId() : null;
        int added = state.enqueue(tiles, requester, label, OffsetDateTime.now(ZoneOffset.UTC));
        persist();
        sender.sendMessage(RoadMessages.good("Queued " + added + " tile(s) (" + label + "); " + state.pendingCount() + " waiting"
            + (running != null ? ", one building" : "") + ". Progress in your action bar; /knk road build status | cancel."));
        tick();
    }

    /** Every tile the API marks dirty in the world (after a fresh tile list). */
    public void enqueueDirty(CommandSender sender, String world) {
        cache.refreshTiles(world).whenComplete((v, ex) -> mainThread.execute(() -> {
            List<TileKey> dirty = cache.tiles(world).stream().filter(RoadTile::dirty).map(TileKey::of).toList();
            if (dirty.isEmpty()) {
                sender.sendMessage(RoadMessages.info("No dirty tiles in " + world + "."));
                return;
            }
            enqueue(sender, dirty, "dirty tiles of " + world);
        }));
    }

    /**
     * Every tile of the world that has something to start from: the API's tile rows, the tiles of every
     * admin/survey seed and the tiles of every Domain Location inside the world border (a tile with none of
     * these has no seed and would build nothing) - DESIGN §9 "recommended kingdom by kingdom".
     */
    public void enqueueAll(CommandSender sender, String world) {
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            sender.sendMessage(RoadMessages.bad("World '" + world + "' is not loaded."));
            return;
        }
        WorldBorder border = bukkitWorld.getWorldBorder();
        int half = (int) Math.min(30_000_000, border.getSize() / 2);
        int cx = border.getCenter().getBlockX();
        int cz = border.getCenter().getBlockZ();
        CompletableFuture<List<RoadTile>> tilesF = queryApi.tiles(world);
        CompletableFuture<List<RoadSeed>> seedsF = queryApi.seeds(world);
        CompletableFuture<List<RoadSeedLocation>> locationsF = config.seedFromDomains()
            ? queryApi.seedLocations(world, cx - half, cz - half, cx + half, cz + half)
            : CompletableFuture.completedFuture(List.of());
        CompletableFuture.allOf(tilesF, seedsF, locationsF).whenComplete((v, ex) -> mainThread.execute(() -> {
            if (RoadAdminCommand.failed(sender, "list the world's tiles and seeds", ex)) {
                return;
            }
            Set<TileKey> keys = new LinkedHashSet<>();
            tilesF.join().forEach(t -> keys.add(TileKey.of(t)));
            seedsF.join().forEach(s -> keys.add(TileKey.of(world, s.x(), s.z())));
            locationsF.join().forEach(l -> keys.add(TileKey.of(world, l.x(), l.z())));
            enqueue(sender, keys, "all of " + world);
        }));
    }

    /** The tiles within {@code radius} blocks of a block position (a square, tile-aligned). */
    public static List<TileKey> tilesWithin(String world, int blockX, int blockZ, int radius) {
        TileKey min = TileKey.of(world, blockX - radius, blockZ - radius);
        TileKey max = TileKey.of(world, blockX + radius, blockZ + radius);
        List<TileKey> keys = new ArrayList<>();
        // Nearest tiles first: order by Chebyshev distance to the centre tile.
        TileKey centre = TileKey.of(world, blockX, blockZ);
        for (int x = min.tileX(); x <= max.tileX(); x++) {
            for (int z = min.tileZ(); z <= max.tileZ(); z++) {
                keys.add(new TileKey(world, x, z));
            }
        }
        keys.sort((a, b) -> Integer.compare(a.distanceTo(centre), b.distanceTo(centre)));
        return keys;
    }

    // ===== status / cancel =====

    public void status(CommandSender sender) {
        sender.sendMessage(RoadMessages.prefixed(Component.text("Build queue: " + describe(), RoadMessages.HIGHLIGHT)));
        List<TileKey> pending = state.pending();
        if (!pending.isEmpty()) {
            sender.sendMessage(Component.text(" next: " + pending.subList(0, Math.min(6, pending.size()))
                + (pending.size() > 6 ? " …" : ""), RoadMessages.INFO));
        }
    }

    public void cancel(CommandSender sender) {
        int dropped = state.pendingCount();
        state.clear();
        persist();
        if (running != null) {
            running.cancel();
            sender.sendMessage(RoadMessages.info("Cancelled: the running tile stops at its next step, " + dropped + " queued tile(s) dropped."));
        } else {
            sender.sendMessage(RoadMessages.info("Cancelled: " + dropped + " queued tile(s) dropped."));
        }
    }

    /** One line for {@code /knk road status}. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (running != null) {
            text.append("building ").append(running.tile()).append("; ");
        }
        text.append(state.pendingCount()).append(" waiting");
        if (state.totalCount() > 0) {
            text.append(" (").append(state.doneCount()).append('/').append(state.totalCount()).append(" done of \"").append(state.label()).append("\")");
        }
        if (failures > 0) {
            text.append(", ").append(failures).append(" failed");
        }
        return text.toString();
    }

    public boolean isIdle() {
        return running == null && state.isEmpty();
    }

    // ===== ticking =====

    /** Main thread. Starts the next job when none runs and the server keeps up. */
    void tick() {
        if (running != null || state.isEmpty() || buildThread == null) {
            return;
        }
        if (tickBudget.isLagging()) {
            notifyRequesterBar("Road build paused: the server is lagging");
            return;
        }
        Optional<TileKey> next = state.next();
        if (next.isEmpty()) {
            return;
        }
        persist();
        TileKey key = next.get();
        running = new RoadBuildJob(plugin, config, queryApi, commandApi, cache, gateManager, regionIds, regionResolver,
            mainThread, buildThread, tickBudget, key, this::notifyRequesterBar, this::completed);
        LOGGER.info("[Roads] Building tile " + key + " (" + state.pendingCount() + " more queued)");
        running.start();
    }

    /** Main thread. */
    private void completed(RoadBuildJob.Outcome outcome) {
        running = null;
        state.completed();
        if (!outcome.success()) {
            failures++;
            LOGGER.warning("[Roads] Tile " + outcome.tile() + " not built: " + outcome.error());
            notifyRequester(RoadMessages.bad("Tile " + outcome.tile().tileX() + "," + outcome.tile().tileZ() + " not built: " + outcome.error()));
        } else {
            summary(outcome).forEach(this::notifyRequester);
        }
        if (state.isEmpty()) {
            notifyRequester(RoadMessages.good("Road build \"" + state.label() + "\" finished: " + state.doneCount() + " tile(s)"
                + (failures > 0 ? ", " + failures + " failed" : "") + ". /knk road show to look at it."));
            notifyRequesterBar("Road build finished");
            state.clear();
            failures = 0;
        }
        persist();
    }

    /** The per-tile build summary (DESIGN §7): counts, disappeared nodes, conflicts, warnings, coverage misses. */
    static List<Component> summary(RoadBuildJob.Outcome outcome) {
        List<Component> lines = new ArrayList<>();
        RoadTileUpsertResult up = outcome.upsert();
        TileKey tile = outcome.tile();
        lines.add(RoadMessages.prefixed(Component.text("Tile " + tile.tileX() + "," + tile.tileZ() + " built in " + (outcome.millis() / 1000) + " s: "
            + outcome.build().nodes().size() + " nodes, " + outcome.build().edges().size() + " edges, " + outcome.build().levelCount()
            + " level(s), " + outcome.spansExtracted() + " road cells from " + outcome.chunksCaptured() + " chunks", RoadMessages.HIGHLIGHT)));
        lines.add(Component.text(" nodes +" + up.nodesCreated() + " ~" + up.nodesUpdated() + " -" + up.nodesDeleted() + ", edges +" + up.edgesCreated()
            + " ~" + up.edgesUpdated() + " -" + up.edgesDeleted() + ", " + up.stitchEdges() + " stitched, " + up.labelledEdges() + " labelled, "
            + up.unlabelledEdges() + " unlabelled" + (up.bumpedTileIds().isEmpty() ? "" : ", neighbours refreshed: " + up.bumpedTileIds()), RoadMessages.INFO));
        if (!up.deletedNodes().isEmpty()) {
            Component line = Component.text(" disappeared nodes (" + up.deletedNodes().size() + "): ", RoadMessages.WARN);
            int n = 0;
            for (RoadNode node : up.deletedNodes()) {
                if (n++ >= MAX_SUMMARY_ITEMS) {
                    line = line.append(Component.text(" …", RoadMessages.INFO));
                    break;
                }
                line = line.append(RoadMessages.teleport(node.x(), node.y(), node.z())).append(Component.text(node.name() == null ? " " : " \"" + node.name() + "\" ", RoadMessages.INFO));
            }
            lines.add(line);
        }
        for (String conflict : up.conflicts().subList(0, Math.min(MAX_SUMMARY_ITEMS, up.conflicts().size()))) {
            lines.add(Component.text(" street conflict: " + conflict, RoadMessages.WARN));
        }
        List<BuildWarning> warnings = outcome.build().warnings();
        int shown = 0;
        for (BuildWarning warning : warnings) {
            if (shown++ >= MAX_SUMMARY_ITEMS) {
                lines.add(Component.text(" … " + (warnings.size() - MAX_SUMMARY_ITEMS) + " more warning(s) in the tile overview", RoadMessages.INFO));
                break;
            }
            lines.add(Component.text(" " + warning.message() + " ", RoadMessages.WARN).append(RoadMessages.teleport(warning.x(), warning.y(), warning.z())));
        }
        if (!outcome.coverageMisses().isEmpty()) {
            Component line = Component.text(" survey coverage: " + outcome.coverageMisses().size() + " walked point(s) got no road - a material missing from the profiles, or a stretch to record: ", RoadMessages.WARN);
            int n = 0;
            for (CoverageCheck.Miss miss : outcome.coverageMisses()) {
                if (n++ >= MAX_SUMMARY_ITEMS) {
                    line = line.append(Component.text(" …", RoadMessages.INFO));
                    break;
                }
                line = line.append(RoadMessages.teleport(miss.x(), miss.y(), miss.z())).append(Component.text(" "));
            }
            lines.add(line);
        }
        return lines;
    }

    // ===== requester =====

    private void notifyRequester(Component message) {
        Optional<UUID> requester = state.requester();
        if (requester.isPresent()) {
            Player player = Bukkit.getPlayer(requester.get());
            if (player != null) {
                player.sendMessage(message);
                return;
            }
        }
        LOGGER.info("[Roads] " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message));
    }

    private void notifyRequesterBar(String text) {
        state.requester().map(Bukkit::getPlayer).ifPresent(player -> player.sendActionBar(Component.text(text, RoadMessages.INFO)));
    }

    private void persist() {
        try {
            state.save(stateFile);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Roads] Could not persist the build queue: " + e.getMessage());
        }
    }
}
