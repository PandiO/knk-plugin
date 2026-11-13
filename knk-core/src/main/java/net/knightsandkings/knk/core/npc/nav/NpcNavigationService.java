package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

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
 * <p>Rules stay binding while an NPC walks (Phase 3; NAV-02, 03, 04, 13). A few NPCs per tick get a rule check: the
 * trespass guard (did the NPC enter a region the current rules deny?) and the recheck of the way ahead (the rest of
 * the engine's path, the hops, the route line, the route's gates) against {@link AccessRules} judged from the
 * regions the NPC stands in; a denied region or a closed gate ahead plans the route again. Gate and availability
 * events ({@link #onGateChanged}, {@link #onAvailabilityChanged}) only mark the NPCs they can concern - those whose
 * route crosses the gate, those waiting at a blocking element - and the same time slice looks at them first. When
 * the router answers BLOCKED, the NPC walks the partial route to the blocking element and waits there; the
 * element's open event wakes it, a slow timer ({@code no-route-wait-ticks}) is the fallback.
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
    /** Rule checks (recheck of the way ahead, trespass guard, event wake-ups) per tick unless told otherwise. */
    public static final int DEFAULT_RULE_CHECKS_PER_TICK = 10;
    /** With the recheck off ({@code recheck-ticks: 0}) the trespass guard still looks at a walking NPC this often. */
    static final int TRESPASS_TICKS = 40;
    /** The recheck looks this many route points ahead ({@link RouteFollower#STEP} blocks each: about 96 blocks). */
    static final int RECHECK_AHEAD_POINTS = 64;
    /** A gate is ahead until the NPC is this many route points past the end of the gate's road stretch. */
    static final int GATE_PASSED_POINTS = 2;

    /** What the service needs from outside; {@code log} gets one line per stuck event, with the position. */
    public record Ports(RoadPlanner roads, LocalLegPreparer legs, NavWorld world, NpcNavigationMetrics metrics,
                        LongSupplier nanoTime, Consumer<String> log, AccessRules rules) {

        /** Without access rules of its own: everything is allowed while walking ({@link AccessRules#OPEN}). */
        public Ports(RoadPlanner roads, LocalLegPreparer legs, NavWorld world, NpcNavigationMetrics metrics,
                     LongSupplier nanoTime, Consumer<String> log) {
            this(roads, legs, world, metrics, nanoTime, log, null);
        }

        public Ports {
            Objects.requireNonNull(roads, "roads");
            Objects.requireNonNull(legs, "legs");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(metrics, "metrics");
            nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
            log = log == null ? line -> { } : log;
            rules = rules == null ? AccessRules.OPEN : rules;
        }
    }

    /** One NPC's resolved settings (see {@link NavigationSettings}). */
    public record Settings(double stretchBlocks, double hopBlocks, int noProgressTicks, int maxRetries,
                           int noRouteWaitTicks, double walkOnlyBlocks, int recheckTicks, boolean stopOnTrespass) {

        /** With the code defaults for the Phase 3 settings ({@code recheck-ticks}, {@code stop-on-trespass}). */
        public Settings(double stretchBlocks, double hopBlocks, int noProgressTicks, int maxRetries,
                        int noRouteWaitTicks, double walkOnlyBlocks) {
            this(stretchBlocks, hopBlocks, noProgressTicks, maxRetries, noRouteWaitTicks, walkOnlyBlocks,
                NavigationSettings.RECHECK_TICKS.defaultValue(), NavigationSettings.STOP_ON_TRESPASS.defaultValue());
        }

        public static Settings of(NavigationSettings settings, NavigationSettings.Scope scope) {
            return new Settings(settings.value(NavigationSettings.STRETCH_BLOCKS, scope),
                settings.value(NavigationSettings.HOP_BLOCKS, scope),
                settings.value(NavigationSettings.NO_PROGRESS_TICKS, scope),
                settings.value(NavigationSettings.MAX_RETRIES, scope),
                settings.value(NavigationSettings.NO_ROUTE_WAIT_TICKS, scope),
                settings.value(NavigationSettings.WALK_ONLY_BLOCKS, scope),
                settings.value(NavigationSettings.RECHECK_TICKS, scope),
                settings.value(NavigationSettings.STOP_ON_TRESPASS, scope));
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
        // ---- rules stay binding (Phase 3) ----
        /** What blocks the order now: set while the NPC walks the partial route to it and while it waits there. */
        BlockingElement blockedBy;
        /** Whether {@link #blockedBy} still blocks a subject; null when unknown. */
        Predicate<AccessSubject> stillBlocked;
        /** The element the caller last heard {@link NavigationResult.Blocked} for. */
        BlockingElement reportedElement;
        /** Waiting at the blocking element (or where the NPC stood when nothing of the route was allowed). */
        boolean atBlock;
        /** The gate doors on the route in hand, with the line index where each one's road stretch ends. */
        List<GateOnLine> gates = List.of();
        long lastRuleTick;
        /** The regions the NPC stood in at its last rule check (the trespass guard); null before the first. */
        Set<String> regions;
        /** Gate doors that changed state since the last rule check, when they can concern this NPC; null: none. */
        Set<Integer> eventDoors;
        /** Availability may have changed since the last rule check. */
        boolean eventAvailability;
        /** What the recheck saw ahead when it asked for the plan in flight; null for any other plan. */
        String replanFor;
        /** Elements the recheck saw ahead that the router then still routed through: not asked about again. */
        final Set<String> tolerated = new HashSet<>();

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
                    atBlock = false;
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
                    regions = null;
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

    private record GateOnLine(int doorId, int index) {
    }

    private final Ports ports;
    private final int maxPlansInFlight;
    private final int ruleChecksPerTick;
    private int ruleCursor;
    private final Map<Integer, Nav> npcs = new LinkedHashMap<>();
    private final Deque<Nav> queue = new ArrayDeque<>();
    private int inFlight;
    private long tick;
    private long busyNanos;
    private long lastTickNanos;
    private int depth;

    public NpcNavigationService(Ports ports, int maxPlansInFlight) {
        this(ports, maxPlansInFlight, DEFAULT_RULE_CHECKS_PER_TICK);
    }

    /** @param ruleChecksPerTick NPCs whose rules are checked per tick at most (NAV-13: never all at once) */
    public NpcNavigationService(Ports ports, int maxPlansInFlight, int ruleChecksPerTick) {
        this.ports = Objects.requireNonNull(ports, "ports");
        if (maxPlansInFlight < 1) {
            throw new IllegalArgumentException("maxPlansInFlight must be at least 1");
        }
        if (ruleChecksPerTick < 1) {
            throw new IllegalArgumentException("ruleChecksPerTick must be at least 1");
        }
        this.maxPlansInFlight = maxPlansInFlight;
        this.ruleChecksPerTick = ruleChecksPerTick;
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
        nav.reportedElement = null;
        nav.lastRuleTick = tick;
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

    /**
     * What blocks a mover's order now, while it walks the partial route to it and while it waits there; null when
     * nothing blocks.
     */
    public BlockingElement blockingOf(int moverId) {
        Nav nav = npcs.get(moverId);
        return nav == null ? null : nav.blockedBy;
    }

    /** Whether a mover waits at its blocking element (woken by the element's open event). */
    public boolean waitsAtBlock(int moverId) {
        Nav nav = npcs.get(moverId);
        return nav != null && nav.atBlock;
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

    /** Once per server tick: timers, the watchdog, stretch hand-overs, the rule checks and the planning queue. */
    public void tick() {
        timed(() -> {
            tick++;
            List<Nav> all = List.copyOf(npcs.values());
            for (Nav nav : all) {
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
            ruleChecks(all);
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
        nav.atBlock = false;
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
            nav.replanFor = null;
            nav.blockedBy = null;
            nav.stillBlocked = null;
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
        String replanFor = nav.replanFor;
        nav.replanFor = null;
        if (plan.status() != RoadPlanner.Status.BLOCKED) {
            nav.blockedBy = null;
            nav.stillBlocked = null;
        }
        switch (plan.status()) {
            case FOUND -> {
                if (plan.line().isEmpty()) {
                    end(nav, failed("the road route is empty"));
                    return;
                }
                if (replanFor != null) {
                    // the recheck and the router disagree (the router is the authority): do not ask again for this
                    // order, the trespass guard still watches
                    nav.tolerated.add(replanFor);
                    ports.log().accept(nav.mover.name() + " recheck saw " + replanFor
                        + " ahead, the router still routes that way: not rechecked again until the rules change");
                }
                nav.blockedReported = false;
                nav.reportedElement = null;
                startRoute(nav, generation, at, plan.line(), plan.gates(), from, to);
            }
            case BLOCKED -> {
                // a route exists without the current rules (a closed gate, a denied region): walk as far as the rules
                // allow, wait at the blocking element, never improvise across terrain (NAV-03, NAV-04)
                nav.blockedBy = plan.blocking() == null
                    ? BlockingElement.unknown(plan.reason() == null ? "no allowed route" : plan.reason()) : plan.blocking();
                nav.stillBlocked = plan.stillBlocked();
                List<NavPoint> partial = allowedPart(nav, at, plan.line());
                if (!partial.isEmpty()
                    && partial.get(partial.size() - 1).horizontalDistance(from) > ROAD_JOIN_DISTANCE) {
                    ports.metrics().count(Event.BLOCKED_WALK);
                    startRoute(nav, generation, at, partial, plan.gates(), from, null);
                } else {
                    waitAtBlock(nav);
                }
                reportBlocked(nav, plan.reason());
            }
            case NO_ROUTE -> {
                // no route even without the rules: wait where the NPC is and ask again on the slow timer
                ports.metrics().count(Event.NO_ROUTE_WAIT);
                waitTicks(nav, nav.settings.noRouteWaitTicks());
                reportBlocked(nav, plan.reason());
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

    /**
     * Takes a road line as the route in hand: an off-road leg to its start when the NPC is not on it, the line in
     * stretches, and (with a goal) an off-road leg from its end. {@code to} null: the line is the partial route to
     * a blocking element and ends there.
     */
    private void startRoute(Nav nav, int generation, NavPoint at, List<NavPoint> line,
                            List<RoadPlanner.GateCrossing> gates, NavPoint from, NavPoint to) {
        nav.follower = new RouteFollower(line);
        nav.onTrail = false;
        List<GateOnLine> onLine = new java.util.ArrayList<>();
        for (RoadPlanner.GateCrossing gate : gates) {
            onLine.add(new GateOnLine(gate.doorId(), nav.follower.indexNearest(gate.at())));
        }
        nav.gates = List.copyOf(onLine);
        NavPoint roadStart = line.get(0);
        NavPoint roadEnd = line.get(line.size() - 1);
        nav.hops.clear();
        nav.tail.clear();
        if (roadStart.horizontalDistance(from) > ROAD_JOIN_DISTANCE) {
            nav.hops.addAll(HopPlanner.hops(ports.world(), List.of(from, roadStart), nav.settings.hopBlocks()));
        }
        if (to != null && roadEnd.horizontalDistance(to) > ARRIVAL_DISTANCE) {
            nav.tail.addAll(HopPlanner.hops(ports.world(), List.of(roadEnd, to), nav.settings.hopBlocks()));
        }
        if (nav.hops.isEmpty()) {
            sendStretch(nav, true);
        } else {
            planHop(nav, generation, at);
        }
    }

    /**
     * The partial route of a BLOCKED plan as far as the NPC may walk it now: cut before the first point in a region
     * the current rules deny it (the router's partial route ends at a road node, which may lie past a district's
     * edge), so the NPC waits at the edge and the recheck has nothing to object to.
     */
    private List<NavPoint> allowedPart(Nav nav, NavPoint at, List<NavPoint> line) {
        if (line.isEmpty()) {
            return line;
        }
        Set<String> here = regionsAtFeet(at);
        Predicate<String> mayEnter = null;
        Map<String, Boolean> verdicts = new HashMap<>();
        List<NavPoint> points = HopPlanner.resampleKeepingCorners(line, RouteFollower.STEP);
        for (int i = 0; i < points.size(); i++) {
            for (String region : regionsAtFloor(points.get(i))) {
                if (here.contains(region)) {
                    continue;
                }
                if (mayEnter == null) {
                    mayEnter = ports.rules().mayEnter(subject(nav, at.world(), here));
                }
                if (!verdicts.computeIfAbsent(region, mayEnter::test)) {
                    return i == 0 ? List.of() : points.subList(0, i);
                }
            }
        }
        return line;
    }

    private void waitAtBlock(Nav nav) {
        dropRoute(nav);
        ports.metrics().count(Event.NO_ROUTE_WAIT);
        waitTicks(nav, nav.settings.noRouteWaitTicks()); // the safety timer; the element's open event comes first
        nav.atBlock = true;
    }

    /**
     * The caller hears Blocked once per blocked spell, and again when another element blocks (the callback may give
     * another order: call it last).
     */
    private void reportBlocked(Nav nav, String reason) {
        if (nav.blockedReported && (nav.blockedBy == null || nav.blockedBy.equals(nav.reportedElement))) {
            return;
        }
        nav.blockedReported = true;
        nav.reportedElement = nav.blockedBy;
        report(nav, new NavigationResult.Blocked(reason == null ? "no allowed route" : reason, nav.blockedBy));
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
        } else if (nav.blockedBy != null) {
            waitAtBlock(nav); // the partial route is walked: wait at the blocking element
        } else {
            end(nav, new NavigationResult.Arrived());
        }
    }

    // ==================== rules stay binding ====================

    /**
     * A gate door opened, closed, started moving, jammed or was destroyed. Marks only the NPCs it can concern: those
     * whose route passes the gate, those blocked by it, and those waiting at another blocking element (the gate may
     * open another way). They are looked at in the next rule checks, a few per tick.
     */
    public void onGateChanged(int doorId) {
        timed(() -> {
            for (Nav nav : npcs.values()) {
                if (nav.goal == null) {
                    continue;
                }
                nav.tolerated.clear();
                boolean concerned = nav.atBlock || (nav.blockedBy != null && nav.blockedBy.hasGate(doorId));
                for (int i = 0; !concerned && i < nav.gates.size(); i++) {
                    concerned = nav.gates.get(i).doorId() == doorId;
                }
                if (concerned) {
                    if (nav.eventDoors == null) {
                        nav.eventDoors = new HashSet<>();
                    }
                    nav.eventDoors.add(doorId);
                }
            }
        });
    }

    /**
     * A region's entry or exit rule may have changed, which one is not known (the domain cache was refreshed, a
     * siege lockdown changed). Nobody plans again for it: walking NPCs get their recheck early, NPCs at a blocking
     * element are asked whether it still blocks; only those with something denied ahead, or whose element opened,
     * plan again.
     */
    public void onAvailabilityChanged() {
        timed(() -> {
            for (Nav nav : npcs.values()) {
                if (nav.goal != null) {
                    nav.tolerated.clear();
                    nav.eventAvailability = true;
                }
            }
        });
    }

    private static boolean hasEvents(Nav nav) {
        return nav.eventAvailability || nav.eventDoors != null;
    }

    private boolean ruleCheckDue(Nav nav, boolean eventsOnly) {
        if (nav.goal == null || !nav.mover.isSpawned()) {
            return false;
        }
        if (nav.atBlock) {
            return hasEvents(nav);
        }
        if (nav.state != NpcNavState.MOVING) {
            return false;
        }
        if (hasEvents(nav)) {
            return true;
        }
        int every = nav.settings.recheckTicks() > 0 ? nav.settings.recheckTicks() : TRESPASS_TICKS;
        return !eventsOnly && tick - nav.lastRuleTick >= every;
    }

    /** At most {@code ruleChecksPerTick} NPCs per tick: first those an event concerns, then those whose time is up. */
    private void ruleChecks(List<Nav> all) {
        int n = all.size();
        if (n == 0) {
            return;
        }
        int done = 0;
        for (int i = 0; i < n && done < ruleChecksPerTick; i++) {
            Nav nav = all.get(i);
            if (npcs.get(nav.mover.id()) == nav && ruleCheckDue(nav, true)) {
                done++;
                ruleCheck(nav);
            }
        }
        int start = ruleCursor % n;
        for (int i = 0; i < n && done < ruleChecksPerTick; i++) {
            int index = (start + i) % n;
            Nav nav = all.get(index);
            if (npcs.get(nav.mover.id()) == nav && ruleCheckDue(nav, false)) {
                done++;
                ruleCursor = index + 1;
                ruleCheck(nav);
            }
        }
    }

    private void ruleCheck(Nav nav) {
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null) {
            return;
        }
        Set<Integer> doors = nav.eventDoors == null ? Set.of() : nav.eventDoors;
        boolean availability = nav.eventAvailability;
        boolean byEvent = hasEvents(nav);
        nav.eventDoors = null;
        nav.eventAvailability = false;
        Set<String> here = regionsAtFeet(at);
        AccessSubject subject = subject(nav, at.world(), here);
        if (nav.atBlock) {
            wakeIfOpen(nav, subject, doors, availability);
            return;
        }
        ports.metrics().count(Event.RULE_CHECK);
        boolean periodic = nav.settings.recheckTicks() > 0 && tick - nav.lastRuleTick >= nav.settings.recheckTicks();
        nav.lastRuleTick = tick;
        if (trespassed(nav, at, here) && nav.settings.stopOnTrespass()) {
            replan(nav);
            return;
        }
        if (nav.blockedBy != null && byEvent && (availability || doors.stream().anyMatch(nav.blockedBy::hasGate))
            && !stillBlocked(nav, subject)) {
            // on the way to a blocking element that opened meanwhile: the full route
            ports.metrics().count(Event.REPLAN_EVENT);
            ports.log().accept(nav.mover.name() + " " + nav.blockedBy.describe() + " opened: route planned again");
            replan(nav);
            return;
        }
        if (!byEvent && !periodic) {
            return; // the recheck is off: only the trespass guard looks
        }
        String denied = deniedAhead(nav, at, here, subject, availability || periodic ? null : doors);
        if (denied != null) {
            ports.metrics().count(byEvent ? Event.REPLAN_EVENT : Event.REPLAN_RECHECK);
            ports.log().accept(String.format(Locale.ROOT, "%s way ahead is closed by %s (%s) at %d %d %d: route planned again",
                nav.mover.name(), denied, byEvent ? "event" : "recheck", at.blockX(), at.blockY(), at.blockZ()));
            nav.replanFor = denied;
            replan(nav);
        }
    }

    /** An NPC waiting at its blocking element: plan again when the element, or a gate elsewhere, opened. */
    private void wakeIfOpen(Nav nav, AccessSubject subject, Set<Integer> doors, boolean availability) {
        boolean own = availability || nav.blockedBy == null || doors.stream().anyMatch(nav.blockedBy::hasGate);
        boolean open = own && !stillBlocked(nav, subject);
        for (int doorId : doors) {
            if (open) {
                break;
            }
            // another gate: when it lets the NPC through now, there may be another way
            open = (nav.blockedBy == null || !nav.blockedBy.hasGate(doorId)) && !ports.rules().gateBlocks(subject, doorId);
        }
        if (open) {
            ports.metrics().count(Event.WOKEN);
            ports.metrics().count(Event.REPLAN_EVENT);
            enqueue(nav);
        }
    }

    private boolean stillBlocked(Nav nav, AccessSubject subject) {
        if (nav.stillBlocked == null) {
            return false; // unknown: plan again to find out
        }
        try {
            return nav.stillBlocked.test(subject);
        } catch (RuntimeException e) {
            ports.log().accept(nav.mover.name() + " blocking element check failed: " + e);
            return false;
        }
    }

    /**
     * The trespass guard: a region the NPC stands in now and did not stand in at its last check was entered; the
     * entry is judged by the current rules from the regions it came from. Independent of the planner and of the
     * engine's block rules: it watches where NPCs really are. Must stay 0.
     */
    private boolean trespassed(Nav nav, NavPoint at, Set<String> here) {
        Set<String> before = nav.regions;
        nav.regions = here;
        if (before == null) {
            return false; // the first look at this NPC: nothing to compare with
        }
        Predicate<String> mayEnter = null;
        boolean trespassed = false;
        for (String region : here) {
            if (before.contains(region)) {
                continue;
            }
            if (mayEnter == null) {
                mayEnter = ports.rules().mayEnter(subject(nav, at.world(), before));
            }
            if (!mayEnter.test(region)) {
                trespassed = true;
                ports.metrics().count(Event.TRESPASS);
                ports.log().accept(String.format(Locale.ROOT, "TRESPASS %s entered %s at %d %d %d (%s)",
                    nav.mover.name(), region, at.blockX(), at.blockY(), at.blockZ(),
                    nav.onTrail ? "on the road line" : "on a local leg"));
            }
        }
        return trespassed;
    }

    /**
     * The first thing on the NPC's way ahead that the current rules deny it, judged from the regions it stands in:
     * a region ({@code region <id>}) on the rest of the engine's path, the hops, the route line or the tail, or a
     * gate door ({@code gate <id>}) the route still has to pass. Null when the way is free. {@code onlyDoors}
     * non-null: a gate event - only those doors are asked about.
     */
    private String deniedAhead(Nav nav, NavPoint at, Set<String> here, AccessSubject subject, Set<Integer> onlyDoors) {
        int from = nav.follower == null ? 0 : nav.onTrail ? nav.follower.nearestIndex(at) : nav.follower.nextIndex();
        for (GateOnLine gate : nav.gates) {
            if (gate.index() + GATE_PASSED_POINTS < from || (onlyDoors != null && !onlyDoors.contains(gate.doorId()))) {
                continue;
            }
            String key = "gate " + gate.doorId();
            if (!nav.tolerated.contains(key) && ports.rules().gateBlocks(subject, gate.doorId())) {
                return key;
            }
        }
        if (onlyDoors != null) {
            return null;
        }
        Predicate<String> mayEnter = null;
        Map<String, Boolean> verdicts = new HashMap<>();
        List<Set<String>> ahead = new java.util.ArrayList<>();
        if (!nav.onTrail) {
            for (NavPoint feet : nav.mover.pathAhead()) {
                ahead.add(regionsAtFeet(feet));
            }
        }
        for (NavPoint hop : nav.hops) {
            ahead.add(regionsAtFloor(hop));
        }
        if (nav.follower != null) {
            List<NavPoint> line = nav.follower.line();
            for (int i = Math.max(0, from); i < Math.min(line.size(), from + RECHECK_AHEAD_POINTS); i++) {
                ahead.add(regionsAtFloor(line.get(i)));
            }
        }
        for (NavPoint hop : nav.tail) {
            ahead.add(regionsAtFloor(hop));
        }
        for (Set<String> regions : ahead) {
            for (String region : regions) {
                if (here.contains(region)) {
                    continue; // a region the NPC is in is not entered
                }
                if (mayEnter == null) {
                    mayEnter = ports.rules().mayEnter(subject);
                }
                if (!verdicts.computeIfAbsent(region, mayEnter::test) && !nav.tolerated.contains("region " + region)) {
                    return "region " + region;
                }
            }
        }
        return null;
    }

    /** Drops the route and plans again from where the NPC stands, through the planning queue (the rules changed). */
    private void replan(Nav nav) {
        nav.generation++;
        dropRoute(nav);
        nav.retries = 0;
        queue.remove(nav);
        nav.state = NpcNavState.WAITING;
        enqueue(nav); // QUEUED before the stop below, so the engine's cancel is not taken for a stuck NPC
        if (nav.mover.isSpawned()) {
            nav.mover.stop();
        }
    }

    private Set<String> regionsAtFeet(NavPoint feet) {
        return ports.world().regionsAt(feet.world(), feet.blockX(), feet.blockY(), feet.blockZ());
    }

    private Set<String> regionsAtFloor(NavPoint floor) {
        return ports.world().regionsAt(floor.world(), floor.blockX(), floor.blockY() + 1, floor.blockZ());
    }

    private AccessSubject subject(Nav nav, String world, Set<String> regions) {
        return new NpcAccessSubject(nav.mover.name(), nav.profile, world, regions);
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
        nav.gates = List.of();
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
        nav.atBlock = false;
        nav.blockedBy = null;
        nav.stillBlocked = null;
        nav.replanFor = null;
        nav.eventDoors = null;
        nav.eventAvailability = false;
        nav.tolerated.clear();
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
