package net.knightsandkings.knk.paper.npcspike;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.PathStrategy;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.ai.StuckAction;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.ai.event.NavigationCancelEvent;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.DoorExaminer;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.knightsandkings.knk.core.roads.walk.WalkGoal;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
import net.knightsandkings.knk.paper.navigation.NavigationService;
import net.knightsandkings.knk.paper.navigation.walk.WalkBox;

/**
 * #243 Citizens spike (throwaway branch, <b>never merged</b>): spawns player-shaped Citizens NPCs in an
 * in-memory registry and walks them back and forth between two points on routes K&K navigation plans.
 *
 * <ul>
 *   <li><b>DENSE</b>: the K&K walk pathfinder plans the whole leg (cell by cell, with the subject's gate,
 *       door and domain access); Citizens follows the cells with {@code Navigator.setTarget(Iterable<Vector>)}
 *       — no Citizens pathfinding, so no Citizens door callbacks either.</li>
 *   <li><b>HOP</b>: the K&K road router plans the road route; the spike cuts it into hops of
 *       {@code hopSpacing} blocks and Citizens' own A* walks each hop with {@link SpikeExaminer}
 *       (K&K access in front of Citizens' examiners) — the hybrid of BEHAVIOR_MODEL §2.</li>
 * </ul>
 *
 * <p>Access subject: the K&K access code is player-bound today (NPC identity in permissions is DESIGN §7,
 * not built), so the admin who spawns the NPCs stands in as the subject. Spike shortcut only.
 *
 * <p>Persistence: {@code CitizensAPI.createInMemoryNPCRegistry} — nothing is written to Citizens' saves.yml.
 */
public final class CitizensSpike implements Listener {

    public enum Mode { DENSE, HOP }

    enum State { IDLE, QUEUED, PLANNING, MOVING, WAITING, PAUSED }

    static final String REGISTRY = "knk-npc-spike";
    static final int MAX_RETRIES = 3;
    static final int MAX_CAPTURE_FAILURES = 5;

    static final class SpikeNpc {
        final NPC npc;
        final Mode mode;
        boolean towardsTo = true;
        State state = State.IDLE;
        int generation;
        long waitUntil;
        long queuedAtNanos;
        long tripStartNanos;
        long targetSetNanos;
        boolean citizensPathSeen;
        int retries;
        int captureFailures;
        long lastPlanTick;
        final Deque<double[]> hops = new ArrayDeque<>();

        SpikeNpc(NPC npc, Mode mode) {
            this.npc = npc;
            this.mode = mode;
        }
    }

    private final Plugin plugin;
    private final NavigationService navigation;
    private final NavigationService.Walk walk;
    private final int captureMargin;
    private final Logger logger;
    final SpikeMetrics metrics = new SpikeMetrics();

    private NPCRegistry registry;
    private BukkitTask task;
    private long tick;
    private final Map<Integer, SpikeNpc> npcs = new LinkedHashMap<>();
    private final Deque<SpikeNpc> queue = new ArrayDeque<>();
    private int inFlight;
    private int loggedLegFailures;

    // settings (the command changes them)
    Location from;
    Location to;
    UUID subject;
    int maxInFlight = 4;
    PathfinderType pathfinderType = PathfinderType.CITIZENS;
    double hopSpacing = 16;
    int stationaryTicks = 60;
    int recheckTicks = 0;
    boolean openDoors = true;
    boolean collidable = false;

    private final StuckAction stuckAction = (npc, navigator) -> {
        metrics.stuck++;
        return false; // stop: NavigationCancelEvent(STUCK) replans from where the NPC stands
    };

    public CitizensSpike(Plugin plugin, NavigationService navigation, int captureMargin) {
        this.plugin = plugin;
        this.navigation = navigation;
        this.walk = navigation.spikeWalk();
        this.captureMargin = captureMargin;
        this.logger = plugin.getLogger();
        // tick sampling starts at once, so /knk npcspike baseline has data before the first spawn
        ensureStarted();
    }

