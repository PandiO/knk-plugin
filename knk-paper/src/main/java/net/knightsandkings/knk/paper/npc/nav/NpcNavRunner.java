package net.knightsandkings.knk.paper.npc.nav;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavWorld;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcMoverFactory;
import net.knightsandkings.knk.core.npc.nav.NpcNavState;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService;
import net.knightsandkings.knk.core.npc.nav.NpcSpawnQueue;
import net.knightsandkings.knk.core.npc.nav.RouteFollower;

/**
 * The admin test driver behind {@code /knk npc nav} (PandiO/knk-workspace#360), in place of the Citizens spike's
 * command: it stands in for behavior. NPCs are spawned a few per tick (NAV-13) near the route's start and walk the
 * route back and forth through {@link NpcNavigationService}; a failed order is given again after a pause. It keeps
 * the numbers the spike reported (trips, stuck by kind, tick cost per NPC against a baseline, distance from the
 * road line) and can trace one NPC tick by tick. Not a gameplay feature. Main thread.
 */
public final class NpcNavRunner {

    /** Ticks an NPC rests at an end before walking back. */
    static final int TURN_TICKS = 20;
    /** Ticks before a failed order is given again. */
    static final int FAILED_RETRY_TICKS = 100;
    /** Ticks sampled for the tick-time mean and percentile. */
    static final int TICK_WINDOW = 1200;
    static final int STRAY_SAMPLE_TICKS = 20;

    private static final class Walker {
        final NpcMover mover;
        final String profile;
        boolean patrol = true;
        boolean towardsTo = true;
        long tripStartNanos = System.nanoTime();
        long nextOrderTick = -1;
        NavPoint goal;

        Walker(NpcMover mover, String profile) {
            this.mover = mover;
            this.profile = profile;
        }
    }

    private final NpcMoverFactory factory;
    private final NpcNavigationService service;
    private final NavigationSettings settings;
    private final NpcNavigationMetrics metrics;
    private final NavWorld world;
    private final NpcRouteStore routes;
    private final NpcNavLog log;
    private final NpcSpawnQueue spawns;
    private final Function<NavPoint, List<String>> regionsOuterToInner;
    private final Map<Integer, Walker> walkers = new LinkedHashMap<>();
    private final Map<String, Integer> failures = new TreeMap<>();
    private final Map<Event, Long> totalsAtReset = new EnumMap<>(Event.class);
    private long tick;
    private int nextName = 1;
    private NavPoint from;
    private NavPoint to;

    // numbers
    private final double[] tickMs = new double[TICK_WINDOW];
    private int tickSamples;
    private final double[] serviceUs = new double[TICK_WINDOW];
    private int serviceSamples;
    private double baselineMs = Double.NaN;
    private long trips;
    private double tripSecondsSum;
    private long blockedReports;
    private long straySamples;
    private long strayOver2;
    private double strayMax;
    private double straySum;

    // trace
    private int traceId = -1;
    private long traceUntil;
    private Path traceFile;
    private NavPoint tracePrev;
    private boolean tracePrevGround;

    /**
     * @param spawnsPerTick       NPCs spawned per tick at most
     * @param regionsOuterToInner the regions at a feet position, outermost first, for the settings' region layer
     */
    public NpcNavRunner(NpcMoverFactory factory, NpcNavigationService service, NavigationSettings settings,
                        NpcNavigationMetrics metrics, NavWorld world, NpcRouteStore routes, NpcNavLog log,
                        int spawnsPerTick, Function<NavPoint, List<String>> regionsOuterToInner) {
        this.factory = factory;
        this.service = service;
        this.settings = settings;
        this.metrics = metrics;
        this.world = world;
        this.routes = routes;
        this.log = log;
        this.spawns = new NpcSpawnQueue(spawnsPerTick);
        this.regionsOuterToInner = regionsOuterToInner;
        NpcRouteStore.Route last = routes.current();
        this.from = last.from();
        this.to = last.to();
        resetCounters();
    }

    public NavPoint from() {
        return from;
    }

    public NavPoint to() {
        return to;
    }

