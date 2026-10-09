package net.knightsandkings.knk.paper.roads;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.api.mapper.RoadMapper;
import net.knightsandkings.knk.core.domain.common.Conditional;
import net.knightsandkings.knk.core.domain.roads.RoadNetworkMeta;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.ports.api.RoadNetworkQueryApi;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * The plugin's copy of the road network (DESIGN §3.8, §9; plan Phase 3 "RoadNetworkCache"): per world a
 * {@link RoadNetworkSnapshot} built from the downloaded tile graphs plus the network meta (profiles,
 * street names), swapped atomically.
 * <ul>
 *   <li><b>Tiles:</b> {@code tiles(world)} → for every built tile a conditional {@code tileGraph} with the
 *       cached ETag (R17): a 304 keeps the file in {@code plugins/KnightsAndKings/roads/<world>/<x>_<z>.json},
 *       a 200 replaces it. Refreshed on start, after builds ({@link #invalidateTile}) and every 10 minutes.</li>
 *   <li><b>Meta:</b> {@code meta(world)} every 60 s - a few KB, and how a street renamed in the web app
 *       reaches navigation messages within a minute.</li>
 *   <li>{@link #reload} ({@code /knk road reload}) forces both.</li>
 * </ul>
 * Downloads and the snapshot rebuild run on the api-client executor (every API future completes
 * there); listeners are told on the main thread. The snapshot's node/geometry y is the <b>floor</b>
 * block (Phase 2c decision 1).
 */
public final class RoadNetworkCache {
    private static final Logger LOGGER = Logger.getLogger(RoadNetworkCache.class.getName());
    public static final long TILE_REFRESH_TICKS = 10 * 60 * 20L;
    public static final long META_REFRESH_TICKS = 60 * 20L;

    /** Per-world state: the graphs by tile and the last meta. */
    private static final class WorldState {
        final Map<TileKey, RoadTileGraph> graphs = new ConcurrentHashMap<>();
        volatile RoadNetworkMeta meta;
        volatile RoadNetworkSnapshot snapshot;
        volatile List<RoadTile> tiles = List.of();
        final AtomicBoolean refreshing = new AtomicBoolean();

        WorldState(String world) {
            this.snapshot = RoadNetworkSnapshot.empty(world);
        }
    }

    private final Plugin plugin;
    private final RoadNetworkQueryApi queryApi;
    private final RoadTileCache files;
    private final RegionDomainResolver regionResolver;
    private final Executor mainThread;
    private final Supplier<Collection<String>> worldNames;
    private final Map<String, WorldState> worlds = new ConcurrentHashMap<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private BukkitTask tileTimer;
    private BukkitTask metaTimer;

    public RoadNetworkCache(Plugin plugin, RoadNetworkQueryApi queryApi, Path roadsDirectory,
                            RegionDomainResolver regionResolver, Executor mainThread) {
        this(plugin, queryApi, new RoadTileCache(roadsDirectory), regionResolver, mainThread,
            () -> Bukkit.getWorlds().stream().map(World::getName).toList());
    }

    RoadNetworkCache(Plugin plugin, RoadNetworkQueryApi queryApi, RoadTileCache files,
                     RegionDomainResolver regionResolver, Executor mainThread, Supplier<Collection<String>> worldNames) {
        this.plugin = plugin;
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi");
        this.files = Objects.requireNonNull(files, "files");
        this.regionResolver = regionResolver;
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.worldNames = Objects.requireNonNull(worldNames, "worldNames");
    }

    // ===== lifecycle =====

    /** Refreshes every loaded world now and starts the two timers. */
    public void start() {
        refreshAll();
        tileTimer = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAllTiles, TILE_REFRESH_TICKS, TILE_REFRESH_TICKS);
        metaTimer = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAllMeta, META_REFRESH_TICKS, META_REFRESH_TICKS);
    }

    public void stop() {
        if (tileTimer != null) {
            tileTimer.cancel();
            tileTimer = null;
        }
        if (metaTimer != null) {
            metaTimer.cancel();
            metaTimer = null;
        }
    }

    /** Runs (main thread) with the world name after its snapshot was swapped. */
    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    // ===== reads =====

    /** The current snapshot of a world; empty (never null) for a world without roads. */
    public RoadNetworkSnapshot snapshot(String world) {
        WorldState state = worlds.get(world);
        return state == null ? RoadNetworkSnapshot.empty(world) : state.snapshot;
    }

    /** The last downloaded tile list of a world (every tile row the API knows, built or not). */
    public List<RoadTile> tiles(String world) {
        WorldState state = worlds.get(world);
        return state == null ? List.of() : state.tiles;
    }

    public Optional<RoadTileGraph> tileGraph(TileKey key) {
        WorldState state = worlds.get(key.world());
        return state == null ? Optional.empty() : Optional.ofNullable(state.graphs.get(key));
    }

    /** The last downloaded meta of a world, if any. */
    public Optional<RoadNetworkMeta> meta(String world) {
        WorldState state = worlds.get(world);
        return state == null ? Optional.empty() : Optional.ofNullable(state.meta);
    }

    /** The enabled profiles of the last meta (any world's - profiles are global). */
    public List<RoadProfile> profiles() {
        for (WorldState state : worlds.values()) {
            RoadNetworkMeta meta = state.meta;
            if (meta != null) {
                return meta.profiles();
            }
        }
        return List.of();
    }

    /** Upper-case floor material names of every enabled profile (for the dirty tracker). */
    public Set<String> roadMaterialNames() {
        Set<String> names = new HashSet<>();
        for (RoadProfile profile : profiles()) {
            if (!profile.enabled()) {
                continue;
            }
            profile.materials().forEach(m -> {
                if (m.role() != net.knightsandkings.knk.core.domain.roads.RoadMaterialRole.OVERLAY) {
                    names.add(m.material().toUpperCase(java.util.Locale.ROOT));
                }
            });
        }
        return names;
    }

    public RoadTileCache files() {
        return files;
    }

    // ===== refreshes =====

    /** Tiles + meta of every loaded world. */
    public CompletableFuture<Void> refreshAll() {
        List<CompletableFuture<Void>> all = new ArrayList<>();
        for (String world : worldNames.get()) {
            all.add(reload(world));
        }
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new));
    }

    /** {@code /knk road reload}: tiles and meta of one world, ignoring the refresh timers. */
    public CompletableFuture<Void> reload(String world) {
        return refreshMeta(world).thenCompose(v -> refreshTiles(world));
    }

    private void refreshAllTiles() {
        for (String world : worldNames.get()) {
            refreshTiles(world);
        }
    }

    private void refreshAllMeta() {
        for (String world : worldNames.get()) {
            refreshMeta(world);
        }
    }

    /**
     * Downloads the tile list and every built tile whose version changed (conditional GET); tiles the API
     * no longer lists as built are dropped. Completes after the snapshot swap. One refresh per world at a
     * time; a second call while one runs completes immediately.
     */
    public CompletableFuture<Void> refreshTiles(String world) {
        WorldState state = worlds.computeIfAbsent(world, WorldState::new);
        if (!state.refreshing.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        return queryApi.tiles(world).thenCompose(tiles -> {
            state.tiles = tiles;
            Set<TileKey> built = new HashSet<>();
            List<CompletableFuture<Void>> downloads = new ArrayList<>();
            for (RoadTile tile : tiles) {
                if (!tile.isBuilt()) {
                    continue;
                }
                TileKey key = TileKey.of(tile);
                built.add(key);
                RoadTileGraph known = state.graphs.get(key);
                if (known == null) {
                    known = files.read(key).orElse(null);
                    if (known != null) {
                        state.graphs.put(key, known);
                    }
                }
                if (known != null && known.tile().version() == tile.version()) {
                    continue; // same version as the API lists: no request at all
                }
                downloads.add(downloadTile(state, key, known == null ? null : known.etag()));
            }
            for (TileKey stale : new ArrayList<>(state.graphs.keySet())) {
                if (!built.contains(stale)) {
                    state.graphs.remove(stale);
                    deleteFile(stale);
                }
            }
            return CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new));
        }).handle((v, ex) -> {
            state.refreshing.set(false);
            if (ex != null) {
                LOGGER.log(Level.WARNING, "[Roads] Tile refresh of " + world + " failed: " + RoadMessages.describeError(ex));
                return null;
            }
            rebuild(state, world);
            return null;
        });
    }

    /** Re-downloads one tile (after a build, or when an upsert bumped a neighbour's version). */
    public CompletableFuture<Void> invalidateTile(TileKey key) {
        WorldState state = worlds.computeIfAbsent(key.world(), WorldState::new);
        return downloadTile(state, key, null).handle((v, ex) -> {
            if (ex != null) {
                if (RoadMessages.isNotFound(ex)) {
                    state.graphs.remove(key);
                    deleteFile(key);
                } else {
                    LOGGER.log(Level.WARNING, "[Roads] Download of tile " + key + " failed: " + RoadMessages.describeError(ex));
                    return null;
                }
            }
            rebuild(state, key.world());
            return null;
        });
    }

    /** Re-downloads one tile by id (upsert results name bumped tiles by id). */
    public CompletableFuture<Void> invalidateTileId(String world, int tileId) {
        for (RoadTile tile : tiles(world)) {
            if (tile.id() == tileId) {
                return invalidateTile(TileKey.of(tile));
            }
        }
        return refreshTiles(world);
    }

    private CompletableFuture<Void> downloadTile(WorldState state, TileKey key, String etag) {
        return queryApi.tileGraph(key.world(), key.tileX(), key.tileZ(), etag).thenAccept(result -> {
            if (result.notModified()) {
                return;
            }
            RoadTileGraph graph = result.body();
            state.graphs.put(key, graph);
            try {
                files.write(graph);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "[Roads] Could not write tile cache " + files.fileOf(key) + ": " + e.getMessage());
            }
        });
    }

    /** Downloads the profiles and street names of a world and rebuilds its snapshot with them. */
    public CompletableFuture<Void> refreshMeta(String world) {
        WorldState state = worlds.computeIfAbsent(world, WorldState::new);
        return queryApi.meta(world).handle((meta, ex) -> {
            if (ex != null) {
                LOGGER.log(Level.WARNING, "[Roads] Meta refresh of " + world + " failed: " + RoadMessages.describeError(ex));
                return null;
            }
            boolean changed = !Objects.equals(state.meta, meta);
            state.meta = meta;
            if (changed) {
                rebuild(state, world);
            }
            return null;
        });
    }

    /** Off the main thread (the caller's executor): builds the snapshot from the graphs + meta, swaps, notifies. */
    private void rebuild(WorldState state, String world) {
        RoadNetworkSnapshot.Builder builder = RoadNetworkSnapshot.builder(world);
        for (RoadTileGraph graph : state.graphs.values()) {
            builder.addNodes(graph.nodes()).addEdges(graph.edges());
        }
        RoadNetworkMeta meta = state.meta;
        if (meta != null) {
            meta.profiles().forEach(p -> builder.addProfile(RoadMapper.toSnapshotProfile(p)));
            meta.streets().forEach(builder::addStreet);
        }
        RoadNetworkSnapshot snapshot;
        try {
            snapshot = builder.build();
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "[Roads] Could not build the road network snapshot of " + world, e);
            return;
        }
        List<Integer> unresolved = snapshot.unresolvedEdgeIds();
        if (!unresolved.isEmpty()) {
            LOGGER.warning("[Roads] " + world + ": " + unresolved.size() + " edge(s) reference nodes of tiles not downloaded"
                + " (first: " + unresolved.subList(0, Math.min(5, unresolved.size())) + ")");
        }
        state.snapshot = snapshot;
        if (regionResolver != null) {
            regionResolver.warmCache(snapshot.regionIds());
        }
        LOGGER.info("[Roads] " + world + ": network snapshot " + snapshot.nodeCount() + " nodes, " + snapshot.edgeCount()
            + " edges from " + state.graphs.size() + " tile(s)");
        mainThread.execute(() -> listeners.forEach(l -> {
            try {
                l.accept(world);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "[Roads] A snapshot listener failed", e);
            }
        }));
    }

    private void deleteFile(TileKey key) {
        try {
            files.delete(key);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "[Roads] Could not delete tile cache " + key, e);
        }
    }

    /** The graphs currently held for a world (for the build job's neighbour anchors). */
    public Map<TileKey, RoadTileGraph> graphs(String world) {
        WorldState state = worlds.get(world);
        return state == null ? Map.of() : new HashMap<>(state.graphs);
    }

    /** Makes the conditional result readable in logs/tests. */
    static String describe(Conditional<RoadTileGraph> result) {
        return result.notModified() ? "304 " + result.etag() : "200 " + result.etag();
    }
}
