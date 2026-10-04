package net.knightsandkings.knk.paper.roads;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.api.mapper.RoadMapper;
import net.knightsandkings.knk.core.domain.roads.RoadBreadcrumbPoint;
import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.domain.roads.RoadSeed;
import net.knightsandkings.knk.core.domain.roads.RoadSeedLocation;
import net.knightsandkings.knk.core.domain.roads.RoadSurvey;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.domain.roads.RoadTileUpsertResult;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.ports.api.RoadNetworkCommandApi;
import net.knightsandkings.knk.core.ports.api.RoadNetworkQueryApi;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.roads.build.BuildParameters;
import net.knightsandkings.knk.core.roads.build.MaskBuilder;
import net.knightsandkings.knk.core.roads.build.NodeMatcher;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.build.ProfileSet;
import net.knightsandkings.knk.core.roads.build.ScopeLookup;
import net.knightsandkings.knk.core.roads.build.SkeletonGraph;
import net.knightsandkings.knk.core.roads.build.TileBuildResult;
import net.knightsandkings.knk.core.roads.build.TileBuilder;
import net.knightsandkings.knk.core.roads.route.CoverageCheck;
import net.knightsandkings.knk.core.roads.survey.SurveySample;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.regions.RegionIds;
import net.knightsandkings.knk.paper.utils.TickBudget;

/**
 * Builds one road tile (DESIGN §5.4-5.8; plan Phase 3 "RoadBuildJob"), in phases:
 * <ol>
 *   <li><b>Inputs</b> (api-client threads): profiles, this tile's previous graph (cache or download), the
 *       neighbours' Boundary nodes (from the cache), seeds - admin/survey seeds, survey breadcrumbs, Domain
 *       Locations with a road nearby ({@code seed-locations}, D12) - in the tile + margin.</li>
 *   <li><b>Capture</b> (main thread, {@code snapshot-chunks-per-tick}, fewer under lag - R11): the chunks
 *       around every seed, then repeatedly the frontier chunks that hold spans on a captured chunk's border
 *       ({@link CompactSpans#frontierChunks}), loaded with {@code getChunkAtAsync(x, z, false)} - never
 *       generating terrain - and reduced at once to compact spans ({@link ChunkSnapshotSurfaceGrid}).</li>
 *   <li><b>Build</b> (the queue's build thread): {@link TileBuilder#build}, pure.</li>
 *   <li><b>Tagging</b> (main thread, budgeted): WorldGuard region ids every 4 blocks along each edge
 *       ({@link RegionIds} R8) → domain ids through the {@link RegionDomainResolver} (R7, D11).</li>
 *   <li><b>Upload</b>: {@code upsertTileGraph}; the cache re-downloads this tile and the bumped neighbours;
 *       the summary (counts, disappeared nodes, conflicts, warnings, survey coverage misses, each with a
 *       clickable teleport) goes to whoever asked.</li>
 * </ol>
 * All node/geometry coordinates are floor blocks (Phase 2c decision 1).
 */
public final class RoadBuildJob {
    private static final Logger LOGGER = Logger.getLogger(RoadBuildJob.class.getName());
    /** Hard cap on chunks captured per tile, whatever the frontier says (a 576×576 region is 1296 chunks). */
    public static final int MAX_CHUNKS = 1600;
    /** Region-id lookups per tick while tagging (normal / lagging). */
    public static final int TAG_LOOKUPS_PER_TICK = 200;
    public static final int TAG_LOOKUPS_PER_TICK_LAGGING = 50;
    /** Geometry sampling step for the domain/region tags (D11). */
    public static final int TAG_SAMPLE_STEP = 4;
    /** Breadcrumb points thinned to one seed every this many blocks. */
    public static final int BREADCRUMB_SEED_STEP = 8;
    /** Domain Locations within this many blocks of a road cell seed the build (DESIGN §5.4). */
    public static final int DOMAIN_SEED_REACH = 8;

    /** What the job reports when it ends. */
    public record Outcome(TileKey tile, boolean success, String error, TileBuildResult build, RoadTileUpsertResult upsert,
                          int chunksCaptured, int spansExtracted, List<CoverageCheck.Miss> coverageMisses,
                          long millis) {
    }

