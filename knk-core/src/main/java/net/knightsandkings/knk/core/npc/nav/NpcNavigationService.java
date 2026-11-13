package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;

/**
 * Walks NPCs along K&K routes (implementation plan Phase 2; BEHAVIOR_MODEL NAV-01, 03, 11, 13, 14, 15, 17): a state
 * machine per NPC ({@link NpcNavState}), a planning queue with a limit on plans in flight, the progress watchdog,
 * bounded retries and a {@link NavigationResult} back to the caller. Engine-neutral: the engine moves the body
 * ({@link NpcMover}), the road router plans the road ({@link RoadPlanner}), the walk capture prepares local legs
 * ({@link LocalLegPreparer}) and the world answers floor and chunk questions ({@link NavWorld}).
 *
 * <p>How an order runs (decision Q8, trail mode): the road route is planned on the road graph without chunks; the
 * NPC follows that line as waypoint lists one stretch at a time ({@link RouteFollower}), the next stretch handed
 * over shortly before the current one ends. The engine's own path search walks only the off-road ends, short trips
 * and recovery legs, hop by hop, under K&K's cell access ({@link LocalLeg}).
 *
 * <p>Failure policy (DESIGN §6): a hop or stretch that does not work is retried a bounded number of times
 * ({@code max-retries}); then the route is dropped and planned again from where the NPC stands, at most
 * {@link #MAX_REPLANS} times per order; then the order fails and the caller's behavior decides. No allowed route
 * (the router's BLOCKED or NO_ROUTE) is not a failure: the NPC waits and asks again on a slow timer (NAV-03), and
 * the caller hears {@link NavigationResult.Blocked} once. Roads that do not connect never clear by waiting: the order
 * fails. There is no straight-line fallback across terrain.
 *
 * <p>Main thread only. {@link #tick} runs once per server tick.
 */
public final class NpcNavigationService {

    /** Route replans per order after the retries of a hop or stretch were used up. */
    public static final int MAX_REPLANS = 2;
    /** Local legs that could not be prepared (other than for an unloaded chunk) before the order fails. */
    public static final int MAX_PREPARE_FAILURES = 5;
    /** Ticks between two tries to prepare a local leg that failed. */
    static final int PREPARE_RETRY_TICKS = 40;
    /** The watchdog looks at an NPC this often. */
    static final int WATCHDOG_TICKS = 20;
    /** The early hand-over of the next stretch is checked this often. */
    static final int HANDOVER_TICKS = 5;
    /** The next stretch is handed over within this many blocks of the current one's end. */
    static final double HANDOVER_DISTANCE = 4;
    /** Within this many blocks (horizontally) and two blocks of height the goal counts as reached. */
    static final double ARRIVAL_DISTANCE = 1.5;
    /** The road starts or ends further than this from the NPC or the goal: an off-road leg joins them. */
    static final double ROAD_JOIN_DISTANCE = 3;

    /** What the service needs from outside; {@code log} gets one line per stuck event, with the position. */
    public record Ports(RoadPlanner roads, LocalLegPreparer legs, NavWorld world, NpcNavigationMetrics metrics,
                        LongSupplier nanoTime, Consumer<String> log) {
        public Ports {
            Objects.requireNonNull(roads, "roads");
            Objects.requireNonNull(legs, "legs");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(metrics, "metrics");
            nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
            log = log == null ? line -> { } : log;
        }
    }

    /** One NPC's resolved settings (see {@link NavigationSettings}). */
    public record Settings(double stretchBlocks, double hopBlocks, int noProgressTicks, int maxRetries,
                           int noRouteWaitTicks, double walkOnlyBlocks) {

        public static Settings of(NavigationSettings settings, NavigationSettings.Scope scope) {
            return new Settings(settings.value(NavigationSettings.STRETCH_BLOCKS, scope),
                settings.value(NavigationSettings.HOP_BLOCKS, scope),
                settings.value(NavigationSettings.NO_PROGRESS_TICKS, scope),
                settings.value(NavigationSettings.MAX_RETRIES, scope),
                settings.value(NavigationSettings.NO_ROUTE_WAIT_TICKS, scope),
                settings.value(NavigationSettings.WALK_ONLY_BLOCKS, scope));
        }

        public static Settings defaults() {
            return of(NavigationSettings.defaults(), NavigationSettings.Scope.of(null));
        }
    }

