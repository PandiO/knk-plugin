package net.knightsandkings.knk.paper.npcspike;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    public enum Mode { DENSE, HOP, TRAIL }

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
        long lastRecheckTick;
        /** The regions the NPC stood in at the last trespass check (null before the first). */
        Set<String> regions;
        final Deque<double[]> hops = new ArrayDeque<>();
        /** The K&K road route as floor points every {@link #TRAIL_STEP} blocks (trail: followed; hop: stray metric). */
        List<double[]> line;
        /** Trail: first point of the stretch last sent to Citizens, and its exclusive end. */
        int lineIndex;
        int stretchEnd;
        /** Progress watchdog: where the NPC was at the last progress check, and when it last moved. */
        double[] progressAt;
        long lastProgressTick;
        /** Trail: the stretch start the bubble was last requested for (avoids re-requesting every tick). */
        int bubbleFor = -1;
        /** Trail: true while Citizens follows a stretch as a waypoint list, false during an A* leg. */
        boolean onTrail;
        /** Trail: the off-road stretch from the road's end to the goal, walked by A* after the trail. */
        final Deque<double[]> tail = new ArrayDeque<>();

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
    final SpikeRouteStore routes;

    private NPCRegistry registry;
    private BukkitTask task;
    private long tick;
    private final Map<Integer, SpikeNpc> npcs = new LinkedHashMap<>();
    private final Deque<SpikeNpc> queue = new ArrayDeque<>();
    private int inFlight;
    private int loggedLegFailures;
    private int loggedTrespasses;
    private Runnable unsubscribeRules;

    /** How often every spike NPC's position is checked against the current entry rules (ticks). */
    static final int TRESPASS_CHECK_TICKS = 20;
    /** How long an NPC with no road route waits before asking the router again (ticks). */
    static final int NO_ROUTE_WAIT_TICKS = 100;

    // settings (the command changes them)
    Location from;
    Location to;
    UUID subject;
    int maxInFlight = 4;
    PathfinderType pathfinderType = PathfinderType.CITIZENS;
    double hopSpacing = 16;
    int stationaryTicks = 60;
    /**
     * Re-check interval (ticks, 0 = off). HOP: the path ahead is checked against the current entry rules and
     * replanned only when it enters a region the NPC may no longer enter. DENSE: the whole leg is replanned.
     * 40 is what player navigation uses ({@code NavigationService.RECHECK_TICKS}).
     */
    int recheckTicks = NavigationService.RECHECK_TICKS;
    /** No road route: wait and ask again (true), or walk straight-line hops to the goal (false, the old fallback). */
    boolean noRouteWait = true;
    boolean openDoors = true;
    boolean collidable = false;
    /** NAV-12 travel bubble: NPCs keep the chunks they walk through loaded (Citizens KEEP_CHUNK_LOADED + K&K tickets). */
    boolean keepLoaded = false;
    /** Trail: blocks of the route handed to Citizens at once. */
    double stretchBlocks = 32;
    /** Trail: spacing of the waypoints along the K&K route. */
    static final double TRAIL_STEP = 1.5;
    final SpikeChunkBubble bubble;
    final SpikeRunLog runLog;
    /** Progress watchdog: an NPC on a trail stretch that moved less than 1 block in this many ticks is stuck. */
    static final int NO_PROGRESS_TICKS = 60;
    /** Ticks between the status lines in the run file. */
    static final int STATUS_TICKS = 1200;

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
        this.routes = new SpikeRouteStore(plugin.getDataFolder(), logger);
        this.bubble = new SpikeChunkBubble(plugin);
        this.runLog = new SpikeRunLog(plugin);
        SpikeRouteStore.Route last = routes.current();
        from = last.from();
        to = last.to();
        subject = last.subject();
        if (from != null || to != null) {
            runLog.log("route restored: from " + SpikeRouteStore.describe(from) + " -> to "
                + SpikeRouteStore.describe(to));
        }
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
        // what player navigation re-checks its routes on: a gate changed state, a domain cache refresh, a siege change
        unsubscribeRules = navigation.spikeOnRulesChanged(why -> {
            if (Bukkit.isPrimaryThread()) {
                onRulesChanged(why);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> onRulesChanged(why));
            }
        });
        runLog.log("in-memory Citizens registry '" + REGISTRY + "' created");
    }

    public void shutdown() {
        clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
        if (unsubscribeRules != null) {
            unsubscribeRules.run();
            unsubscribeRules = null;
        }
        if (registry != null) {
            CitizensAPI.removeNamedNPCRegistry(REGISTRY);
            registry = null;
        }
    }

    int count() {
        return npcs.size();
    }

    /** Turn the travel bubble on or off for every spike NPC, at once. */
    void setKeepLoaded(boolean on) {
        keepLoaded = on;
        for (SpikeNpc s : npcs.values()) {
            s.npc.data().set(NPC.Metadata.KEEP_CHUNK_LOADED, on);
            if (!on) {
                bubble.releaseAll(s.npc.getId(), Bukkit::getWorld);
            }
        }
    }

    /** Zero the counters and re-arm the first-20 stuck and trespass log lines (keeps the NPCs). */
    void resetCounters() {
        metrics.reset();
        loggedLegFailures = 0;
        loggedTrespasses = 0;
    }

    // ==================== spawn / clear ====================

    int spawn(int n, Mode mode, EntityType type) {
        ensureStarted();
        int made = 0;
        for (int i = 0; i < n; i++) {
            Location at = spawnSpot();
            NPC npc = registry.createNPC(type, "spike-" + (npcs.size() + 1));
            npc.data().set(NPC.Metadata.SHOULD_SAVE, false);
            // Citizens appends its own DoorExaminer after the defaults when this is on, and "first PASSABLE
            // wins" would let it open a door K&K denies: doors go through SpikeExaminer instead.
            npc.data().set(NPC.Metadata.PATHFINDER_OPEN_DOORS, false);
            npc.data().set(NPC.Metadata.COLLIDABLE, collidable);
            // NAV-12: Citizens' ChunkTicketTrait force-loads the chunk the NPC stands in and moves it as it walks
            npc.data().set(NPC.Metadata.KEEP_CHUNK_LOADED, keepLoaded);
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
                // Citizens' A* only finishes on the exact target block by default (path-distance-margin 0); a hop
                // target is a point on a road or a snapped floor, so accept arriving within 1.5 blocks of it
                .pathDistanceMargin(1.5)
                .range((float) Math.max(params.range(), hopSpacing + captureMargin + 8));
            SpikeNpc s = new SpikeNpc(npc, mode);
            s.tripStartNanos = System.nanoTime();
            npcs.put(npc.getId(), s);
            metrics.spawned++;
            made++;
        }
        return made;
    }

    /**
     * A free standing spot near {@code from}: a random point 1.5–4 blocks away, moved to the nearest floor within
     * 3 blocks up or down with 2 free blocks above; after 12 misses, {@code from} itself (inside a castle a fixed
     * height around {@code from} is often in a wall or in the air, and Citizens cannot plan from there).
     */
    private Location spawnSpot() {
        World world = from.getWorld();
        int fromFloor = from.getBlockY() - 1;
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double radius = 1.5 + ThreadLocalRandom.current().nextDouble(2.5);
            int x = (int) Math.floor(from.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(from.getZ() + Math.sin(angle) * radius);
            for (int d = 0; d <= 3; d++) {
                for (int floorY : d == 0 ? new int[] {fromFloor} : new int[] {fromFloor + d, fromFloor - d}) {
                    if (world.getBlockAt(x, floorY, z).getType().isSolid()
                        && !org.bukkit.Tag.LEAVES.isTagged(world.getBlockAt(x, floorY, z).getType())
                        && world.getBlockAt(x, floorY + 1, z).isPassable()
                        && world.getBlockAt(x, floorY + 2, z).isPassable()) {
                        return new Location(world, x + 0.5, floorY + 1, z + 0.5);
                    }
                }
            }
        }
        metrics.fail("spawn: no free spot near from (spawned at from)");
        return from.clone();
    }

    void clear() {
        for (SpikeNpc s : npcs.values()) {
            s.generation++;
            s.npc.destroy();
        }
        bubble.releaseEverything(Bukkit::getWorld);
        runLog.endRun("clear");
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
        if (tick % TRESPASS_CHECK_TICKS == 0) {
            checkTrespass();
            sampleStray();
        }
        if (tick % STATUS_TICKS == 0 && !npcs.isEmpty()) {
            runLog.fileOnly(statusLine());
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
        if (s.mode == Mode.TRAIL && s.onTrail && s.npc.isSpawned() && tick % 20 == 0) {
            // Citizens reports no STUCK for waypoint lists: K&K watches progress itself
            Location at = s.npc.getEntity().getLocation();
            double[] now = {at.getX(), at.getY(), at.getZ()};
            if (s.progressAt == null || Math.abs(now[0] - s.progressAt[0]) + Math.abs(now[1] - s.progressAt[1])
                + Math.abs(now[2] - s.progressAt[2]) >= 1) {
                s.progressAt = now;
                s.lastProgressTick = tick;
            } else if (tick - s.lastProgressTick >= NO_PROGRESS_TICKS) {
                s.progressAt = null;
                metrics.stuck++;
                metrics.fail("trail: no progress for " + NO_PROGRESS_TICKS + " ticks");
                java.util.function.Consumer<String> out = loggedLegFailures++ < 20 ? runLog::log : runLog::fileOnly;
                double[] end = s.line != null && s.stretchEnd > 0 ? s.line.get(Math.min(s.line.size(), s.stretchEnd) - 1) : null;
                out.accept(String.format(Locale.ROOT, "%s no progress at %d %d %d -> stretch end floor %s",
                    s.npc.getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                    end == null ? "-" : String.format(Locale.ROOT, "%.0f %.0f %.0f", end[0], end[1], end[2])));
                if (++s.retries <= MAX_RETRIES) {
                    metrics.replans++;
                    recoverTrail(s);
                } else {
                    metrics.fail("stuck: " + MAX_RETRIES + " retries used");
                    s.retries = 0;
                    s.line = null;
                    s.hops.clear();
                    s.tail.clear();
                    s.onTrail = false;
                    navigator.cancelNavigation();
                    waitTicks(s, 100);
                }
                return;
            }
        }
        if (s.mode == Mode.TRAIL && s.onTrail && s.line != null && s.stretchEnd < s.line.size()
            && s.npc.isSpawned()) {
            // hand Citizens the next stretch shortly before the end of this one, so the NPC doesn't stop (NAV-14)
            double[] end = s.line.get(s.stretchEnd - 1);
            Location at = s.npc.getEntity().getLocation();
            if (Math.hypot(at.getX() - end[0], at.getZ() - end[2]) < 4) {
                sendStretch(s, s.stretchEnd, false);
            }
        }
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
            return;
        }
        if (s.mode == Mode.HOP && recheckTicks > 0 && tick - s.lastRecheckTick >= recheckTicks) {
            // as player navigation's route re-check: the leg's access was decided when it was planned, so check the
            // way ahead against the rules as they are now and replan only when it enters a region now denied
            s.lastRecheckTick = tick;
            Player player = subject == null ? null : Bukkit.getPlayer(subject);
            if (player != null && s.npc.isSpawned()) {
                deniedAhead(s, player).ifPresent(region -> {
                    metrics.recheckReplans++;
                    metrics.fail("recheck: way ahead enters " + region + " (replanned)");
                    replan(s);
                });
            }
        }
    }

    /**
     * The first region on the NPC's way ahead (the rest of Citizens' path, then the hops still to walk) that it may
     * not enter by the current rules, judged from the regions it stands in now. Main thread, domain cache only.
     */
    private Optional<String> deniedAhead(SpikeNpc s, Player player) {
        World world = s.npc.getEntity().getWorld();
        Location feet = s.npc.getEntity().getLocation();
        Set<String> here = navigation.spikeRegionsAt(world, floorOf(feet));
        java.util.function.Predicate<String> mayEnter = navigation.spikeMayEnter(player, world, here);
        Map<String, Boolean> verdicts = new java.util.HashMap<>();
        List<double[]> ahead = new ArrayList<>();
        PathStrategy strategy = s.npc.getNavigator().getPathStrategy();
        Iterable<Vector> path = strategy == null ? null : strategy.getPath();
        if (path != null) {
            // Citizens' path from its start: skip what lies behind the nearest point to the NPC
            List<Vector> points = new ArrayList<>();
            path.forEach(points::add);
            int nearest = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                double d = points.get(i).distanceSquared(feet.toVector());
                if (d < best) {
                    best = d;
                    nearest = i;
                }
            }
            for (Vector p : points.subList(nearest, points.size())) {
                ahead.add(new double[] {p.getX() + 0.5, p.getBlockY() - 1, p.getZ() + 0.5}); // feet → floor
            }
        }
        ahead.addAll(s.hops);
        if (s.line != null && s.mode == Mode.TRAIL) {
            ahead.addAll(s.line.subList(Math.min(s.lineIndex, s.line.size()),
                Math.min(s.line.size(), s.lineIndex + 300)));
        }
        ahead.addAll(s.tail);
        for (double[] point : ahead) {
            for (String region : navigation.spikeRegionsAt(world, point)) {
                if (!here.contains(region) && !verdicts.computeIfAbsent(region, mayEnter::test)) {
                    return Optional.of(region);
                }
            }
        }
        return Optional.empty();
    }

    /** Drop the leg and the road route and plan again from where the NPC stands (the rules changed). */
    private void replan(SpikeNpc s) {
        s.generation++;
        s.hops.clear();
        s.tail.clear();
        s.line = null;
        s.lineIndex = s.stretchEnd = 0;
        s.onTrail = false;
        s.retries = 0;
        queue.remove(s);
        enqueue(s); // QUEUED first, so the PLUGIN cancel below is not taken for a stuck NPC
        if (s.npc.isSpawned() && s.npc.getNavigator().isNavigating()) {
            s.npc.getNavigator().cancelNavigation();
        }
    }

    /** A gate changed state or availability changed (player navigation's re-check triggers): every NPC replans. */
    private void onRulesChanged(String why) {
        int n = 0;
        for (SpikeNpc s : npcs.values()) {
            if (s.state == State.PAUSED || !s.npc.isSpawned()) {
                continue;
            }
            replan(s);
            n++;
        }
        if (n > 0) {
            metrics.eventReplans += n;
            runLog.log("rules changed (" + why + "): " + n + " NPCs replan");
        }
    }

    /**
     * The trespass counter: every {@link #TRESPASS_CHECK_TICKS} ticks, each NPC that stands in a region it did not
     * stand in at the last check has entered it; that entry is checked against the current entry rule, judged from
     * the regions it came from. Independent of the planner and the examiner: it watches where NPCs really are.
     */
    private void checkTrespass() {
        Player player = subject == null ? null : Bukkit.getPlayer(subject);
        if (player == null) {
            return;
        }
        for (SpikeNpc s : npcs.values()) {
            if (!s.npc.isSpawned()) {
                continue;
            }
            Location feet = s.npc.getEntity().getLocation();
            World world = feet.getWorld();
            Set<String> now = navigation.spikeRegionsAt(world, floorOf(feet));
            Set<String> before = s.regions;
            s.regions = now;
            if (before == null) {
                continue; // first look at this NPC: nothing to compare with
            }
            java.util.function.Predicate<String> mayEnter = null;
            for (String region : now) {
                if (before.contains(region)) {
                    continue;
                }
                if (mayEnter == null) {
                    mayEnter = navigation.spikeMayEnter(player, world, before);
                }
                if (!mayEnter.test(region)) {
                    metrics.trespasses++;
                    metrics.fail("TRESPASS into " + region);
                    {
                        java.util.function.Consumer<String> out = loggedTrespasses++ < 20 ? runLog::log : runLog::fileOnly;
                        out.accept(String.format(Locale.ROOT, "TRESPASS %s entered %s at %d %d %d (state %s)",
                            s.npc.getName(), region, feet.getBlockX(), feet.getBlockY(), feet.getBlockZ(), s.state));
                    }
                }
            }
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
        } else if (s.mode == Mode.TRAIL) {
            planTrail(s, player, generation);
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

    /**
     * The walk preparer's capture and access for one leg, then {@code then} on the main thread. With the travel
     * bubble the box's chunks are loaded (and held) first, so the capture never meets an unloaded chunk.
     */
    private void prepareLeg(SpikeNpc s, Player player, int generation, double[] target,
                            java.util.function.BiConsumer<WalkRequest, WalkBox> then) {
        if (keepLoaded) {
            Location feetLoc = s.npc.getEntity().getLocation();
            World world = feetLoc.getWorld();
            WalkBox box = WalkBox.around(world.getName(), feetLoc.getX(), feetLoc.getY(), feetLoc.getZ(), target[0],
                target[1], target[2], captureMargin, world.getMinHeight(), world.getMaxHeight());
            if (box.chunkCount() <= net.knightsandkings.knk.paper.navigation.walk.WalkSnapshotService.MAX_CHUNKS_PER_REQUEST) {
                List<int[]> chunks = new ArrayList<>();
                for (int cx = box.minChunkX(); cx <= box.maxChunkX(); cx++) {
                    for (int cz = box.minChunkZ(); cz <= box.maxChunkZ(); cz++) {
                        chunks.add(new int[] {cx, cz});
                    }
                }
                bubble.holdOnly(s.npc.getId(), world, chunks).whenComplete((ok, error) -> {
                    if (!current(s, generation)) {
                        done(s);
                        return;
                    }
                    if (error != null || !Boolean.TRUE.equals(ok)) {
                        metrics.fail("bubble: a chunk of the leg is not generated");
                    }
                    capture(s, player, generation, target, then);
                });
                return;
            }
        }
        capture(s, player, generation, target, then);
    }

    private void capture(SpikeNpc s, Player player, int generation, double[] target,
                         java.util.function.BiConsumer<WalkRequest, WalkBox> then) {
        Location feetLoc = s.npc.getEntity().getLocation();
        double[] feet = {feetLoc.getX(), feetLoc.getY(), feetLoc.getZ()};
        WalkGoal goal = WalkGoal.within(target[0], target[1], target[2], 1.5);
        World world = feetLoc.getWorld();
        WalkBox box = WalkBox.around(world.getName(), feet[0], feet[1], feet[2], target[0], target[1], target[2],
            captureMargin, world.getMinHeight(), world.getMaxHeight());
        // the NPC is the mover: the regions it stands in decide the domain exit rules, not where the subject is
        Set<String> inside = navigation.spikeRegionsAt(world, floorOf(feetLoc));
        CompletableFuture<Supplier<WalkRequest>> prepared =
            walk.preparer() instanceof net.knightsandkings.knk.paper.navigation.walk.WalkLegPreparer leg
                ? leg.prepare(player, inside, feet, target, goal, null)
                : walk.preparer().prepare(player, feet, target, goal, null);
        prepared.whenComplete((supplier, error) -> {
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
                        && !org.bukkit.Tag.LEAVES.isTagged(world.getBlockAt(x, floorY, z).getType())
                    && world.getBlockAt(x, floorY + 1, z).isPassable()
                    && world.getBlockAt(x, floorY + 2, z).isPassable()) {
                    return new double[] {hop[0], floorY, hop[2]};
                }
            }
        }
        return hop;
    }

    /** Citizens' own cost-only examiner from AStarPlanner (an anonymous class). */
    static boolean isCitizensCostOnly(BlockExaminer e) {
        return e.getClass().getName().startsWith("net.citizensnpcs.npc.ai.AStarNavigationStrategy$");
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
            boolean routerSaysNo = error == null && road != null && !road.found() && road.reason() != null
                && road.reason().startsWith("router:");
            if (routerSaysNo && noRouteWait) {
                // the router found no way the rules allow (a closed gate, a denied domain): wait where the NPC is and
                // ask again later or when the rules change - no improvising across the terrain
                done(s);
                metrics.fail("no road route: " + road.reason() + " (waiting)");
                waitTicks(s, NO_ROUTE_WAIT_TICKS);
                return;
            }
            if (error != null || road == null || !road.found()) {
                // no road: straight-line hops, so each Citizens leg (and its capture box) stays short
                metrics.fail("no road route: " + (road == null ? "error" : road.reason()) + " (straight-line hops)");
                s.line = null;
                World world = feet.getWorld();
                for (double[] hop : resample(List.of(feetFloor, goalFloor), hopSpacing)) {
                    s.hops.add(snapToFloor(world, hop));
                }
            } else if (s.mode == Mode.TRAIL) {
                // NAV-15: follow the K&K road line (the follow trail) as waypoints; A* only for the off-road ends
                World world = feet.getWorld();
                s.line = resampleKeepingCorners(road.line(), TRAIL_STEP);
                s.lineIndex = s.stretchEnd = 0;
                s.bubbleFor = -1;
                double[] roadStart = road.line().get(0);
                if (Math.hypot(roadStart[0] - feetFloor[0], roadStart[2] - feetFloor[2]) > 3) {
                    s.hops.add(snapToFloor(world, roadStart));
                }
                double[] roadEnd = road.line().get(road.line().size() - 1);
                s.tail.clear();
                if (Math.hypot(roadEnd[0] - goalFloor[0], roadEnd[2] - goalFloor[2]) > 2) {
                    s.tail.add(goalFloor);
                }
                if (s.hops.isEmpty()) {
                    done(s);
                    sendStretch(s, 0, true);
                } else {
                    planHop(s, player, generation);
                }
                return;
            } else {
                s.line = resample(road.line(), TRAIL_STEP); // only for the stray-from-route metric
                // resampled points between road vertices have an interpolated height (wrong on stairs and slopes)
                World roadWorld = feet.getWorld();
                for (double[] hop : resample(road.line(), hopSpacing)) {
                    s.hops.add(snapToFloor(roadWorld, hop));
                }
                // the off-road stretch from the road's end to the goal: short floor-snapped hops as well
                double[] roadEnd = road.line().get(road.line().size() - 1);
                World world = feet.getWorld();
                for (double[] hop : resample(List.of(roadEnd, goalFloor), hopSpacing)) {
                    s.hops.add(snapToFloor(world, hop));
                }
            }
            s.hops.addLast(goalFloor);
            planHop(s, player, generation);
        });
    }

    /** As {@link #resample}, plus every vertex of the line, so the trail turns exactly at the road's corners. */
    static List<double[]> resampleKeepingCorners(List<double[]> line, double spacing) {
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i < line.size(); i++) {
            List<double[]> piece = resample(List.of(line.get(i - 1), line.get(i)), spacing);
            if (out.isEmpty()) {
                out.add(line.get(0));
            }
            out.addAll(piece);
        }
        return out;
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
            // diagnostics: K&K blocking the NPC's own cell or the hop's target makes the A* fail before it starts
            Location here = s.npc.getEntity().getLocation();
            request.access().denyReason(here.getBlockX(), here.getBlockY(), here.getBlockZ())
                .ifPresent(reason -> metrics.fail("K&K blocks the NPC's own cell: " + reason));
            request.access().denyReason(dest.getBlockX(), dest.getBlockY(), dest.getBlockZ())
                .ifPresent(reason -> metrics.fail("K&K blocks the hop target: " + reason));
            metrics.planMs.add((System.nanoTime() - s.queuedAtNanos) / 1e6);
            s.npc.getNavigator().setTarget(dest);
            NavigatorParameters local = s.npc.getNavigator().getLocalParameters();
            BlockExaminer[] used = local.examiners();
            boolean leak = false;
            for (BlockExaminer e : used) {
                // AStarPlanner adds an anonymous avoid-water cost examiner (isPassable IGNORE, canStandAt IGNORE):
                // it cannot overrule the K&K veto, so it is not a leak and stays
                if (!(e instanceof SpikeExaminer) && !isCitizensCostOnly(e)) {
                    leak = true;
                }
            }
            if (leak) {
                // Citizens added an examiner after ours (doors, falling, bounding boxes, swimming): "first PASSABLE
                // wins" would let it overrule the K&K veto. Name it, then strip it: the sync planner reads the
                // examiners when it searches (on the next ticks), so this still takes effect.
                metrics.examinerLeaks++;
                StringBuilder names = new StringBuilder();
                SpikeExaminer ours = null;
                List<BlockExaminer> keep = new ArrayList<>();
                for (BlockExaminer e : used) {
                    if (e instanceof SpikeExaminer spike) {
                        ours = spike;
                    } else if (isCitizensCostOnly(e)) {
                        keep.add(e);
                    } else {
                        names.append(names.length() == 0 ? "" : "+").append(e.getClass().getSimpleName());
                    }
                }
                metrics.fail("examiner leak (stripped): " + names);
                local.clearExaminers();
                if (ours != null) {
                    local.examiner(ours);
                }
                keep.forEach(local::examiner);
            }
            s.targetSetNanos = System.nanoTime();
            s.citizensPathSeen = false;
            s.state = State.MOVING;
        });
    }

    // ==================== trail (NAV-15) ====================

    private void planTrail(SpikeNpc s, Player player, int generation) {
        if (!s.hops.isEmpty()) {
            planHop(s, player, generation); // an A* leg: to the road, a stuck recovery, or the tail
        } else if (s.line != null && s.lineIndex < s.line.size()) {
            done(s);
            sendStretch(s, s.lineIndex, true);
        } else if (!s.tail.isEmpty()) {
            s.hops.addAll(s.tail);
            s.tail.clear();
            planHop(s, player, generation);
        } else {
            planRoad(s, player, generation);
        }
    }

    /**
     * Hand Citizens the trail from point {@code start} for {@link #stretchBlocks} blocks as a waypoint list (no A*:
     * NAV-02's recheck keeps the rules binding). With the bubble, the next stretch's chunks are requested now.
     * {@code required}: the NPC needs it now (else it is the early hand-over and may be skipped).
     */
    private void sendStretch(SpikeNpc s, int start, boolean required) {
        if (s.line == null || start >= s.line.size() || !s.npc.isSpawned()) {
            return;
        }
        World world = s.npc.getEntity().getWorld();
        int perStretch = Math.max(4, (int) Math.round(stretchBlocks / TRAIL_STEP));
        int end = Math.min(s.line.size(), start + perStretch);
        if (keepLoaded && s.bubbleFor != start) {
            s.bubbleFor = start;
            int nextEnd = Math.min(s.line.size(), end + perStretch);
            bubble.holdOnly(s.npc.getId(), world, chunksOf(s.line.subList(Math.max(0, start - 2), nextEnd)));
        }
        for (double[] p : s.line.subList(start, end)) {
            if (!world.isChunkLoaded((int) Math.floor(p[0]) >> 4, (int) Math.floor(p[2]) >> 4)) {
                if (required) {
                    metrics.fail(keepLoaded ? "trail: stretch loading (bubble)" : "trail: stretch reaches an unloaded chunk");
                    s.onTrail = false;
                    s.lineIndex = start;
                    waitTicks(s, keepLoaded ? 10 : 100);
                }
                return;
            }
        }
        List<Vector> points = new ArrayList<>();
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (double[] p : s.line.subList(start, end)) {
            double[] f = snapToFloor(world, p);
            int bx = (int) Math.floor(f[0]);
            int by = (int) Math.round(f[1]);
            int bz = (int) Math.floor(f[2]);
            if (bx == lastX && by == lastY && bz == lastZ) {
                continue;
            }
            points.add(new Vector(bx + 0.5, by + 1, bz + 0.5)); // floor → feet, block centre
            lastX = bx;
            lastY = by;
            lastZ = bz;
        }
        if (points.isEmpty()) {
            s.lineIndex = s.stretchEnd = end;
            completeTrail(s);
            return;
        }
        s.lineIndex = start;
        s.stretchEnd = end;
        s.onTrail = true;
        if (s.progressAt == null) {
            s.lastProgressTick = tick;
        }
        s.state = State.MOVING; // QUEUED first would make the replaced target's cancel look like a stuck NPC
        s.npc.getNavigator().setTarget(points);
        s.targetSetNanos = System.nanoTime();
        metrics.hops++;
    }

    /** A trail stretch or an A* leg of a trail NPC is done: next stretch, the tail, or the trip's end. */
    private void completeTrail(SpikeNpc s) {
        if (!s.onTrail && !s.hops.isEmpty()) {
            s.hops.pollFirst(); // the A* leg that just finished
        }
        if (!s.hops.isEmpty()) {
            enqueue(s);
        } else if (s.line != null && (s.onTrail ? s.stretchEnd : s.lineIndex) < s.line.size()) {
            sendStretch(s, s.onTrail ? s.stretchEnd : s.lineIndex, true);
        } else if (!s.tail.isEmpty()) {
            s.onTrail = false;
            s.line = null;
            enqueue(s); // planTrail moves the tail into the hops
        } else {
            s.onTrail = false;
            s.line = null;
            metrics.trips++;
            metrics.tripSeconds.add((System.nanoTime() - s.tripStartNanos) / 1e9);
            s.tripStartNanos = System.nanoTime();
            s.towardsTo = !s.towardsTo;
            waitTicks(s, 20);
        }
    }

    /** Stuck on the trail: one Citizens A* leg to a point a few blocks further along, then back on the line. */
    private void recoverTrail(SpikeNpc s) {
        Location at = s.npc.getEntity().getLocation();
        int nearest = s.lineIndex;
        double best = Double.MAX_VALUE;
        for (int i = s.lineIndex; i < Math.min(s.line.size(), s.stretchEnd + 4); i++) {
            double[] p = s.line.get(i);
            double d = Math.hypot(p[0] - at.getX(), p[2] - at.getZ());
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        int target = Math.min(s.line.size() - 1, nearest + 4);
        s.hops.clear();
        s.hops.add(snapToFloor(at.getWorld(), s.line.get(target)));
        s.lineIndex = s.stretchEnd = target + 1;
        s.onTrail = false;
        metrics.fail("trail: stuck, A* recovery leg");
        enqueue(s);
    }

    private static List<int[]> chunksOf(List<double[]> points) {
        Set<Long> seen = new java.util.HashSet<>();
        List<int[]> out = new ArrayList<>();
        for (double[] p : points) {
            int cx = (int) Math.floor(p[0]) >> 4;
            int cz = (int) Math.floor(p[2]) >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    // the NPC's own chunk and its neighbours along the line, so it never walks into an unloaded one
                    if (seen.add(SpikeChunkBubble.key(cx + dx, cz + dz))) {
                        out.add(new int[] {cx + dx, cz + dz});
                    }
                }
            }
        }
        return out;
    }

    /** Every second: how far each moving NPC is from the K&K road line (horizontal blocks). */
    private void sampleStray() {
        for (SpikeNpc s : npcs.values()) {
            if (s.state != State.MOVING || s.line == null || s.line.size() < 2 || !s.npc.isSpawned()
                || s.mode == Mode.TRAIL && !s.onTrail) {
                continue; // off-road legs (to and from the road) are not strays
            }
            Location at = s.npc.getEntity().getLocation();
            double best = Double.MAX_VALUE;
            for (int i = 1; i < s.line.size(); i++) {
                best = Math.min(best, segmentDistance(s.line.get(i - 1), s.line.get(i), at.getX(), at.getZ()));
            }
            metrics.strayBlocks.add(best);
            metrics.straySamples++;
            if (best > 2) {
                metrics.strayOver2++;
            }
        }
    }

    static double segmentDistance(double[] a, double[] b, double x, double z) {
        double dx = b[0] - a[0];
        double dz = b[2] - a[2];
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (z - a[2]) * dz) / len2));
        return Math.hypot(a[0] + t * dx - x, a[2] + t * dz - z);
    }

    // ==================== events ====================

    private SpikeNpc ours(NPC npc) {
        return npc != null && npc.getOwningRegistry() == registry ? npcs.get(npc.getId()) : null;
    }

    @EventHandler
    public void onComplete(NavigationCompleteEvent event) {
        long start = System.nanoTime();
        SpikeNpc s = ours(event.getNPC());
        if (s != null && s.state == State.MOVING && s.mode == Mode.TRAIL) {
            s.retries = 0;
            completeTrail(s);
        } else if (s != null && s.state == State.MOVING) {
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
            if (s.npc.isSpawned()) {
                // the first 20 also on the console; every one in the run file
                java.util.function.Consumer<String> out = loggedLegFailures++ < 20 ? runLog::log : runLog::fileOnly;
                Location at = s.npc.getEntity().getLocation();
                double[] hop = s.mode == Mode.TRAIL && s.onTrail && s.line != null && s.stretchEnd > 0
                    ? s.line.get(Math.min(s.line.size(), s.stretchEnd) - 1) : s.hops.peekFirst();
                out.accept(String.format(Locale.ROOT, "%s %s at %d %d %d -> %s floor %s",
                    s.npc.getName(), noPath ? "no path" : "stuck", at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                    s.mode == Mode.TRAIL && s.onTrail ? "stretch end" : "hop",
                    hop == null ? "-" : String.format(Locale.ROOT, "%.0f %.0f %.0f", hop[0], hop[1], hop[2])));
            }
            if (s.mode == Mode.TRAIL && s.onTrail && ++s.retries <= MAX_RETRIES) {
                metrics.replans++;
                recoverTrail(s);
            } else if (s.mode == Mode.TRAIL && s.onTrail) {
                metrics.fail("stuck: " + MAX_RETRIES + " retries used");
                s.retries = 0;
                s.line = null;
                s.hops.clear();
                s.tail.clear();
                s.onTrail = false;
                waitTicks(s, 100);
            } else if (++s.retries <= MAX_RETRIES) {
                metrics.replans++;
                enqueue(s); // HOP keeps its hops: retry towards the same hop from here (DESIGN §6 bounded retry)
            } else {
                metrics.fail("stuck: " + MAX_RETRIES + " retries used");
                s.retries = 0;
                s.hops.clear();
                s.line = null;
                s.tail.clear();
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
            bubble.releaseAll(s.npc.getId(), Bukkit::getWorld);
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

    /**
     * Why the access subject makes rule tests meaningless, or null: {@code knk.region.bypass} opens every domain,
     * and a gate admin passes every closed gate (GatePassThroughRules), so the router plans through them.
     */
    String subjectWarning() {
        Player who = subject == null ? null : Bukkit.getPlayer(subject);
        if (who == null) {
            return null;
        }
        List<String> why = new ArrayList<>();
        if (who.hasPermission("knk.region.bypass")) {
            why.add("knk.region.bypass (domain entry/exit rules are open)");
        }
        if (net.knightsandkings.knk.paper.gates.GatePassThroughRules.isAdmin(who)) {
            why.add("gate admin (passes every closed gate)");
        }
        return why.isEmpty() ? null : "access subject " + who.getName() + " has " + String.join(" and ", why)
            + ": gate and access tests are meaningless; /knk npcspike set subject <player>";
    }

    /** The lines that describe a run's setup (route, subject, settings), for the run file's header. */
    List<String> setupLines() {
        List<String> lines = new ArrayList<>();
        lines.add("route: from " + SpikeRouteStore.describe(from) + " -> to " + SpikeRouteStore.describe(to));
        Player who = subject == null ? null : Bukkit.getPlayer(subject);
        lines.add("access subject: " + (who == null ? "offline/none" : who.getName())
            + (subjectWarning() == null ? "" : " - WARNING " + subjectWarning()));
        lines.add(String.format(Locale.ROOT, "settings: pathfinder %s, max-in-flight %d, hop %.0f, stretch %.0f, stationary %d, recheck %d, noroute %s, keeploaded %s, doors %s, collidable %s, baseline %.2f ms",
            pathfinderType, maxInFlight, hopSpacing, stretchBlocks, stationaryTicks, recheckTicks,
            noRouteWait ? "wait" : "line", keepLoaded ? "on" : "off", openDoors, collidable, metrics.baselineMs));
        return lines;
    }

    /** One compact line for the run file every minute. */
    private String statusLine() {
        Map<State, Integer> states = new java.util.EnumMap<>(State.class);
        int spawned = 0;
        for (SpikeNpc s : npcs.values()) {
            states.merge(s.state, 1, Integer::sum);
            if (s.npc.isSpawned()) {
                spawned++;
            }
        }
        return String.format(Locale.ROOT,
            "status: NPCs %d (spawned %d) %s | tick mean %.2f ms p95 %.2f | TPS %.2f | trips %d, hops %d, stuck %d, "
                + "trespasses %d, pauses %d, resumes %d | stray p95 %.2f | bubble NPCs %d, chunks %d",
            npcs.size(), spawned, states, metrics.tickMs.mean(), metrics.tickMs.percentile(95), Bukkit.getTPS()[0],
            metrics.trips, metrics.hops, metrics.stuck, metrics.trespasses, metrics.pausedChunkUnload, metrics.resumed,
            metrics.strayBlocks.percentile(95), bubble.npcsWithBubble(), bubble.chunksHeld());
    }

    /** Gate commands go into the run file, so their time sits next to the NPCs' reaction. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/gate ") || lower.startsWith("/knk npcspike")) {
            runLog.fileOnly(event.getPlayer().getName() + " issued " + message);
        }
    }

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
            "trips %d, hops %d, stuck %d, replans %d, recheck replans %d, event replans %d, chunk-unload pauses %d, resumes %d",
            metrics.trips, metrics.hops, metrics.stuck, metrics.replans, metrics.recheckReplans, metrics.eventReplans,
            metrics.pausedChunkUnload, metrics.resumed));
        lines.add(String.format(Locale.ROOT,
            "TRESPASSES %d (NPC entered a region the current rules deny) | foreign examiners stripped %d",
            metrics.trespasses, metrics.examinerLeaks));
        lines.add(String.format(Locale.ROOT, "examiner: %d calls, %.2f µs mean, %d blocked by K&K rules", calls,
            calls == 0 ? 0.0 : SpikeExaminer.NANOS.sum() / 1000.0 / calls, SpikeExaminer.BLOCKED.sum()));
        lines.add("failures: " + (metrics.failures.isEmpty() ? "none" : metrics.failures.toString()));
        lines.add(String.format(Locale.ROOT, "stray from the K&K road line: %s | over 2 blocks %.1f%% of %d samples",
            metrics.strayBlocks.describe("blocks"),
            metrics.straySamples == 0 ? 0.0 : 100.0 * metrics.strayOver2 / metrics.straySamples, metrics.straySamples));
        lines.add(String.format(Locale.ROOT,
            "bubble: keeploaded %s, NPCs with bubble %d, chunks held %d, loads %d, not generated %d",
            keepLoaded ? "on" : "off", bubble.npcsWithBubble(), bubble.chunksHeld(), bubble.loadsRequested,
            bubble.notGenerated));
        lines.add("route: from " + SpikeRouteStore.describe(from) + " -> to " + SpikeRouteStore.describe(to));
        Player who = subject == null ? null : Bukkit.getPlayer(subject);
        lines.add("access subject: " + (who == null ? (subject == null ? "none (run from/to)" : "offline")
            : who.getName() + (subjectWarning() == null ? "" : " - WARNING " + subjectWarning())));
        lines.add(String.format(Locale.ROOT, "settings: modes %s, pathfinder %s, max-in-flight %d, hop %.0f, stretch %.0f, stationary %d, recheck %d, noroute %s, keeploaded %s, doors %s, collidable %s",
            modes(), pathfinderType, maxInFlight, hopSpacing, stretchBlocks, stationaryTicks, recheckTicks,
            noRouteWait ? "wait" : "line", keepLoaded ? "on" : "off", openDoors, collidable));
        return lines;
    }

    /** One CSV row for the benchmark issue. */
    String csv(String label) {
        int spawned = (int) npcs.values().stream().filter(s -> s.npc.isSpawned()).count();
        return String.format(Locale.ROOT, "%s,%d,%.2f,%.2f,%.2f,%.2f,%.3f,%.1f,%.1f,%.1f,%.1f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s,%s,%.2f,%.2f,%.1f,%d",
            label, spawned, metrics.tickMs.mean(), metrics.tickMs.percentile(95), Bukkit.getTPS()[0], metrics.baselineMs,
            Double.isNaN(metrics.baselineMs) || spawned == 0 ? Double.NaN : (metrics.tickMs.mean() - metrics.baselineMs) / spawned,
            metrics.spikeMainUs.mean(), metrics.planMs.percentile(50), metrics.planMs.percentile(95),
            metrics.citizensPathMs.percentile(95), metrics.trips, metrics.stuck, metrics.replans,
            metrics.failures.values().stream().mapToLong(Long::longValue).sum(), metrics.examinerLeaks,
            metrics.trespasses, metrics.recheckReplans, metrics.eventReplans, (int) hopSpacing,
            recheckTicks + "/" + (noRouteWait ? "wait" : "line"), modes(), keepLoaded ? "on" : "off",
            metrics.strayBlocks.percentile(50), metrics.strayBlocks.percentile(95),
            metrics.straySamples == 0 ? 0.0 : 100.0 * metrics.strayOver2 / metrics.straySamples, bubble.chunksHeld());
    }

    /** The modes of the spike NPCs present ("hop", "trail", "hop+trail"). */
    private String modes() {
        return npcs.values().stream().map(s -> s.mode.name().toLowerCase(Locale.ROOT)).distinct().sorted()
            .reduce((a, b) -> a + "+" + b).orElse("-");
    }

    void metricsCsvLog(String label) {
        runLog.csv(CSV_HEADER, csv(label));
    }

    static final String CSV_HEADER = "label,npcs,mspt_mean,mspt_p95,tps,baseline_ms,ms_per_npc,spike_us_tick,"
        + "plan_p50_ms,plan_p95_ms,citizens_path_p95_ms,trips,stuck,replans,failures,examiner_leaks,"
        + "trespasses,recheck_replans,event_replans,hop,recheck/noroute,mode,keeploaded,stray_p50,stray_p95,"
        + "stray_over2_pct,chunks_held";
}
