package net.knightsandkings.knk.paper.navigation.walk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.util.BlockKey;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.roads.SpanExtractor;
import net.knightsandkings.knk.paper.utils.TickBudget;

/**
 * Captures the world around a walk leg on the main thread (KNG-51 {@code LAST_MILE_PATHFINDING.md}
 * §3, §8) and hands the routing thread an immutable {@link CapturedWalkTerrain}:
 * <ul>
 *   <li><b>Loaded chunks only.</b> A box touching an unloaded chunk is {@link Status#UNLOADED} at once
 *       (no chunk loading in v1, decision §11-6); a chunk that unloads while queued fails the request
 *       the same way. Navigation then keeps the straight line.</li>
 *   <li><b>Spread over ticks.</b> {@link #tick} (every server tick once {@link #start}ed) captures at
 *       most {@value #CHUNKS_PER_TICK} chunks, {@value #CHUNKS_PER_TICK_LAGGING} while the server lags
 *       ({@link TickBudget}, R11); a request waits until its chunks are in.</li>
 *   <li><b>Shared TTL cache</b> keyed by world and chunk (not per player — §8, §13): a chunk captured
 *       less than {@code chunk-ttl-seconds} ago that covers the request's sections is reused; at most
 *       {@value #CACHE_MAX_CHUNKS} chunks are kept (least recently used out). A world's chunks are
 *       dropped when its gate footprints change (the capture records floors under gates).</li>
 *   <li><b>Only the box's sections</b> ({@link WalkBox}: the leg ± {@code capture-margin}, up and down
 *       too) are read, not the whole column.</li>
 * </ul>
 * Main thread only (a single-threaded queue and cache, no locks); the result is safe to read anywhere.
 */
public final class WalkSnapshotService {

    /** Chunks captured per tick. */
    public static final int CHUNKS_PER_TICK = 4;
    /** Chunks captured per tick while the server lags. */
    public static final int CHUNKS_PER_TICK_LAGGING = 1;
    /** Cached chunks kept at most (≈ 10-20 KB each, see the Phase B measurement). */
    public static final int CACHE_MAX_CHUNKS = 256;
    /** A box larger than this many chunks is refused (a 48-block leg + 16 margin needs at most 6 × 6). */
    public static final int MAX_CHUNKS_PER_REQUEST = 49;

    /** Port: one world's chunks, as the main thread sees them. */
    public interface WorldChunks {
        String name();

        int minY();

        /** Exclusive, like {@code World.getMaxHeight()}. */
        int maxY();

        boolean isLoaded(int chunkX, int chunkZ);

        /** A loaded chunk's blocks, chunk-local coordinates (a snapshot, so it may be read later). */
        SpanExtractor.BlockSource blocks(int chunkX, int chunkZ);
    }