    private final class Nav implements NpcMover.MoverListener {
        final NpcMover mover;
        final NpcAccessProfile profile;
        final Settings settings;
        String world;
        NpcNavState state = NpcNavState.IDLE;
        /** Bumped whenever what is in flight for this NPC no longer counts. */
        int generation;
        NavPoint goal;
        Consumer<NavigationResult> onResult;
        long waitUntil;
        long queuedAtNanos;
        int retries;
        int replans;
        int prepareFailures;
        boolean blockedReported;
        /** Off-road hops (floor points) still to walk before the line, or the recovery leg. */
        final Deque<NavPoint> hops = new ArrayDeque<>();
        /** Off-road hops from the road's end to the goal, walked after the line. */
        final Deque<NavPoint> tail = new ArrayDeque<>();
        RouteFollower follower;
        /** True while the mover follows a stretch as a waypoint list, false during a local leg. */
        boolean onTrail;
        boolean recovering;
        long lastProgressTick;

        Nav(NpcMover mover, NpcAccessProfile profile, Settings settings, String world) {
            this.mover = mover;
            this.profile = profile;
            this.settings = settings;
            this.world = world;
        }

        @Override
        public void arrived(NpcMover m) {
            timed(() -> {
                if (state == NpcNavState.MOVING) {
                    legDone(this);
                }
            });
        }

        @Override
        public void stuck(NpcMover m) {
            timed(() -> {
                if (state == NpcNavState.MOVING) {
                    ports.metrics().count(Event.STUCK_ENGINE);
                    onStuck(this, "stuck");
                }
            });
        }

        @Override
        public void despawned(NpcMover m, boolean chunkUnload) {
            timed(() -> {
                if (goal == null) {
                    return;
                }
                if (chunkUnload) {
                    ports.metrics().count(Event.PAUSED);
                    generation++;
                    queue.remove(this);
                    state = NpcNavState.PAUSED;
                } else {
                    end(this, new NavigationResult.Cancelled("despawned"));
                }
            });
        }

        @Override
        public void respawned(NpcMover m) {
            timed(() -> {
                if (state == NpcNavState.PAUSED) {
                    ports.metrics().count(Event.RESUMED);
                    // the entity is new and may stand somewhere else: plan again from where it is
                    dropRoute(this);
                    waitTicks(this, 1);
                }
            });
        }

        @Override
        public void engineRuleRemoved(NpcMover m, String names) {
            ports.metrics().count(Event.EXAMINER_LEAK);
            ports.log().accept(m.name() + " engine block rules removed from a local search: " + names);
        }
    }

    private final Ports ports;
    private final int maxPlansInFlight;
    private final Map<Integer, Nav> npcs = new LinkedHashMap<>();
    private final Deque<Nav> queue = new ArrayDeque<>();
    private int inFlight;
    private long tick;
    private long busyNanos;
    private long lastTickNanos;
    private int depth;

    public NpcNavigationService(Ports ports, int maxPlansInFlight) {
        this.ports = Objects.requireNonNull(ports, "ports");
        if (maxPlansInFlight < 1) {
            throw new IllegalArgumentException("maxPlansInFlight must be at least 1");
        }
        this.maxPlansInFlight = maxPlansInFlight;
    }

    // ==================== callers ====================