    private final Plugin plugin;
    private final NavigationConfig config;
    private final RoadNetworkQueryApi queryApi;
    private final RoadNetworkCommandApi commandApi;
    private final RoadNetworkCache cache;
    private final GateManager gateManager;
    private final RegionIds regionIds;
    private final RegionDomainResolver regionResolver;
    private final Executor mainThread;
    private final Executor buildThread;
    private final TickBudget tickBudget;
    private final TileKey key;
    private final Consumer<String> progress;
    private final Consumer<Outcome> onDone;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final long startedAt = System.currentTimeMillis();

    // filled by the phases
    private List<RoadProfile> profiles = List.of();
    private NodeMatcher.PreviousGraph previousGraph = NodeMatcher.PreviousGraph.EMPTY;
    private List<SkeletonGraph.Anchor> anchors = List.of();
    private List<SkeletonGraph.Plaza> plazas = List.of();
    private final Set<MaskBuilder.Seed> seeds = new LinkedHashSet<>();
    private final List<RoadBreadcrumbPoint> breadcrumbs = new ArrayList<>();
    private ChunkSnapshotSurfaceGrid grid;
    private final Deque<Long> chunkQueue = new ArrayDeque<>();
    private final Set<Long> chunkSeen = new HashSet<>();
    private int inFlight;
    private int chunksCaptured;
    private BukkitTask captureTask;
    private BukkitTask tagTask;

    public RoadBuildJob(Plugin plugin, NavigationConfig config, RoadNetworkQueryApi queryApi, RoadNetworkCommandApi commandApi,
                        RoadNetworkCache cache, GateManager gateManager, RegionIds regionIds, RegionDomainResolver regionResolver,
                        Executor mainThread, Executor buildThread, TickBudget tickBudget, TileKey key,
                        Consumer<String> progress, Consumer<Outcome> onDone) {
        this.plugin = plugin;
        this.config = Objects.requireNonNull(config, "config");
        this.queryApi = Objects.requireNonNull(queryApi, "queryApi");
        this.commandApi = Objects.requireNonNull(commandApi, "commandApi");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.gateManager = gateManager;
        this.regionIds = regionIds;
        this.regionResolver = regionResolver;
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.buildThread = Objects.requireNonNull(buildThread, "buildThread");
        this.tickBudget = tickBudget == null ? new TickBudget(() -> 20.0) : tickBudget;
        this.key = Objects.requireNonNull(key, "key");
        this.progress = progress == null ? s -> { } : progress;
        this.onDone = Objects.requireNonNull(onDone, "onDone");
    }