    /** {@link WorldChunks} over a Bukkit world: {@code isChunkLoaded}, a {@code ChunkSnapshot} without heightmaps or biomes. */
    public static WorldChunks of(World world) {
        Objects.requireNonNull(world, "world");
        return new WorldChunks() {
            @Override
            public String name() {
                return world.getName();
            }

            @Override
            public int minY() {
                return world.getMinHeight();
            }

            @Override
            public int maxY() {
                return world.getMaxHeight();
            }

            @Override
            public boolean isLoaded(int chunkX, int chunkZ) {
                return world.isChunkLoaded(chunkX, chunkZ);
            }

            @Override
            public SpanExtractor.BlockSource blocks(int chunkX, int chunkZ) {
                ChunkSnapshot snapshot = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false);
                return (lx, y, lz) -> snapshot.getBlockType(lx, y, lz).name();
            }
        };
    }

    public enum Status {
        /** The terrain is captured. */
        READY,
        /** A chunk of the box is not loaded (or unloaded before it was captured): no walk path. */
        UNLOADED,
        /** The box covers more than {@link #MAX_CHUNKS_PER_REQUEST} chunks. */
        TOO_LARGE
    }

    /**
     * The outcome of one capture.
     *
     * @param terrain    the captured terrain (READY only)
     * @param doorBlocks the hand-openable door blocks inside the box (READY only) — what the door access checks
     */
    public record WalkCapture(Status status, WalkBox box, CapturedWalkTerrain terrain, List<Long> doorBlocks) {
        public WalkCapture {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(box, "box");
            doorBlocks = doorBlocks == null ? List.of() : List.copyOf(doorBlocks);
        }

        public boolean ready() {
            return status == Status.READY;
        }
    }

    /** Counters for the §8 measurements (and a later debug command). */
    public record Stats(long chunksCaptured, long cacheHits, long captureNanos, int cachedChunks, long cachedBytes,
                        int pendingRequests) {
        /** Mean main-thread time per captured chunk, in microseconds. */
        public double meanCaptureMicros() {
            return chunksCaptured == 0 ? 0 : captureNanos / 1000.0 / chunksCaptured;
        }
    }

    private record ChunkId(String world, long key) {
    }

    private final class Pending {
        final WorldChunks world;
        final GateCells gates;
        final WalkBox box;
        final CompletableFuture<WalkCapture> future = new CompletableFuture<>();
        final Map<Long, WalkChunk> chunks = new HashMap<>();
        final List<Long> needed;

        Pending(WorldChunks world, GateCells gates, WalkBox box) {
            this.world = world;
            this.gates = gates;
            this.box = box;
            this.needed = box.chunkKeys();
        }

        boolean wants(ChunkId id, WalkChunk chunk) {
            return id.world.equals(world.name()) && needed.contains(id.key) && !chunks.containsKey(id.key)
                && chunk.covers(box.minSection(), box.maxSection());
        }

        void completeIfReady() {
            if (chunks.size() < needed.size() || future.isDone()) {
                return;
            }
            List<Long> doors = new ArrayList<>();
            for (WalkChunk chunk : chunks.values()) {
                for (long key : chunk.doorBlocks()) {
                    if (box.contains(BlockKey.x(key), BlockKey.y(key), BlockKey.z(key))) {
                        doors.add(key);
                    }
                }
            }
            CapturedWalkTerrain terrain = new CapturedWalkTerrain(chunks.values(), gates, world.minY(), world.maxY());
            future.complete(new WalkCapture(Status.READY, box, terrain, doors));
        }
    }

    private record Job(WorldChunks world, GateCells gates, int chunkX, int chunkZ, int fromSection, int toSection) {
    }

    private final PassabilityRules rules;
    private final NavigationConfig.WalkConfig config;
    private final TickBudget tickBudget;
    private final LongSupplier clockMillis;
    private final Map<String, WalkChunkExtractor> extractors = new HashMap<>();
    private final Map<String, GateCells> gatesByWorld = new HashMap<>();
    private final LinkedHashMap<ChunkId, WalkChunk> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ChunkId, WalkChunk> eldest) {
            return size() > CACHE_MAX_CHUNKS;
        }
    };
    private final LinkedHashMap<ChunkId, Job> queue = new LinkedHashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private BukkitTask task;
    private long chunksCaptured;
    private long cacheHits;
    private long captureNanos;

    /**
     * @param rules       the builder's passability rules (Bukkit's collision flag + overlay patterns)
     * @param config      {@code navigation.walk.*}
     * @param tickBudget  the lag check
     * @param clockMillis the cache clock ({@code System::currentTimeMillis})
     */
    public WalkSnapshotService(PassabilityRules rules, NavigationConfig.WalkConfig config, TickBudget tickBudget,
                               LongSupplier clockMillis) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.config = Objects.requireNonNull(config, "config");
        this.tickBudget = Objects.requireNonNull(tickBudget, "tickBudget");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** Runs {@link #tick} every server tick. */
    public void start(Plugin plugin) {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    /** Stops the ticker; pending requests complete as UNLOADED, the cache is dropped. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Pending p : pending) {
            p.future.complete(new WalkCapture(Status.UNLOADED, p.box, null, List.of()));
        }
        pending.clear();
        queue.clear();
        cache.clear();
    }

    /**
     * Main thread: captures (or reuses) the chunks of {@code box}. Completes at once for cache hits,
     * an unloaded chunk or a too large box; otherwise within a few ticks, on the main thread. The
     * caller may cancel the future; its chunks are then not captured for it.
     *
     * @param gates the world's gate-door cells now (paper: {@code GateCellsIndex.of(gateManager, world)})
     */
    public CompletableFuture<WalkCapture> capture(WorldChunks world, GateCells gates, WalkBox box) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(box, "box");
        GateCells gateCells = gates == null ? GateCells.NONE : gates;
        if (!world.name().equals(box.world())) {
            throw new IllegalArgumentException("box of world " + box.world() + " for world " + world.name());
        }
        if (box.chunkCount() > MAX_CHUNKS_PER_REQUEST) {
            return CompletableFuture.completedFuture(new WalkCapture(Status.TOO_LARGE, box, null, List.of()));
        }
        for (long key : box.chunkKeys()) {
            if (!world.isLoaded(chunkX(key), chunkZ(key))) {
                return CompletableFuture.completedFuture(new WalkCapture(Status.UNLOADED, box, null, List.of()));
            }
        }
        GateCells known = gatesByWorld.put(world.name(), gateCells);
        if (known != null && !known.equals(gateCells)) {
            cache.keySet().removeIf(id -> id.world.equals(world.name()));
        }

        Pending request = new Pending(world, gateCells, box);
        long now = clockMillis.getAsLong();
        for (long key : request.needed) {
            ChunkId id = new ChunkId(world.name(), key);
            WalkChunk cached = cache.get(id);
            if (cached != null && fresh(cached, now) && cached.covers(box.minSection(), box.maxSection())) {
                request.chunks.put(key, cached);
                cacheHits++;
                continue;
            }
            int from = box.minSection();
            int to = box.maxSection();
            if (cached != null && fresh(cached, now)) {
                from = Math.min(from, cached.minSection()); // keep what other recent requests needed
                to = Math.max(to, cached.maxSection());
            }
            Job queued = queue.get(id);
            if (queued != null) {
                from = Math.min(from, queued.fromSection());
                to = Math.max(to, queued.toSection());
            }
            queue.put(id, new Job(world, gateCells, chunkX(key), chunkZ(key), from, to));
        }
        request.completeIfReady();
        if (!request.future.isDone()) {
            pending.add(request);
        }
        return request.future;
    }

    /** Main thread, once per tick: captures the next queued chunks within the tick budget. */
    public void tick() {
        pending.removeIf(p -> p.future.isDone());
        if (queue.isEmpty()) {
            return;
        }
        int budget = tickBudget.perTick(CHUNKS_PER_TICK, CHUNKS_PER_TICK_LAGGING);
        Iterator<Map.Entry<ChunkId, Job>> it = queue.entrySet().iterator();
        while (budget > 0 && it.hasNext()) {
            Map.Entry<ChunkId, Job> entry = it.next();
            it.remove();
            ChunkId id = entry.getKey();
            Job job = entry.getValue();
            if (pending.stream().noneMatch(p -> p.world.name().equals(id.world) && p.needed.contains(id.key))) {
                continue; // nobody waits for it any more (cancelled or failed)
            }
            budget--;
            if (!job.world().isLoaded(job.chunkX(), job.chunkZ())) {
                failWaitingFor(id);
                continue;
            }
            long start = System.nanoTime();
            WalkChunk chunk = extractor(job.world()).extract(job.world().blocks(job.chunkX(), job.chunkZ()),
                job.chunkX(), job.chunkZ(), job.fromSection(), job.toSection(), s -> false, job.gates(),
                clockMillis.getAsLong());
            captureNanos += System.nanoTime() - start;
            chunksCaptured++;
            cache.put(id, chunk);
            for (Pending p : pending) {
                if (p.wants(id, chunk)) {
                    p.chunks.put(id.key, chunk);
                    p.completeIfReady();
                }
            }
        }
        pending.removeIf(p -> p.future.isDone());
    }

    private void failWaitingFor(ChunkId id) {
        for (Pending p : pending) {
            if (p.world.name().equals(id.world) && p.needed.contains(id.key)) {
                p.future.complete(new WalkCapture(Status.UNLOADED, p.box, null, List.of()));
            }
        }
    }

    private WalkChunkExtractor extractor(WorldChunks world) {
        return extractors.computeIfAbsent(world.name(), name -> new WalkChunkExtractor(rules,
            config.profile().climbables(), GateCells.NONE, world.minY(), world.maxY()));
    }

    private boolean fresh(WalkChunk chunk, long now) {
        return now - chunk.capturedAt() <= config.chunkTtlSeconds() * 1000L;
    }

    /** Drops every cached chunk of a world (world unload, a bulk edit). */
    public void invalidate(String world) {
        cache.keySet().removeIf(id -> id.world.equals(world));
    }

    public Stats stats() {
        long bytes = 0;
        for (WalkChunk chunk : cache.values()) {
            bytes += chunk.approximateBytes();
        }
        return new Stats(chunksCaptured, cacheHits, captureNanos, cache.size(), bytes, pending.size());
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }
}