    public int count() {
        return walkers.size();
    }

    public int pendingSpawns() {
        return spawns.pending();
    }

    public NpcRouteStore routes() {
        return routes;
    }

    /** Sets an end of the route (feet position) and saves it as the current route. */
    public void setEnd(boolean fromEnd, NavPoint feet) {
        if (fromEnd) {
            from = feet;
        } else {
            to = feet;
        }
        routes.saveCurrent(new NpcRouteStore.Route(from, to));
        log.fileOnly("route " + (fromEnd ? "from" : "to") + " set at " + NpcRouteStore.describe(feet));
    }

    public void useRoute(NpcRouteStore.Route route) {
        from = route.from();
        to = route.to();
        routes.saveCurrent(route);
        log.fileOnly("route: from " + NpcRouteStore.describe(from) + " -> to " + NpcRouteStore.describe(to));
    }

    // ==================== spawn / goto / clear ====================

    /** Queues {@code n} NPCs with {@code profile}; they spawn a few per tick near the route's start and walk it. */
    public void spawn(int n, NpcAccessProfile profile) {
        if (walkers.isEmpty() && spawns.pending() == 0) {
            resetCounters(); // a spawn with no NPCs out starts a run
            log.rearm();
            log.fileOnly("run: " + n + " NPCs, profile " + profile.id() + ", route from " + NpcRouteStore.describe(from)
                + " -> to " + NpcRouteStore.describe(to));
        }
        for (int i = 0; i < n; i++) {
            spawns.add(() -> spawnOne(profile));
        }
    }

    private void spawnOne(NpcAccessProfile profile) {
        NavPoint at = spawnSpot();
        NavigationSettings.Scope scope = new NavigationSettings.Scope(at.world(), regionsOuterToInner.apply(at), null,
            null);
        NpcMover mover = factory.spawn("knk-nav-" + nextName++, at, new NpcMoverFactory.MoverOptions(
            settings.value(NavigationSettings.PATHFINDER, scope),
            settings.value(NavigationSettings.STATIONARY_TICKS, scope),
            settings.value(NavigationSettings.COLLIDABLE, scope)));
        service.register(mover, profile, NpcNavigationService.Settings.of(settings, scope));
        Walker walker = new Walker(mover, profile.id());
        walkers.put(mover.id(), walker);
        metrics.count(Event.SPAWNED);
        order(walker);
    }