    public TileKey tile() {
        return key;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    // ===== phase 1: inputs =====

    /** Main thread. Starts the job; {@code onDone} runs (main thread) exactly once when it ends. */
    public void start() {
        World world = Bukkit.getWorld(key.world());
        if (world == null) {
            fail("world '" + key.world() + "' is not loaded", null);
            return;
        }
        progress.accept("Tile " + key.tileX() + "," + key.tileZ() + ": loading inputs…");
        BuildParameters params = config.builder().buildParameters();
        MaskBuilder.Region region = MaskBuilder.Region.tile(key.tileX(), key.tileZ(), params.tileSize()).grow(params.tileMargin());

        CompletableFuture<List<RoadProfile>> profilesF = queryApi.profiles();
        CompletableFuture<List<RoadSeed>> seedsF = queryApi.seeds(key.world());
        CompletableFuture<List<RoadSurvey>> surveysF = queryApi.surveys(key.world());
        CompletableFuture<List<RoadSeedLocation>> locationsF = config.seedFromDomains()
            ? queryApi.seedLocations(key.world(), region.minX() - DOMAIN_SEED_REACH, region.minZ() - DOMAIN_SEED_REACH,
                region.maxX() + DOMAIN_SEED_REACH, region.maxZ() + DOMAIN_SEED_REACH)
            : CompletableFuture.<List<RoadSeedLocation>>completedFuture(List.of());
        CompletableFuture<Optional<RoadTileGraph>> previousF = previousGraph();

        CompletableFuture.allOf(profilesF, seedsF, surveysF, locationsF, previousF).whenComplete((v, ex) -> mainThread.execute(() -> {
            if (ex != null) {
                fail("could not load the build inputs: " + RoadMessages.describeError(ex), ex);
                return;
            }
            if (cancelled.get()) {
                fail("cancelled", null);
                return;
            }
            profiles = profilesF.join();
            previousF.join().ifPresent(graph -> {
                previousGraph = RoadMapper.toPreviousGraph(graph);
                anchors = RoadMapper.toAnchors(graph);
                plazas = RoadMapper.toPlazas(graph);
            });
            for (RoadSeed seed : seedsF.join()) {
                if (region.contains(seed.x(), seed.z())) {
                    seeds.add(new MaskBuilder.Seed(seed.x(), seed.y(), seed.z()));
                }
            }
            for (RoadSurvey survey : surveysF.join()) {
                int sinceSeed = BREADCRUMB_SEED_STEP;
                for (RoadBreadcrumbPoint p : survey.breadcrumb()) {
                    if (!region.contains(p.x(), p.z())) {
                        continue;
                    }
                    breadcrumbs.add(p);
                    if (p.onRoad() && ++sinceSeed >= BREADCRUMB_SEED_STEP) {
                        seeds.add(new MaskBuilder.Seed(p.x(), p.y(), p.z()));
                        sinceSeed = 0;
                    }
                }
            }
            for (RoadSeedLocation location : locationsF.join()) {
                // A Location's y is where a player stands; the seed snaps to a span within ±4 anyway.
                seeds.add(new MaskBuilder.Seed(location.x(), location.y() - 1, location.z()));
            }
            // Boundary nodes of already-built neighbours: the roads continue from there into this tile.
            for (Map.Entry<TileKey, RoadTileGraph> entry : cache.graphs(key.world()).entrySet()) {
                if (entry.getKey().equals(key) || entry.getKey().distanceTo(key) != 1) {
                    continue;
                }
                for (RoadNode node : entry.getValue().nodes()) {
                    if (node.kind() == RoadNodeKind.BOUNDARY && region.contains(node.x(), node.z())) {
                        seeds.add(new MaskBuilder.Seed(node.x(), node.y(), node.z()));
                    }
                }
            }
            if (seeds.isEmpty()) {
                finish(new Outcome(key, false, "no seeds: no domain Location, survey or admin seed in or near this tile "
                    + "(walk it with /knk road survey or add /knk road seed add)", null, null, 0, 0, List.of(), elapsed()));
                return;
            }
            startCapture(world, params, region);
        }));
    }

    private CompletableFuture<Optional<RoadTileGraph>> previousGraph() {
        Optional<RoadTileGraph> cached = cache.tileGraph(key);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(cached);
        }
        boolean built = cache.tiles(key.world()).stream().anyMatch(t -> t.tileX() == key.tileX() && t.tileZ() == key.tileZ() && t.isBuilt());
        if (!built) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return queryApi.tileGraph(key.world(), key.tileX(), key.tileZ(), null).handle((result, ex) -> {
            if (ex != null) {
                if (RoadMessages.isNotFound(ex)) {
                    return Optional.empty();
                }
                throw new java.util.concurrent.CompletionException(ex);
            }
            return result.bodyOptional();
        });
    }

    // ===== phase 2: capture =====