    /** Puts a spawned mover under navigation (state IDLE) and takes over its listener. */
    public void register(NpcMover mover, NpcAccessProfile profile, Settings settings) {
        Objects.requireNonNull(mover, "mover");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(settings, "settings");
        if (npcs.containsKey(mover.id())) {
            throw new IllegalStateException("mover " + mover.id() + " is already registered");
        }
        Nav nav = new Nav(mover, profile, settings, mover.position().map(NavPoint::world).orElse(null));
        npcs.put(mover.id(), nav);
        mover.setListener(nav);
    }

    /**
     * Sends an NPC to {@code goalFeet}. {@code onResult} hears {@link NavigationResult.Blocked} (at most once per
     * blocked spell; the order goes on by itself) and then exactly one final result: Arrived, Failed or Cancelled.
     * A running order is cancelled first ("replaced"). The callback may give the NPC its next order.
     *
     * @return false when the mover is not registered
     */
    public boolean navigate(int moverId, NavPoint goalFeet, Consumer<NavigationResult> onResult) {
        Objects.requireNonNull(goalFeet, "goalFeet");
        Nav nav = npcs.get(moverId);
        if (nav == null) {
            return false;
        }
        if (nav.goal != null) {
            end(nav, new NavigationResult.Cancelled("replaced"));
        }
        nav.goal = goalFeet;
        nav.onResult = onResult;
        nav.retries = 0;
        nav.replans = 0;
        nav.prepareFailures = 0;
        nav.blockedReported = false;
        if (nav.mover.isSpawned()) {
            enqueue(nav);
        } else {
            nav.state = NpcNavState.PAUSED;
        }
        return true;
    }

    /** Ends an NPC's order with {@link NavigationResult.Cancelled}; false when it has none. */
    public boolean cancel(int moverId, String reason) {
        Nav nav = npcs.get(moverId);
        if (nav == null || nav.goal == null) {
            return false;
        }
        end(nav, new NavigationResult.Cancelled(reason));
        return true;
    }

    /** Takes a mover out of navigation (its order is cancelled); the mover itself is the caller's to remove. */
    public void unregister(int moverId) {
        Nav nav = npcs.remove(moverId);
        if (nav == null) {
            return;
        }
        if (nav.goal != null) {
            end(nav, new NavigationResult.Cancelled("removed"));
        }
        nav.generation++;
        queue.remove(nav);
        nav.mover.setListener(null);
    }

    public int count() {
        return npcs.size();
    }

    public int queued() {
        return queue.size();
    }

    public int plansInFlight() {
        return inFlight;
    }

    /** The state of a registered mover, or null. */
    public NpcNavState state(int moverId) {
        Nav nav = npcs.get(moverId);
        return nav == null ? null : nav.state;
    }

    /** The route line a mover follows now (floor points) and the waypoints last handed out, for diagnostics. */
    public RouteFollower followerOf(int moverId) {
        Nav nav = npcs.get(moverId);
        return nav == null ? null : nav.follower;
    }

    /** NPC counts by world and state, for the metrics snapshot and status output. */
    public Map<String, Map<NpcNavState, Integer>> states() {
        Map<String, Map<NpcNavState, Integer>> out = new HashMap<>();
        for (Nav nav : npcs.values()) {
            out.computeIfAbsent(nav.world == null ? "unknown" : nav.world, w -> new EnumMap<>(NpcNavState.class))
                .merge(nav.state, 1, Integer::sum);
        }
        return out;
    }

    // ==================== tick ====================

    /** Once per server tick: timers, the watchdog, stretch hand-overs and the planning queue. */
    public void tick() {
        timed(() -> {
            tick++;
            for (Nav nav : List.copyOf(npcs.values())) {
                switch (nav.state) {
                    case WAITING -> {
                        if (tick >= nav.waitUntil) {
                            enqueue(nav);
                        }
                    }
                    case MOVING -> watch(nav);
                    default -> { }
                }
            }
            while (inFlight < maxPlansInFlight && !queue.isEmpty()) {
                Nav nav = queue.pollFirst();
                if (nav.state == NpcNavState.QUEUED && npcs.get(nav.mover.id()) == nav) {
                    plan(nav);
                }
            }
        });
        ports.metrics().observeMainThread(busyNanos / 1e9);
        lastTickNanos = busyNanos;
        busyNanos = 0;
    }