    /** True when the Citizens plugin is present and enabled (the spike is a no-op otherwise). */
    public static boolean citizensAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens");
    }

    boolean walkAvailable() {
        return walk != null;
    }

    private void ensureStarted() {
        if (registry != null) {
            return;
        }
        registry = CitizensAPI.createInMemoryNPCRegistry(REGISTRY);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        logger.info("[NpcSpike] in-memory Citizens registry '" + REGISTRY + "' created");
    }

    public void shutdown() {
        clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
        if (registry != null) {
            CitizensAPI.removeNamedNPCRegistry(REGISTRY);
            registry = null;
        }
    }

    int count() {
        return npcs.size();
    }

    // ==================== spawn / clear ====================

    int spawn(int n, Mode mode, EntityType type) {
        ensureStarted();
        int made = 0;
        for (int i = 0; i < n; i++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double radius = 1.5 + ThreadLocalRandom.current().nextDouble(2.5);
            Location at = from.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            at.setY(from.getY());
            NPC npc = registry.createNPC(type, "spike-" + (npcs.size() + 1));
            npc.data().set(NPC.Metadata.SHOULD_SAVE, false);
            // Citizens appends its own DoorExaminer after the defaults when this is on, and "first PASSABLE
            // wins" would let it open a door K&K denies: doors go through SpikeExaminer instead.
            npc.data().set(NPC.Metadata.PATHFINDER_OPEN_DOORS, false);
            npc.data().set(NPC.Metadata.COLLIDABLE, collidable);
            if (!npc.spawn(at)) {
                metrics.fail("spawn refused");
                registry.deregister(npc);
                continue;
            }
            NavigatorParameters params = npc.getNavigator().getDefaultParameters();
            params.pathfinderType(pathfinderType)
                .stationaryTicks(stationaryTicks)
                .stuckAction(stuckAction)
                // no teleport shortcuts: a teleport to the target would skip a closed gate or a denied domain
                .destinationTeleportMargin(-1)
                .range((float) Math.max(params.range(), hopSpacing + captureMargin + 8));
            SpikeNpc s = new SpikeNpc(npc, mode);
            s.tripStartNanos = System.nanoTime();
            npcs.put(npc.getId(), s);
            metrics.spawned++;
            made++;
        }
        return made;
    }

    void clear() {
        for (SpikeNpc s : npcs.values()) {
            s.generation++;
            s.npc.destroy();
        }
        npcs.clear();
        queue.clear();
        if (registry != null) {
            registry.deregisterAll();
        }
    }

    // ==================== tick ====================

    private void tick() {
        long start = System.nanoTime();
        tick++;
        for (SpikeNpc s : npcs.values()) {
            switch (s.state) {
                case IDLE -> enqueue(s);
                case WAITING -> {
                    if (tick >= s.waitUntil) {
                        enqueue(s);
                    }
                }
                case MOVING -> watchMoving(s);
                default -> { }
            }
        }
        while (inFlight < maxInFlight && !queue.isEmpty()) {
            SpikeNpc s = queue.pollFirst();
            if (s.state != State.QUEUED || !s.npc.isSpawned()) {
                continue;
            }
            plan(s);
        }
        metrics.spikeNanosThisTick += System.nanoTime() - start;
    }

    private void watchMoving(SpikeNpc s) {
        Navigator navigator = s.npc.getNavigator();
        if (s.mode == Mode.HOP && !s.citizensPathSeen && navigator.isNavigating()) {
            PathStrategy strategy = navigator.getPathStrategy();
            if (strategy != null && strategy.getPath() != null) {
                s.citizensPathSeen = true;
                metrics.citizensPathMs.add((System.nanoTime() - s.targetSetNanos) / 1e6);
            }
        }
        if (s.mode == Mode.DENSE && recheckTicks > 0 && tick - s.lastPlanTick >= recheckTicks) {
            // a gate or an access rule may have changed since the cells were planned: replan from here
            metrics.recheckReplans++;
            s.generation++;
            enqueue(s);
        }
    }

    private void enqueue(SpikeNpc s) {
        s.state = State.QUEUED;
        s.queuedAtNanos = System.nanoTime();
        queue.addLast(s);
    }

    private void waitTicks(SpikeNpc s, long ticks) {
        s.state = State.WAITING;
        s.waitUntil = tick + ticks;
    }

    // ==================== planning ====================

    private static double[] floorOf(Location l) {
        return new double[] {l.getX(), Math.floor(l.getY()) - 1, l.getZ()};
    }

    private void plan(SpikeNpc s) {
        Player player = subject == null ? null : Bukkit.getPlayer(subject);
        if (player == null) {
            metrics.fail("subject offline");
            waitTicks(s, 100);
            return;
        }
        s.state = State.PLANNING;
        s.lastPlanTick = tick;
        metrics.queueMs.add((System.nanoTime() - s.queuedAtNanos) / 1e6);
        int generation = ++s.generation;
        inFlight++;
        if (s.mode == Mode.DENSE) {
            planDense(s, player, generation);
        } else if (s.hops.isEmpty()) {
            planRoad(s, player, generation);
        } else {
            planHop(s, player, generation);
        }
    }

    private void done(SpikeNpc s) {
        inFlight = Math.max(0, inFlight - 1);
    }

    private boolean current(SpikeNpc s, int generation) {
        return s.generation == generation && s.state == State.PLANNING && s.npc.isSpawned()
            && npcs.containsKey(s.npc.getId());
    }

    private Location goal(SpikeNpc s) {
        return s.towardsTo ? to : from;
    }

    /** The walk preparer's capture and access for one leg, then {@code then} on the main thread. */
    private void prepareLeg(SpikeNpc s, Player player, int generation, double[] target,
                            java.util.function.BiConsumer<WalkRequest, WalkBox> then) {
        Location feetLoc = s.npc.getEntity().getLocation();
        double[] feet = {feetLoc.getX(), feetLoc.getY(), feetLoc.getZ()};
        WalkGoal goal = WalkGoal.within(target[0], target[1], target[2], 1.5);
        World world = feetLoc.getWorld();
        WalkBox box = WalkBox.around(world.getName(), feet[0], feet[1], feet[2], target[0], target[1], target[2],
            captureMargin, world.getMinHeight(), world.getMaxHeight());
        walk.preparer().prepare(player, feet, target, goal, null).whenComplete((supplier, error) -> {
            if (!current(s, generation)) {
                done(s);
                return;
            }
            if (error != null || supplier == null) {
                done(s);
                metrics.fail(error != null ? "capture error" : captureReason(world, box));
                if (++s.captureFailures >= MAX_CAPTURE_FAILURES) {
                    // give the trip up instead of retrying the same leg for ever
                    metrics.fail("trip given up after " + MAX_CAPTURE_FAILURES + " capture failures");
                    s.captureFailures = 0;
                    s.hops.clear();
                    waitTicks(s, 100);
                } else {
                    waitTicks(s, 40);
                }
                return;
            }
            s.captureFailures = 0;
            // the supplier may block on domain lookups: build the request on the walk executor
            CompletableFuture.supplyAsync(supplier::get, walk.searches()).whenComplete((request, buildError) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!current(s, generation)) {
                        done(s);
                        return;
                    }
                    if (buildError != null) {
                        done(s);
                        metrics.fail("access build error");
                        waitTicks(s, 100);
                        return;
                    }
                    then.accept(request, box);
                }));
        });
    }

    private void planDense(SpikeNpc s, Player player, int generation) {
        double[] target = floorOf(goal(s));
        prepareLeg(s, player, generation, target, (request, box) ->
            CompletableFuture.supplyAsync(() -> walk.pathfinder().find(request), walk.searches())
                .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    done(s);
                    if (!current(s, generation)) {
                        return;
                    }
                    if (error != null || result == null) {
                        metrics.fail("walk search error");
                        waitTicks(s, 100);
                        return;
                    }
                    metrics.expansions.add(result.expansions());
                    Optional<net.knightsandkings.knk.core.roads.walk.WalkPath> path = result.path();
                    if (result.status() != WalkResult.Status.FOUND || path.isEmpty()) {
                        metrics.fail("walk " + result.status() + ": " + result.reason());
                        waitTicks(s, 100);
                        return;
                    }
                    List<Vector> cells = new ArrayList<>();
                    for (double[] p : path.get().points()) {
                        cells.add(new Vector(p[0], p[1] + 1, p[2])); // floor → feet
                    }
                    metrics.planMs.add((System.nanoTime() - s.queuedAtNanos) / 1e6);
                    s.npc.getNavigator().setTarget(cells);
                    s.targetSetNanos = System.nanoTime();
                    s.state = State.MOVING;
                })));
    }

    /**
     * A straight-line hop's interpolated height can be in the air or inside the ground. Citizens only moves a
     * target up 2 blocks, so search ±12 blocks (nearest first) for a floor with 2 free blocks above. Main thread.
     */
    static double[] snapToFloor(World world, double[] hop) {
        int x = (int) Math.floor(hop[0]);
        int z = (int) Math.floor(hop[2]);
        int y0 = (int) Math.round(hop[1]);
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return hop;
        }
        for (int d = 0; d <= 12; d++) {
            for (int floorY : d == 0 ? new int[] {y0} : new int[] {y0 + d, y0 - d}) {
                if (floorY <= world.getMinHeight() || floorY + 2 >= world.getMaxHeight()) {
                    continue;
                }
                if (world.getBlockAt(x, floorY, z).getType().isSolid()
                    && world.getBlockAt(x, floorY + 1, z).isPassable()
                    && world.getBlockAt(x, floorY + 2, z).isPassable()) {
                    return new double[] {hop[0], floorY, hop[2]};
                }
            }
        }
        return hop;
    }

    /** Why the walk preparer had no capture: the same two checks as WalkSnapshotService.capture. */
    private static String captureReason(World world, WalkBox box) {
        int chunks = box.chunkCount();
        if (chunks > net.knightsandkings.knk.paper.navigation.walk.WalkSnapshotService.MAX_CHUNKS_PER_REQUEST) {
            return "capture: box too large (" + chunks + " chunks > "
                + net.knightsandkings.knk.paper.navigation.walk.WalkSnapshotService.MAX_CHUNKS_PER_REQUEST + ")";
        }
        for (int cx = box.minChunkX(); cx <= box.maxChunkX(); cx++) {
            for (int cz = box.minChunkZ(); cz <= box.maxChunkZ(); cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    return "capture: unloaded chunk in box";
                }
            }
        }
        return "capture: not ready (other)";
    }

    private void planRoad(SpikeNpc s, Player player, int generation) {
        Location feet = s.npc.getEntity().getLocation();
        double[] feetFloor = floorOf(feet);
        double[] goalFloor = floorOf(goal(s));
        navigation.spikeRoadPolyline(player, feet.getWorld(), feetFloor, goalFloor).whenComplete((road, error) -> {
            if (!current(s, generation)) {
                done(s);
                return;
            }
            s.hops.clear();
            if (error != null || road == null || !road.found()) {
                // no road: straight-line hops, so each Citizens leg (and its capture box) stays short
                metrics.fail("no road route: " + (road == null ? "error" : road.reason()) + " (straight-line hops)");
                World world = feet.getWorld();
                for (double[] hop : resample(List.of(feetFloor, goalFloor), hopSpacing)) {
                    s.hops.add(snapToFloor(world, hop));
                }
            } else {
                s.hops.addAll(resample(road.line(), hopSpacing));
            }
            s.hops.addLast(goalFloor);
            planHop(s, player, generation);
        });
    }

    /** Points every {@code spacing} blocks along a floor polyline, its end included. */
    static List<double[]> resample(List<double[]> line, double spacing) {
        List<double[]> out = new ArrayList<>();
        if (line.isEmpty()) {
            return out;
        }
        double carried = 0;
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1);
            double[] b = line.get(i);
            double len = Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2));
            double along = spacing - carried;
            while (along <= len) {
                double t = along / len;
                out.add(new double[] {a[0] + (b[0] - a[0]) * t, Math.round(a[1] + (b[1] - a[1]) * t), a[2] + (b[2] - a[2]) * t});
                along += spacing;
            }
            carried = len - (along - spacing);
        }
        double[] last = line.get(line.size() - 1);
        if (out.isEmpty() || out.get(out.size() - 1) != last) {
            out.add(last);
        }
        return out;
    }

    private void planHop(SpikeNpc s, Player player, int generation) {
        double[] target = s.hops.peekFirst();
        prepareLeg(s, player, generation, target, (request, box) -> {
            done(s);
            NavigatorParameters params = s.npc.getNavigator().getDefaultParameters();
            params.clearExaminers();
            List<BlockExaminer> inner = new ArrayList<>();
            inner.add(new MinecraftBlockExaminer(s.npc));
            if (openDoors) {
                inner.add(new DoorExaminer());
            }
            params.examiner(new SpikeExaminer(request.access(), box, inner));
            Location dest = new Location(s.npc.getEntity().getWorld(), target[0], target[1] + 1, target[2]);
            metrics.planMs.add((System.nanoTime() - s.queuedAtNanos) / 1e6);
            s.npc.getNavigator().setTarget(dest);
            NavigatorParameters local = s.npc.getNavigator().getLocalParameters();
            BlockExaminer[] used = local.examiners();
            if (used.length != 1 || !(used[0] instanceof SpikeExaminer)) {
                // Citizens added an examiner after ours (doors, falling, bounding boxes, swimming): "first PASSABLE
                // wins" would let it overrule the K&K veto. Name it, then strip it: the sync planner reads the
                // examiners when it searches (on the next ticks), so this still takes effect.
                metrics.examinerLeaks++;
                StringBuilder names = new StringBuilder();
                SpikeExaminer ours = null;
                for (BlockExaminer e : used) {
                    if (e instanceof SpikeExaminer spike) {
                        ours = spike;
                    } else {
                        names.append(names.length() == 0 ? "" : "+").append(e.getClass().getSimpleName());
                    }
                }
                metrics.fail("examiner leak (stripped): " + names);
                local.clearExaminers();
                if (ours != null) {
                    local.examiner(ours);
                }
            }
            s.targetSetNanos = System.nanoTime();
            s.citizensPathSeen = false;
            s.state = State.MOVING;
        });
    }

    // ==================== events ====================

    private SpikeNpc ours(NPC npc) {
        return npc != null && npc.getOwningRegistry() == registry ? npcs.get(npc.getId()) : null;
    }

    @EventHandler
    public void onComplete(NavigationCompleteEvent event) {
        long start = System.nanoTime();
        SpikeNpc s = ours(event.getNPC());
        if (s != null && s.state == State.MOVING) {
            s.retries = 0;
            if (s.mode == Mode.HOP) {
                s.hops.pollFirst();
                metrics.hops++;
            }
            if (s.mode == Mode.DENSE || s.hops.isEmpty()) {
                metrics.trips++;
                metrics.tripSeconds.add((System.nanoTime() - s.tripStartNanos) / 1e9);
                s.tripStartNanos = System.nanoTime();
                s.towardsTo = !s.towardsTo;
                s.hops.clear();
                waitTicks(s, 20);
            } else {
                enqueue(s);
            }
        }
        metrics.spikeNanosThisTick += System.nanoTime() - start;
    }

    @EventHandler
    public void onCancel(NavigationCancelEvent event) {
        long start = System.nanoTime();
        SpikeNpc s = ours(event.getNPC());
        if (s != null && s.state == State.MOVING && event.getCancelReason() == CancelReason.STUCK) {
            // a hop whose A* never produced a path is "no path", not "stuck while walking"
            boolean noPath = s.mode == Mode.HOP && !s.citizensPathSeen;
            if (noPath) {
                metrics.fail("Citizens A*: no path to hop");
            } else {
                metrics.fail("stuck while walking");
            }
            if (loggedLegFailures < 20 && s.npc.isSpawned()) {
                loggedLegFailures++;
                Location at = s.npc.getEntity().getLocation();
                double[] hop = s.hops.peekFirst();
                logger.info(String.format(Locale.ROOT, "[NpcSpike] %s %s at %d %d %d -> hop floor %s",
                    s.npc.getName(), noPath ? "no path" : "stuck", at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                    hop == null ? "-" : String.format(Locale.ROOT, "%.0f %.0f %.0f", hop[0], hop[1], hop[2])));
            }
            if (++s.retries <= MAX_RETRIES) {
                metrics.replans++;
                enqueue(s); // HOP keeps its hops: retry towards the same hop from here (DESIGN §6 bounded retry)
            } else {
                metrics.fail("stuck: " + MAX_RETRIES + " retries used");
                s.retries = 0;
                s.hops.clear();
                waitTicks(s, 100);
            }
        }
        metrics.spikeNanosThisTick += System.nanoTime() - start;
    }

    @EventHandler
    public void onDespawn(NPCDespawnEvent event) {
        SpikeNpc s = ours(event.getNPC());
        if (s != null && event.getReason() == DespawnReason.CHUNK_UNLOAD) {
            metrics.pausedChunkUnload++;
            s.generation++;
            s.state = State.PAUSED;
            queue.remove(s);
        }
    }

    @EventHandler
    public void onSpawn(NPCSpawnEvent event) {
        SpikeNpc s = ours(event.getNPC());
        if (s != null && s.state == State.PAUSED) {
            metrics.resumed++;
            // NPCSpawnEvent fires before the entity is in the world: plan on the next tick
            waitTicks(s, 1);
        }
    }

    @EventHandler
    public void onTickEnd(ServerTickEndEvent event) {
        metrics.tickMs.add(event.getTickDuration());
        metrics.spikeMainUs.add(metrics.spikeNanosThisTick / 1000.0);
        metrics.spikeNanosThisTick = 0;
    }

    // ==================== report ====================

    List<String> report() {
        List<String> lines = new ArrayList<>();
        int spawned = 0;
        int navigating = 0;
        Map<State, Integer> states = new java.util.EnumMap<>(State.class);
        for (SpikeNpc s : npcs.values()) {
            if (s.npc.isSpawned()) {
                spawned++;
                if (s.npc.getNavigator().isNavigating()) {
                    navigating++;
                }
            }
            states.merge(s.state, 1, Integer::sum);
        }
        double mspt = metrics.tickMs.mean();
        double[] tps = Bukkit.getTPS();
        lines.add(String.format(Locale.ROOT, "NPCs %d (spawned %d, navigating %d) states %s queue %d in-flight %d/%d",
            npcs.size(), spawned, navigating, states, queue.size(), inFlight, maxInFlight));
        lines.add(String.format(Locale.ROOT, "tick: mean %.2f ms, %s | TPS %.2f | baseline %.2f ms -> %.3f ms per NPC",
            mspt, metrics.tickMs.describe("ms"), tps[0], metrics.baselineMs,
            spawned == 0 || Double.isNaN(metrics.baselineMs) ? Double.NaN : (mspt - metrics.baselineMs) / spawned));
        lines.add("spike main thread: " + metrics.spikeMainUs.describe("µs/tick"));
        lines.add("K&K plan (queued -> target set): " + metrics.planMs.describe("ms"));
        lines.add("queue wait: " + metrics.queueMs.describe("ms"));
        lines.add("Citizens A* (hop: target -> path): " + metrics.citizensPathMs.describe("ms"));
        lines.add("walk expansions: " + metrics.expansions.describe(""));
        lines.add("trip: " + metrics.tripSeconds.describe("s"));
        long calls = SpikeExaminer.CALLS.sum();
        lines.add(String.format(Locale.ROOT,
            "trips %d, hops %d, stuck %d, replans %d, recheck replans %d, chunk-unload pauses %d, resumes %d, examiner leaks %d",
            metrics.trips, metrics.hops, metrics.stuck, metrics.replans, metrics.recheckReplans,
            metrics.pausedChunkUnload, metrics.resumed, metrics.examinerLeaks));
        lines.add(String.format(Locale.ROOT, "examiner: %d calls, %.2f µs mean, %d blocked by K&K rules", calls,
            calls == 0 ? 0.0 : SpikeExaminer.NANOS.sum() / 1000.0 / calls, SpikeExaminer.BLOCKED.sum()));
        lines.add("failures: " + (metrics.failures.isEmpty() ? "none" : metrics.failures.toString()));
        Player who = subject == null ? null : Bukkit.getPlayer(subject);
        lines.add("access subject: " + (who == null ? (subject == null ? "none (run from/to)" : "offline")
            : who.getName() + (who.hasPermission("knk.region.bypass")
                ? " - HAS knk.region.bypass: domain rules are open, deop or negate it to test them" : "")));
        lines.add(String.format(Locale.ROOT, "settings: pathfinder %s, max-in-flight %d, hop %.0f, stationary %d, recheck %d, doors %s, collidable %s",
            pathfinderType, maxInFlight, hopSpacing, stationaryTicks, recheckTicks, openDoors, collidable));
        return lines;
    }

    /** One CSV row for the benchmark issue. */
    String csv(String label) {
        int spawned = (int) npcs.values().stream().filter(s -> s.npc.isSpawned()).count();
        return String.format(Locale.ROOT, "%s,%d,%.2f,%.2f,%.2f,%.2f,%.3f,%.1f,%.1f,%.1f,%.1f,%d,%d,%d,%d,%d",
            label, spawned, metrics.tickMs.mean(), metrics.tickMs.percentile(95), Bukkit.getTPS()[0], metrics.baselineMs,
            Double.isNaN(metrics.baselineMs) || spawned == 0 ? Double.NaN : (metrics.tickMs.mean() - metrics.baselineMs) / spawned,
            metrics.spikeMainUs.mean(), metrics.planMs.percentile(50), metrics.planMs.percentile(95),
            metrics.citizensPathMs.percentile(95), metrics.trips, metrics.stuck, metrics.replans,
            metrics.failures.values().stream().mapToLong(Long::longValue).sum(), metrics.examinerLeaks);
    }

    void metricsCsvLog(String label) {
        logger.info("[NpcSpike] CSV " + CSV_HEADER);
        logger.info("[NpcSpike] CSV " + csv(label));
    }

    static final String CSV_HEADER = "label,npcs,mspt_mean,mspt_p95,tps,baseline_ms,ms_per_npc,spike_us_tick,"
        + "plan_p50_ms,plan_p95_ms,citizens_path_p95_ms,trips,stuck,replans,failures,examiner_leaks";
}