    private void startCapture(World world, BuildParameters params, MaskBuilder.Region region) {
        Set<String> floorMaterials = new HashSet<>();
        for (RoadProfile profile : profiles) {
            if (!profile.enabled()) {
                continue;
            }
            profile.materials().stream().filter(m -> m.role() != RoadMaterialRole.OVERLAY)
                .forEach(m -> floorMaterials.add(m.material().toUpperCase(Locale.ROOT)));
        }
        if (floorMaterials.isEmpty()) {
            finish(new Outcome(key, false, "no enabled road profile has any material - survey a road first (/knk road survey start)",
                null, null, 0, 0, List.of(), elapsed()));
            return;
        }
        PassabilityRules rules = config.passabilityRules(ChunkSnapshotSurfaceGrid.bukkitCollidable());
        GateCellsIndex gates = GateCellsIndex.of(gateManager, key.world());
        grid = new ChunkSnapshotSurfaceGrid(rules, floorMaterials::contains, gates, world.getMinHeight(), world.getMaxHeight());
        for (MaskBuilder.Seed seed : seeds) {
            for (long chunk : CompactSpans.chunksAround(seed.x(), seed.z(), 1)) {
                enqueueChunk(chunk, region);
            }
        }
        captureTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> captureTick(world, params, region), 1L, 1L);
    }

    private void enqueueChunk(long chunkKey, MaskBuilder.Region region) {
        int cx = CompactSpans.chunkX(chunkKey);
        int cz = CompactSpans.chunkZ(chunkKey);
        if ((cx << 4) + 15 < region.minX() || (cx << 4) > region.maxX() || (cz << 4) + 15 < region.minZ() || (cz << 4) > region.maxZ()) {
            return;
        }
        if (chunkSeen.add(chunkKey)) {
            chunkQueue.addLast(chunkKey);
        }
    }

    /** Main thread, every tick while capturing. */
    private void captureTick(World world, BuildParameters params, MaskBuilder.Region region) {
        if (cancelled.get()) {
            stopCapture();
            fail("cancelled", null);
            return;
        }
        int budget = tickBudget.perTick(config.builder().snapshotChunksPerTick(), Math.max(1, config.builder().snapshotChunksPerTick() / 2));
        while (budget-- > 0 && !chunkQueue.isEmpty()) {
            long chunkKey = chunkQueue.pollFirst();
            int cx = CompactSpans.chunkX(chunkKey);
            int cz = CompactSpans.chunkZ(chunkKey);
            if (!world.isChunkGenerated(cx, cz)) {
                grid.captureEmpty(cx, cz);
                continue;
            }
            inFlight++;
            world.getChunkAtAsync(cx, cz, false).whenComplete((chunk, ex) -> mainThread.execute(() -> {
                inFlight--;
                if (grid == null) {
                    return; // job already ended
                }
                if (ex != null || chunk == null) {
                    grid.captureEmpty(cx, cz);
                    return;
                }
                captureChunk(chunk, cx, cz);
            }));
        }
        progress.accept("Tile " + key.tileX() + "," + key.tileZ() + ": capturing chunks " + chunksCaptured + " (" + grid.spans().spanCount() + " road cells)"
            + (tickBudget.isLagging() ? " - server lagging, slowed down" : ""));
        if (chunkQueue.isEmpty() && inFlight == 0) {
            Set<Long> frontier = chunksCaptured < MAX_CHUNKS ? grid.spans().frontierChunks(region.minX(), region.minZ(), region.maxX(), region.maxZ()) : Set.of();
            frontier.forEach(c -> enqueueChunk(c, region));
            if (chunkQueue.isEmpty()) {
                stopCapture();
                startBuild(params, region);
            }
        }
    }

    private void captureChunk(Chunk chunk, int cx, int cz) {
        try {
            grid.capture(chunk.getChunkSnapshot(false, false, false), cx, cz);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[Roads] Chunk " + cx + "," + cz + " of " + key.world() + " could not be extracted", e);
            grid.captureEmpty(cx, cz);
        }
        chunksCaptured++;
    }

    private void stopCapture() {
        if (captureTask != null) {
            captureTask.cancel();
            captureTask = null;
        }
    }

    // ===== phase 3: build =====

    private void startBuild(BuildParameters params, MaskBuilder.Region region) {
        progress.accept("Tile " + key.tileX() + "," + key.tileZ() + ": building the graph from " + grid.spans().spanCount() + " road cells…");
        ScopeLookup scope = scopeLookup();
        ProfileSet profileSet = new ProfileSet(RoadMapper.toBuilderProfiles(profiles), scope);
        TileBuilder.TileRequest request = new TileBuilder.TileRequest(key.world(), key.tileX(), key.tileZ(), params,
            new ArrayList<>(seeds), profileSet, GateCellsIndex.of(gateManager, key.world()), anchors, previousGraph, plazas);
        ChunkSnapshotSurfaceGrid surface = grid;
        buildThread.execute(() -> {
            TileBuildResult result;
            try {
                result = new TileBuilder().build(request, surface);
            } catch (RuntimeException e) {
                mainThread.execute(() -> fail("the tile builder failed: " + e.getMessage(), e));
                return;
            }
            if (surface.unknownQueries() > 0) {
                LOGGER.fine("[Roads] Tile " + key + ": the builder asked about " + surface.unknownQueries() + " cells outside the extraction");
            }
            mainThread.execute(() -> startTagging(result));
        });
    }

    /**
     * Town scope of a column for scoped profiles (DESIGN §3.1, D8): the Town whose WorldGuard region
     * contains the column, resolved from the domain cache without a refresh (the snapshot rebuild warms it).
     * Main thread only - it queries WorldGuard - so the builder gets a precomputed answer: scopes are
     * resolved lazily but cached per column.
     */
    private ScopeLookup scopeLookup() {
        boolean anyScoped = profiles.stream().anyMatch(p -> p.enabled() && !p.scopeTownIds().isEmpty());
        if (!anyScoped || regionIds == null || regionResolver == null) {
            return ScopeLookup.NONE;
        }
        // Precompute per chunk column centre on the main thread now (cheap: one lookup per captured chunk).
        Map<Long, OptionalInt> townByChunk = new HashMap<>();
        World world = Bukkit.getWorld(key.world());
        for (long chunkKey : capturedChunkKeys()) {
            int cx = CompactSpans.chunkX(chunkKey);
            int cz = CompactSpans.chunkZ(chunkKey);
            OptionalInt town = OptionalInt.empty();
            if (world != null) {
                for (String regionId : regionIds.at(world, (cx << 4) + 8, world.getMinHeight() + 64, (cz << 4) + 8)) {
                    Optional<RegionDomainResolver.DomainSnapshot> domain = regionResolver.getDomainByRegionIdNoRefresh(regionId);
                    if (domain.isPresent() && "Town".equalsIgnoreCase(domain.get().domainType()) && domain.get().id() != null) {
                        town = OptionalInt.of(domain.get().id());
                        break;
                    }
                }
            }
            townByChunk.put(chunkKey, town);
        }
        return (x, z) -> townByChunk.getOrDefault(CompactSpans.chunkKey(x >> 4, z >> 4), OptionalInt.empty());
    }

    private Set<Long> capturedChunkKeys() {
        return new HashSet<>(chunkSeen);
    }

    // ===== phase 4: tagging =====

    private void startTagging(TileBuildResult result) {
        if (cancelled.get()) {
            fail("cancelled", null);
            return;
        }
        World world = Bukkit.getWorld(key.world());
        if (world == null || regionIds == null) {
            upload(result, Map.of(), Map.of());
            return;
        }
        // Sample points per edge index.
        List<int[]> samples = new ArrayList<>();
        List<Integer> sampleEdge = new ArrayList<>();
        for (int e = 0; e < result.edges().size(); e++) {
            List<int[]> geometry = result.edges().get(e).geometry();
            double since = TAG_SAMPLE_STEP;
            for (int i = 0; i < geometry.size(); i++) {
                int[] p = geometry.get(i);
                if (i == 0 || i == geometry.size() - 1 || since >= TAG_SAMPLE_STEP) {
                    samples.add(p);
                    sampleEdge.add(e);
                    since = 0;
                }
                if (i + 1 < geometry.size()) {
                    int[] q = geometry.get(i + 1);
                    since += Math.sqrt(Math.pow(q[0] - p[0], 2) + Math.pow(q[1] - p[1], 2) + Math.pow(q[2] - p[2], 2));
                }
            }
        }
        Map<Integer, Set<String>> regionsByEdge = new HashMap<>();
        int[] cursor = {0};
        tagTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (cancelled.get()) {
                stopTagging();
                fail("cancelled", null);
                return;
            }
            int budget = tickBudget.perTick(TAG_LOOKUPS_PER_TICK, TAG_LOOKUPS_PER_TICK_LAGGING);
            while (budget-- > 0 && cursor[0] < samples.size()) {
                int[] p = samples.get(cursor[0]);
                Set<String> ids = regionIds.at(world, p[0], p[1] + 1, p[2]);
                regionsByEdge.computeIfAbsent(sampleEdge.get(cursor[0]), k -> new TreeSet<>()).addAll(ids);
                cursor[0]++;
            }
            progress.accept("Tile " + key.tileX() + "," + key.tileZ() + ": tagging domains " + cursor[0] + "/" + samples.size());
            if (cursor[0] >= samples.size()) {
                stopTagging();
                resolveDomains(result, regionsByEdge);
            }
        }, 1L, 1L);
    }

    private void stopTagging() {
        if (tagTask != null) {
            tagTask.cancel();
            tagTask = null;
        }
    }

    private void resolveDomains(TileBuildResult result, Map<Integer, Set<String>> regionsByEdge) {
        Set<String> all = new HashSet<>();
        regionsByEdge.values().forEach(all::addAll);
        CompletableFuture<Void> warm = regionResolver == null ? CompletableFuture.completedFuture(null) : regionResolver.warmCache(all);
        warm.whenComplete((v, ex) -> mainThread.execute(() -> {
            Map<Integer, List<Integer>> domainsByEdge = new HashMap<>();
            if (regionResolver != null) {
                regionsByEdge.forEach((edge, regions) -> {
                    TreeSet<Integer> domains = new TreeSet<>();
                    for (String regionId : regions) {
                        regionResolver.getDomainByRegionIdNoRefresh(regionId).map(RegionDomainResolver.DomainSnapshot::id)
                            .filter(Objects::nonNull).ifPresent(domains::add);
                    }
                    domainsByEdge.put(edge, new ArrayList<>(domains));
                });
            }
            upload(result, regionsByEdge, domainsByEdge);
        }));
    }

    // ===== phase 5: upload =====

    private void upload(TileBuildResult result, Map<Integer, Set<String>> regionsByEdge, Map<Integer, List<Integer>> domainsByEdge) {
        List<TileBuildResult.Edge> tagged = new ArrayList<>(result.edges().size());
        for (int i = 0; i < result.edges().size(); i++) {
            TileBuildResult.Edge e = result.edges().get(i);
            tagged.add(new TileBuildResult.Edge(e.existingId(), e.fromKey(), e.toKey(), e.geometry(), e.length(), e.avgWidth(),
                e.profileId(), e.gateDoorIds(), domainsByEdge.getOrDefault(i, List.of()),
                new ArrayList<>(regionsByEdge.getOrDefault(i, Set.of()))));
        }
        TileBuildResult build = new TileBuildResult(result.builderVersion(), result.cellCount(), result.levelCount(),
            result.nodes(), tagged, result.warnings(), result.corrections());
        progress.accept("Tile " + key.tileX() + "," + key.tileZ() + ": uploading " + build.nodes().size() + " nodes, " + build.edges().size() + " edges…");
        commandApi.upsertTileGraph(key.world(), key.tileX(), key.tileZ(), build).whenComplete((upsert, ex) -> mainThread.execute(() -> {
            if (ex != null) {
                fail("the API refused the tile: " + RoadMessages.describeError(ex), ex);
                return;
            }
            List<CompletableFuture<Void>> refreshes = new ArrayList<>();
            refreshes.add(cache.invalidateTile(key));
            for (int bumped : upsert.bumpedTileIds()) {
                refreshes.add(cache.invalidateTileId(key.world(), bumped));
            }
            CompletableFuture.allOf(refreshes.toArray(CompletableFuture[]::new)).whenComplete((v, ex2) -> mainThread.execute(() -> {
                List<CoverageCheck.Miss> misses = coverageMisses();
                finish(new Outcome(key, true, null, build, upsert, chunksCaptured, grid == null ? 0 : grid.spans().spanCount(), misses, elapsed()));
            }));
        }));
    }

    /** Survey breadcrumbs marked on-road in this tile that ended up more than 2 blocks from any built edge. */
    private List<CoverageCheck.Miss> coverageMisses() {
        if (breadcrumbs.isEmpty()) {
            return List.of();
        }
        List<SurveySample> crumbs = new ArrayList<>();
        List<String> unknown = java.util.Collections.nCopies(SurveySample.WIDTH, "?");
        for (RoadBreadcrumbPoint p : breadcrumbs) {
            if (p.onRoad() && key.contains(p.x(), p.z())) {
                crumbs.add(SurveySample.of(p.x(), p.y(), p.z(), true, null, unknown));
            }
        }
        return CoverageCheck.misses(crumbs, cache.snapshot(key.world()));
    }

    // ===== end =====

    private void fail(String error, Throwable cause) {
        if (cause != null) {
            LOGGER.log(Level.WARNING, "[Roads] Build of tile " + key + " failed: " + error, cause);
        }
        finish(new Outcome(key, false, error, null, null, chunksCaptured, grid == null ? 0 : grid.spans().spanCount(), List.of(), elapsed()));
    }

    private void finish(Outcome outcome) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        stopCapture();
        stopTagging();
        grid = null; // release the extraction
        onDone.accept(outcome);
    }

    private long elapsed() {
        return System.currentTimeMillis() - startedAt;
    }

    /** True for a tile the API lists as built (for the queue's resume rule). */
    static boolean isBuilt(List<RoadTile> tiles, TileKey key) {
        return tiles.stream().anyMatch(t -> t.tileX() == key.tileX() && t.tileZ() == key.tileZ() && t.isBuilt());
    }
}