    /** Main-thread time the last tick took, the listener calls since the tick before included. */
    public long lastTickNanos() {
        return lastTickNanos;
    }

    private void timed(Runnable work) {
        if (depth++ > 0) {
            try {
                work.run();
            } finally {
                depth--;
            }
            return;
        }
        long start = ports.nanoTime().getAsLong();
        try {
            work.run();
        } finally {
            depth--;
            busyNanos += ports.nanoTime().getAsLong() - start;
        }
    }

    private void watch(Nav nav) {
        if (!nav.onTrail || nav.follower == null || !nav.mover.isSpawned()) {
            return;
        }
        boolean watchdog = (tick + nav.mover.id()) % WATCHDOG_TICKS == 0;
        boolean handover = nav.follower.hasMore() && (tick + nav.mover.id()) % HANDOVER_TICKS == 0;
        if (!watchdog && !handover) {
            return;
        }
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null) {
            return;
        }
        if (watchdog) {
            // the engine reports no stuck for waypoint lists (NAV-17): progress along the route is watched here
            if (nav.follower.progressed(at)) {
                nav.lastProgressTick = tick;
            } else if (tick - nav.lastProgressTick >= nav.settings.noProgressTicks()) {
                ports.metrics().count(Event.STUCK_NO_PROGRESS);
                onStuck(nav, "no progress for " + nav.settings.noProgressTicks() + " ticks");
                return;
            }
        }
        if (handover) {
            NavPoint end = nav.follower.sentEndPoint();
            if (end != null && at.horizontalDistance(end) < HANDOVER_DISTANCE) {
                sendStretch(nav, false);
            }
        }
    }

    // ==================== planning ====================

    private void enqueue(Nav nav) {
        if (nav.state == NpcNavState.QUEUED) {
            return;
        }
        nav.state = NpcNavState.QUEUED;
        nav.queuedAtNanos = ports.nanoTime().getAsLong();
        queue.addLast(nav);
    }

    private void waitTicks(Nav nav, long ticks) {
        nav.state = NpcNavState.WAITING;
        nav.waitUntil = tick + ticks;
    }

    private boolean current(Nav nav, int generation) {
        return nav.generation == generation && nav.state == NpcNavState.PLANNING && nav.mover.isSpawned()
            && npcs.get(nav.mover.id()) == nav;
    }

    private void plan(Nav nav) {
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null) {
            nav.state = NpcNavState.PAUSED; // despawned without the event: resumes on respawn
            return;
        }
        nav.world = at.world();
        nav.state = NpcNavState.PLANNING;
        ports.metrics().observeQueueWait((ports.nanoTime().getAsLong() - nav.queuedAtNanos) / 1e9);
        int generation = ++nav.generation;
        if (!nav.hops.isEmpty()) {
            planHop(nav, generation, at);
        } else if (nav.follower != null && nav.follower.hasMore()) {
            sendStretch(nav, true);
        } else if (!nav.tail.isEmpty()) {
            startTail(nav);
            planHop(nav, generation, at);
        } else {
            planRoad(nav, generation, at);
        }
    }

    private void startTail(Nav nav) {
        nav.hops.addAll(nav.tail);
        nav.tail.clear();
        nav.follower = null;
        nav.onTrail = false;
    }

    private AccessSubject subject(Nav nav, NavPoint feet) {
        return new NpcAccessSubject(nav.mover.name(), nav.profile, feet.world(),
            ports.world().regionsAt(feet.world(), feet.blockX(), feet.blockY(), feet.blockZ()));
    }

    private boolean atGoal(Nav nav, NavPoint feet) {
        return feet.world().equals(nav.goal.world()) && feet.horizontalDistance(nav.goal) <= ARRIVAL_DISTANCE
            && Math.abs(feet.y() - nav.goal.y()) <= 2;
    }

    private void planRoad(Nav nav, int generation, NavPoint at) {
        if (!at.world().equals(nav.goal.world())) {
            end(nav, failed("the goal is in another world (" + nav.goal.world() + ")"));
            return;
        }
        if (atGoal(nav, at)) {
            end(nav, new NavigationResult.Arrived());
            return;
        }
        NavPoint from = HopPlanner.floor(at);
        NavPoint to = HopPlanner.floor(nav.goal);
        double direct = from.horizontalDistance(to);
        if (direct <= nav.settings.hopBlocks()) {
            // a short trip: one local leg, no road
            nav.hops.add(HopPlanner.snapToFloor(ports.world(), to));
            planHop(nav, generation, at);
            return;
        }
        long start = ports.nanoTime().getAsLong();
        inFlight++;
        CompletableFuture<RoadPlanner.RoadPlan> planning;
        try {
            planning = ports.roads().plan(subject(nav, at), from, to);
        } catch (RuntimeException e) {
            planning = CompletableFuture.failedFuture(e);
        }
        planning.whenComplete((plan, error) -> timed(() -> {
            inFlight = Math.max(0, inFlight - 1);
            ports.metrics().observePlan("road", (ports.nanoTime().getAsLong() - start) / 1e9);
            if (current(nav, generation)) {
                onRoad(nav, generation, error != null || plan == null
                    ? RoadPlanner.RoadPlan.none(RoadPlanner.Status.ERROR, String.valueOf(error)) : plan, from, to, direct);
            }
        }));
    }

    private void onRoad(Nav nav, int generation, RoadPlanner.RoadPlan plan, NavPoint from, NavPoint to, double direct) {
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null) {
            nav.state = NpcNavState.PAUSED;
            return;
        }
        switch (plan.status()) {
            case FOUND -> {
                if (plan.line().isEmpty()) {
                    end(nav, failed("the road route is empty"));
                    return;
                }
                nav.blockedReported = false;
                nav.follower = new RouteFollower(plan.line());
                nav.onTrail = false;
                NavPoint roadStart = plan.line().get(0);
                NavPoint roadEnd = plan.line().get(plan.line().size() - 1);
                nav.hops.clear();
                nav.tail.clear();
                if (roadStart.horizontalDistance(from) > ROAD_JOIN_DISTANCE) {
                    nav.hops.addAll(HopPlanner.hops(ports.world(), List.of(from, roadStart), nav.settings.hopBlocks()));
                }
                if (roadEnd.horizontalDistance(to) > ARRIVAL_DISTANCE) {
                    nav.tail.addAll(HopPlanner.hops(ports.world(), List.of(roadEnd, to), nav.settings.hopBlocks()));
                }
                if (nav.hops.isEmpty()) {
                    sendStretch(nav, true);
                } else {
                    planHop(nav, generation, at);
                }
            }
            case BLOCKED, NO_ROUTE -> {
                // the rules allow no route now (a closed gate, a denied region): wait where the NPC is and ask again;
                // never improvise across terrain (NAV-03)
                ports.metrics().count(Event.NO_ROUTE_WAIT);
                waitTicks(nav, nav.settings.noRouteWaitTicks());
                if (!nav.blockedReported) {
                    nav.blockedReported = true;
                    report(nav, new NavigationResult.Blocked(plan.reason() == null ? "no allowed route" : plan.reason(),
                        plan.blockingElement()));
                }
            }
            case DIFFERENT_COMPONENTS -> end(nav, failed("no road connects"
                + (plan.reason() == null ? "" : " (" + plan.reason() + ")")));
            case NO_NETWORK, OFF_ROAD -> {
                if (direct <= nav.settings.walkOnlyBlocks()) {
                    // an end too far from a road: a walk-only trip in short hops, each under K&K's cell access
                    nav.hops.addAll(HopPlanner.hops(ports.world(), List.of(from, to), nav.settings.hopBlocks()));
                    planHop(nav, generation, at);
                } else {
                    end(nav, failed((plan.reason() == null ? "no road" : plan.reason()) + String.format(Locale.ROOT,
                        "; %.0f blocks is further than walk-only-blocks (%.0f)", direct, nav.settings.walkOnlyBlocks())));
                }
            }
            case ERROR -> {
                if (++nav.prepareFailures >= MAX_PREPARE_FAILURES) {
                    end(nav, failed("road planning failed: " + plan.reason()));
                } else {
                    waitTicks(nav, PREPARE_RETRY_TICKS);
                }
            }
            default -> end(nav, failed("road planning: " + plan.status()));
        }
    }

    /** One local leg to the first hop: capture and access first (a planning slot), then the engine's path search. */
    private void planHop(Nav nav, int generation, NavPoint at) {
        NavPoint target = nav.hops.peekFirst();
        long start = ports.nanoTime().getAsLong();
        inFlight++;
        CompletableFuture<LocalLegPreparer.Prepared> preparing;
        try {
            preparing = ports.legs().prepare(subject(nav, at), at, target);
        } catch (RuntimeException e) {
            preparing = CompletableFuture.failedFuture(e);
        }
        preparing.whenComplete((prepared, error) -> timed(() -> {
            inFlight = Math.max(0, inFlight - 1);
            ports.metrics().observePlan("local", (ports.nanoTime().getAsLong() - start) / 1e9);
            if (!current(nav, generation)) {
                return;
            }
            LocalLegPreparer.Status status = error != null || prepared == null ? LocalLegPreparer.Status.FAILED
                : prepared.status();
            switch (status) {
                case READY -> {
                    nav.prepareFailures = 0;
                    nav.onTrail = false;
                    nav.state = NpcNavState.MOVING;
                    nav.mover.walkTo(HopPlanner.feet(target), prepared.leg());
                }
                case UNLOADED -> {
                    // never load chunks for a capture: wait at the edge of the loaded area (NAV-12, Phase 7b)
                    ports.metrics().count(Event.UNLOADED_WAIT);
                    waitTicks(nav, nav.settings.noRouteWaitTicks());
                }
                default -> {
                    String detail = error != null ? String.valueOf(error) : prepared == null ? "no result"
                        : status + (prepared.detail() == null ? "" : " " + prepared.detail());
                    if (++nav.prepareFailures >= MAX_PREPARE_FAILURES) {
                        end(nav, failed("a local leg could not be prepared: " + detail));
                    } else {
                        waitTicks(nav, PREPARE_RETRY_TICKS);
                    }
                }
            }
        }));
    }

    /**
     * Hands the mover the next stretch of the line. {@code required}: the NPC needs it now; otherwise it is the early
     * hand-over, which is skipped when the stretch is not loaded yet.
     */
    private void sendStretch(Nav nav, boolean required) {
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null) {
            nav.state = NpcNavState.PAUSED;
            return;
        }
        RouteFollower.Stretch stretch = nav.follower.next(ports.world(), nav.settings.stretchBlocks(),
            at.blockY() - 1);
        switch (stretch.kind()) {
            case UNLOADED -> {
                if (required) {
                    nav.onTrail = false;
                    ports.metrics().count(Event.UNLOADED_WAIT);
                    waitTicks(nav, nav.settings.noRouteWaitTicks());
                }
            }
            case DONE -> {
                if (required) {
                    nav.onTrail = false;
                    nav.state = NpcNavState.MOVING;
                    legDone(nav);
                }
            }
            case WAYPOINTS -> {
                if (!nav.onTrail || nav.follower.bestIndex() < 0) {
                    nav.lastProgressTick = tick;
                }
                nav.onTrail = true;
                nav.state = NpcNavState.MOVING;
                ports.metrics().count(Event.STRETCH);
                nav.mover.follow(stretch.waypoints());
            }
            default -> { }
        }
    }

    /** A stretch or a local leg was walked: the next hop, the next stretch, the tail, or the end of the order. */
    private void legDone(Nav nav) {
        nav.retries = 0;
        if (!nav.onTrail && !nav.hops.isEmpty()) {
            nav.hops.pollFirst();
        }
        if (nav.recovering) {
            nav.recovering = false;
            ports.metrics().count(Event.RECOVERED);
        }
        if (!nav.hops.isEmpty()) {
            enqueue(nav);
        } else if (nav.follower != null && nav.follower.hasMore()) {
            sendStretch(nav, true);
        } else if (!nav.tail.isEmpty()) {
            startTail(nav);
            enqueue(nav);
        } else {
            end(nav, new NavigationResult.Arrived());
        }
    }

    // ==================== stuck ====================

    private void onStuck(Nav nav, String what) {
        NavPoint at = nav.mover.position().orElse(null);
        NavPoint aim = nav.onTrail && nav.follower != null ? nav.follower.sentEndPoint() : nav.hops.peekFirst();
        ports.log().accept(String.format(Locale.ROOT, "%s %s at %s -> %s floor %s (retry %d of %d)", nav.mover.name(),
            what, at == null ? "-" : at.blockX() + " " + at.blockY() + " " + at.blockZ(),
            nav.onTrail ? "stretch end" : "hop",
            aim == null ? "-" : aim.blockX() + " " + aim.blockY() + " " + aim.blockZ(), nav.retries + 1,
            nav.settings.maxRetries()));
        if (at != null && ++nav.retries <= nav.settings.maxRetries()) {
            if (nav.onTrail && nav.follower != null) {
                // one local leg to a point a few blocks further along, then back on the line
                NavPoint target = nav.follower.recoveryTarget(ports.world(), at);
                nav.hops.clear();
                nav.hops.add(target);
                nav.onTrail = false;
                nav.recovering = true;
            }
            enqueue(nav); // a local leg keeps its hop: try it again from here
            nav.mover.stop();
            return;
        }
        ports.metrics().count(Event.RETRIES_EXHAUSTED);
        nav.retries = 0;
        dropRoute(nav);
        if (++nav.replans <= MAX_REPLANS) {
            waitTicks(nav, nav.settings.noRouteWaitTicks());
            nav.mover.stop();
        } else {
            end(nav, failed("stuck" + (at == null ? "" : " at " + at.blockX() + " " + at.blockY() + " " + at.blockZ())
                + ": retries and replans used up"));
        }
    }

    private void dropRoute(Nav nav) {
        nav.hops.clear();
        nav.tail.clear();
        nav.follower = null;
        nav.onTrail = false;
        nav.recovering = false;
    }

    // ==================== results ====================

    private static NavigationResult failed(String reason) {
        return new NavigationResult.Failed(reason);
    }

    private void report(Nav nav, NavigationResult result) {
        if (nav.onResult != null) {
            nav.onResult.accept(result);
        }
    }

    /** Ends the order: the NPC is IDLE and stopped before the caller hears the result (it may order again). */
    private void end(Nav nav, NavigationResult result) {
        Consumer<NavigationResult> onResult = nav.onResult;
        nav.generation++;
        queue.remove(nav);
        dropRoute(nav);
        nav.goal = null;
        nav.onResult = null;
        nav.state = NpcNavState.IDLE;
        if (result instanceof NavigationResult.Arrived) {
            ports.metrics().count(Event.ARRIVED);
        } else {
            if (result instanceof NavigationResult.Failed) {
                ports.metrics().count(Event.FAILED);
            }
            if (nav.mover.isSpawned()) {
                nav.mover.stop();
            }
        }
        if (onResult != null) {
            onResult.accept(result);
        }
    }
}