    /**
     * A free standing spot near the route's start: a random point 1.5 to 4 blocks away on a floor within 3 blocks up
     * or down; after 12 misses the start itself (inside a castle a fixed height around the start is often in a wall).
     */
    private NavPoint spawnSpot() {
        int floorY = from.blockY() - 1;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble(Math.PI * 2);
            double radius = 1.5 + random.nextDouble(2.5);
            int x = (int) Math.floor(from.x() + Math.cos(angle) * radius);
            int z = (int) Math.floor(from.z() + Math.sin(angle) * radius);
            for (int d = 0; d <= 3; d++) {
                if (world.isStandableFloor(from.world(), x, floorY + d, z)) {
                    return new NavPoint(from.world(), x + 0.5, floorY + d + 1, z + 0.5);
                }
                if (d > 0 && world.isStandableFloor(from.world(), x, floorY - d, z)) {
                    return new NavPoint(from.world(), x + 0.5, floorY - d + 1, z + 0.5);
                }
            }
        }
        fail("spawn: no free spot near the start (spawned at the start)");
        return from;
    }

    private void order(Walker walker) {
        walker.nextOrderTick = -1;
        walker.goal = walker.patrol ? (walker.towardsTo ? to : from) : walker.goal;
        if (walker.goal == null) {
            return;
        }
        service.navigate(walker.mover.id(), walker.goal, result -> onResult(walker, result));
    }

    private void onResult(Walker walker, NavigationResult result) {
        switch (result) {
            case NavigationResult.Arrived arrived -> {
                trips++;
                tripSecondsSum += (System.nanoTime() - walker.tripStartNanos) / 1e9;
                walker.tripStartNanos = System.nanoTime();
                if (walker.patrol) {
                    walker.towardsTo = !walker.towardsTo;
                    walker.nextOrderTick = tick + TURN_TICKS;
                }
            }
            case NavigationResult.Blocked blocked -> {
                blockedReports++;
                fail("blocked: " + blocked.reason());
            }
            case NavigationResult.Failed failed -> {
                fail("failed: " + failed.reason().replaceAll("-?\\d+ -?\\d+ -?\\d+", "<x y z>"));
                log.event(walker.mover.name() + " order failed: " + failed.reason());
                walker.nextOrderTick = tick + FAILED_RETRY_TICKS; // behavior's part: here, simply try again later
            }
            case NavigationResult.Cancelled cancelled -> { }
        }
    }

    private void fail(String what) {
        failures.merge(what, 1, Integer::sum);
    }

    /** Every NPC stops walking the route and goes to {@code feet} once; returns how many. */
    public int goTo(NavPoint feet) {
        for (Walker walker : walkers.values()) {
            walker.patrol = false;
            walker.goal = feet;
            walker.tripStartNanos = System.nanoTime();
            order(walker);
        }
        return walkers.size();
    }

    /** Every NPC walks the route back and forth again. */
    public int patrol() {
        for (Walker walker : walkers.values()) {
            walker.patrol = true;
            order(walker);
        }
        return walkers.size();
    }

    /** Removes every test NPC and drops the spawns still queued; returns how many NPCs were removed. */
    public int clear() {
        spawns.clear();
        int n = walkers.size();
        for (Walker walker : List.copyOf(walkers.values())) {
            service.unregister(walker.mover.id());
            walker.mover.remove();
            metrics.count(Event.REMOVED);
        }
        walkers.clear();
        traceId = -1;
        if (n > 0) {
            log.fileOnly("clear: removed " + n + " NPCs");
        }
        return n;
    }

    // ==================== tick ====================

    /** Once per server tick, before the service's own tick. */
    public void tick() {
        tick++;
        try {
            spawns.tick();
        } catch (RuntimeException e) {
            fail("spawn refused: " + e.getMessage());
        }
        for (Walker walker : walkers.values()) {
            if (walker.nextOrderTick >= 0 && tick >= walker.nextOrderTick) {
                order(walker);
            }
        }
        if (tick % STRAY_SAMPLE_TICKS == 0) {
            sampleStray();
        }
        if (traceId >= 0) {
            traceTick();
        }
    }

    /** After the service's tick: its main-thread time, for the per-NPC cost. */
    public void sampleService() {
        serviceUs[serviceSamples++ % TICK_WINDOW] = service.lastTickNanos() / 1000.0;
    }

    /** The server's last tick duration (ServerTickEndEvent), for the tick mean against the baseline. */
    public void sampleTick(double milliseconds) {
        tickMs[tickSamples++ % TICK_WINDOW] = milliseconds;
    }

    /** Stores the current tick mean as the no-NPC baseline; returns it. */
    public double baseline() {
        baselineMs = mean(tickMs, tickSamples);
        log.fileOnly(String.format(Locale.ROOT, "baseline %.2f ms (%d NPCs out)", baselineMs, walkers.size()));
        return baselineMs;
    }

    /** Every second: how far each NPC on its route line is from that line (horizontal blocks). */
    private void sampleStray() {
        for (Walker walker : walkers.values()) {
            if (service.state(walker.mover.id()) != NpcNavState.MOVING) {
                continue;
            }
            RouteFollower follower = service.followerOf(walker.mover.id());
            NavPoint at = walker.mover.position().orElse(null);
            if (follower == null || at == null || follower.bestIndex() < 0) {
                continue; // off-road legs are not strays
            }
            List<NavPoint> line = follower.line();
            int nearest = follower.nearestIndex(at);
            double best = Double.MAX_VALUE;
            for (int i = Math.max(1, nearest - 3); i < Math.min(line.size(), nearest + 4); i++) {
                best = Math.min(best, segmentDistance(line.get(i - 1), line.get(i), at.x(), at.z()));
            }
            if (best == Double.MAX_VALUE) {
                continue;
            }
            straySamples++;
            straySum += best;
            strayMax = Math.max(strayMax, best);
            if (best > 2) {
                strayOver2++;
            }
        }
    }

    static double segmentDistance(NavPoint a, NavPoint b, double x, double z) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.x()) * dx + (z - a.z()) * dz) / len2));
        return Math.hypot(a.x() + t * dx - x, a.z() + t * dz - z);
    }

    // ==================== trace ====================

    /**
     * Traces one NPC every tick for {@code seconds} into its own file: position, on ground, movement since the last
     * tick, navigation state, and JUMP when it leaves the ground upwards (for the staircases). {@code name} null:
     * the NPC nearest {@code near}.
     */
    public String startTrace(NavPoint near, String name, int seconds) {
        Walker pick = null;
        double best = Double.MAX_VALUE;
        for (Walker walker : walkers.values()) {
            NavPoint at = walker.mover.position().orElse(null);
            if (at == null) {
                continue;
            }
            if (name != null && walker.mover.name().equalsIgnoreCase(name)) {
                pick = walker;
                break;
            }
            if (name == null && near != null && at.world().equals(near.world())) {
                double d = at.horizontalDistance(near) + Math.abs(at.y() - near.y());
                if (d < best) {
                    best = d;
                    pick = walker;
                }
            }
        }
        if (pick == null) {
            return "No test NPC " + (name == null ? "near you" : name);
        }
        traceId = pick.mover.id();
        traceUntil = tick + seconds * 20L;
        tracePrev = null;
        traceFile = log.newFile("trace_" + pick.mover.name());
        log.append(traceFile, "# trace " + pick.mover.name() + " for " + seconds
            + " s; columns: tick x y z onGround dx dy dz | state bestIndex nearestIndex | JUMP when it leaves the ground upwards");
        log.fileOnly("trace " + pick.mover.name() + " -> " + traceFile.getFileName());
        return "Tracing " + pick.mover.name() + " for " + seconds + " s -> npc-logs/" + traceFile.getFileName();
    }

    private void traceTick() {
        Walker walker = walkers.get(traceId);
        NavPoint at = walker == null ? null : walker.mover.position().orElse(null);
        if (at == null || tick > traceUntil) {
            if (traceFile != null) {
                log.append(traceFile, "# trace ended");
            }
            traceId = -1;
            return;
        }
        boolean ground = walker.mover.onGround();
        double dx = tracePrev == null ? 0 : at.x() - tracePrev.x();
        double dy = tracePrev == null ? 0 : at.y() - tracePrev.y();
        double dz = tracePrev == null ? 0 : at.z() - tracePrev.z();
        RouteFollower follower = service.followerOf(traceId);
        boolean jump = tracePrev != null && tracePrevGround && dy > 0.2;
        log.append(traceFile, String.format(Locale.ROOT, "%d %.2f %.2f %.2f %s %.3f %.3f %.3f | %s %d %d%s", tick,
            at.x(), at.y(), at.z(), ground ? "G" : "-", dx, dy, dz, service.state(traceId),
            follower == null ? -1 : follower.bestIndex(), follower == null ? -1 : follower.nearestIndex(at),
            jump ? " JUMP" : ""));
        tracePrev = at;
        tracePrevGround = ground;
    }

    // ==================== numbers ====================

    /** Zeroes the counters of this driver and re-bases the navigation event counts (keeps the NPCs). */
    public void resetCounters() {
        for (Event event : Event.values()) {
            totalsAtReset.put(event, metrics.total(event));
        }
        failures.clear();
        trips = 0;
        tripSecondsSum = 0;
        blockedReports = 0;
        straySamples = 0;
        strayOver2 = 0;
        strayMax = 0;
        straySum = 0;
    }

    private long since(Event event) {
        return metrics.total(event) - totalsAtReset.getOrDefault(event, 0L);
    }

    /**
     * The report of {@code /knk npc nav stats}.
     *
     * @param examinerCalls   cells the K&K examiner was asked for, and how many it blocked (the engine adapter's)
     */
    public List<String> report(long examinerCalls, long examinerBlocked) {
        List<String> lines = new ArrayList<>();
        Map<String, Map<NpcNavState, Integer>> states = service.states();
        lines.add(String.format(Locale.ROOT, "NPCs %d (spawns queued %d) states %s | planning queue %d, in flight %d",
            walkers.size(), spawns.pending(), states, service.queued(), service.plansInFlight()));
        double mean = mean(tickMs, tickSamples);
        int npcs = walkers.size();
        lines.add(String.format(Locale.ROOT,
            "tick: mean %.2f ms, p95 %.2f ms (last %d ticks) | baseline %s -> %s ms per NPC", mean,
            percentile(tickMs, tickSamples, 95), Math.min(tickSamples, TICK_WINDOW),
            Double.isNaN(baselineMs) ? "not set (/knk npc nav baseline before spawning)"
                : String.format(Locale.ROOT, "%.2f ms", baselineMs),
            Double.isNaN(baselineMs) || npcs == 0 ? "-" : String.format(Locale.ROOT, "%.3f", (mean - baselineMs) / npcs)));
        double serviceMean = mean(serviceUs, serviceSamples);
        lines.add(String.format(Locale.ROOT, "K&K navigation on the main thread: mean %.1f µs per tick, p95 %.1f µs%s",
            serviceMean, percentile(serviceUs, serviceSamples, 95),
            npcs == 0 ? "" : String.format(Locale.ROOT, " (%.4f ms per NPC)", serviceMean / 1000.0 / npcs)));
        lines.add(String.format(Locale.ROOT,
            "trips %d (mean %.1f s), stretches %d | stuck: engine %d, no progress %d, recovered %d, retries used up %d, "
                + "orders failed %d", trips, trips == 0 ? 0.0 : tripSecondsSum / trips, since(Event.STRETCH),
            since(Event.STUCK_ENGINE), since(Event.STUCK_NO_PROGRESS), since(Event.RECOVERED),
            since(Event.RETRIES_EXHAUSTED), since(Event.FAILED)));
        lines.add(String.format(Locale.ROOT,
            "waits: no allowed route %d (reported blocked %d), way ahead not loaded %d | chunk-unload pauses %d, resumes %d",
            since(Event.NO_ROUTE_WAIT), blockedReports, since(Event.UNLOADED_WAIT), since(Event.PAUSED),
            since(Event.RESUMED)));
        lines.add(String.format(Locale.ROOT,
            "examiner: %d cells asked, %d blocked by K&K rules | engine block rules removed %d (must be 0) | "
                + "trespasses %d (counted from Phase 3 on)", examinerCalls, examinerBlocked,
            since(Event.EXAMINER_LEAK), since(Event.TRESPASS)));
        lines.add(String.format(Locale.ROOT,
            "distance from the road line: mean %.2f, max %.2f blocks, over 2 blocks %.1f%% of %d samples",
            straySamples == 0 ? 0.0 : straySum / straySamples, strayMax,
            straySamples == 0 ? 0.0 : 100.0 * strayOver2 / straySamples, straySamples));
        lines.add("failures: " + (failures.isEmpty() ? "none" : failures.toString()));
        lines.add("route: from " + NpcRouteStore.describe(from) + " -> to " + NpcRouteStore.describe(to)
            + (log.fileName() == null ? "" : " | log npc-logs/" + log.fileName()));
        return lines;
    }

    /** Writes a report into the run log file. */
    public void logReport(List<String> report) {
        log.fileOnly("stats:");
        log.lines(report);
    }

    static double mean(double[] window, int samples) {
        int n = Math.min(samples, window.length);
        if (n == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += window[i];
        }
        return sum / n;
    }

    static double percentile(double[] window, int samples, int percent) {
        int n = Math.min(samples, window.length);
        if (n == 0) {
            return Double.NaN;
        }
        double[] sorted = java.util.Arrays.copyOf(window, n);
        java.util.Arrays.sort(sorted);
        return sorted[Math.min(n - 1, (int) Math.ceil(percent / 100.0 * n) - 1)];
    }
}
