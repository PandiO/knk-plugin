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
import net.knightsandkings.knk.core.npc.nav.corridor.CorridorShaper;
import net.knightsandkings.knk.core.npc.nav.corridor.LocalLegSearch;
import net.knightsandkings.knk.core.npc.nav.crowd.CrowdControl;
import net.knightsandkings.knk.core.npc.nav.crowd.Occupancy;
import net.knightsandkings.knk.core.npc.nav.push.EdgeGuard;
import net.knightsandkings.knk.core.npc.nav.push.PushLedger;
import net.knightsandkings.knk.core.npc.nav.push.PushWatch;

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
    /**
     * The next stretch is handed over once fewer than this many blocks (3D, along the waypoints not yet walked) are
     * left of the current one (link B2: before, the horizontal distance to the stretch end).
     */
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

    /**
     * One NPC's resolved settings (see {@link NavigationSettings}).
     *
     * @param cornerClearance   blocks kept from walls on the inside of a turn (4b.2 item 1); 0 = off
     * @param waypointMargin    how close the NPC comes to a route waypoint before heading for the next (item 2)
     * @param recoveryWaitTicks ticks between two recovery steps (item 7.1)
     */
    public record Settings(double stretchBlocks, double hopBlocks, int noProgressTicks, int maxRetries,
                           int noRouteWaitTicks, double walkOnlyBlocks, int recheckTicks, boolean stopOnTrespass,
                           double cornerClearance, double waypointMargin, int recoveryWaitTicks,
                           boolean corridorSteering, double corridorHalfWidth, double wallClearance,
                           boolean localLegsClearance, boolean plazaLines, int plazaCorridorBlocks,
                           boolean crowdAwareness, boolean collidable, boolean playersPushNpcs, boolean pushReturn,
                           int pushExemptAfter, int pushExemptWindowTicks, int pushExemptTicks,
                           int pushTeleportTicks) {

        /** With collision off and push resistance at its code defaults (plan 4c.4 item 7; link M7). */
        public Settings(double stretchBlocks, double hopBlocks, int noProgressTicks, int maxRetries,
                        int noRouteWaitTicks, double walkOnlyBlocks, int recheckTicks, boolean stopOnTrespass,
                        double cornerClearance, double waypointMargin, int recoveryWaitTicks,
                        boolean corridorSteering, double corridorHalfWidth, double wallClearance,
                        boolean localLegsClearance, boolean plazaLines, int plazaCorridorBlocks,
                        boolean crowdAwareness) {
            this(stretchBlocks, hopBlocks, noProgressTicks, maxRetries, noRouteWaitTicks, walkOnlyBlocks, recheckTicks,
                stopOnTrespass, cornerClearance, waypointMargin, recoveryWaitTicks, corridorSteering, corridorHalfWidth,
                wallClearance, localLegsClearance, plazaLines, plazaCorridorBlocks, crowdAwareness, false,
                NavigationSettings.PLAYERS_PUSH_NPCS.defaultValue(), NavigationSettings.PUSH_RETURN.defaultValue(),
                NavigationSettings.PUSH_EXEMPT_AFTER.defaultValue(),
                NavigationSettings.PUSH_EXEMPT_WINDOW_TICKS.defaultValue(),
                NavigationSettings.PUSH_EXEMPT_TICKS.defaultValue(),
                NavigationSettings.PUSH_TELEPORT_TICKS.defaultValue());
        }

        /** With crowd awareness off (plan 4c.3 decision 3; link M4). */
        public Settings(double stretchBlocks, double hopBlocks, int noProgressTicks, int maxRetries,
                        int noRouteWaitTicks, double walkOnlyBlocks, int recheckTicks, boolean stopOnTrespass,
                        double cornerClearance, double waypointMargin, int recoveryWaitTicks,
                        boolean corridorSteering, double corridorHalfWidth, double wallClearance,
                        boolean localLegsClearance, boolean plazaLines, int plazaCorridorBlocks) {
            this(stretchBlocks, hopBlocks, noProgressTicks, maxRetries, noRouteWaitTicks, walkOnlyBlocks, recheckTicks,
                stopOnTrespass, cornerClearance, waypointMargin, recoveryWaitTicks, corridorSteering, corridorHalfWidth,
                wallClearance, localLegsClearance, plazaLines, plazaCorridorBlocks,
                NavigationSettings.CROWD_AWARENESS.defaultValue());
        }

        /** With plaza lines off (plan 4c.3 decision 6; link M5). */
        public Settings(double stretchBlocks, double hopBlocks, int noProgressTicks, int maxRetries,
                        int noRouteWaitTicks, double walkOnlyBlocks, int recheckTicks, boolean stopOnTrespass,
                        double cornerClearance, double waypointMargin, int recoveryWaitTicks,
                        boolean corridorSteering, double corridorHalfWidth, double wallClearance,
                        boolean localLegsClearance) {
            this(stretchBlocks, hopBlocks, noProgressTicks, maxRetries, noRouteWaitTicks, walkOnlyBlocks, recheckTicks,
                stopOnTrespass, cornerClearance, waypointMargin, recoveryWaitTicks, corridorSteering, corridorHalfWidth,
                wallClearance, localLegsClearance, NavigationSettings.PLAZA_LINES.defaultValue(),
                NavigationSettings.PLAZA_CORRIDOR_BLOCKS.defaultValue());
        }

        /** With clearance-aware local legs off (plan 4c.3 decision 8; link M6). */
        public Settings(double stretchBlocks, double hopBlocks, int noProgressTicks, int maxRetries,
                        int noRouteWaitTicks, double walkOnlyBlocks, int recheckTicks, boolean stopOnTrespass,
                        double cornerClearance, double waypointMargin, int recoveryWaitTicks,
                        boolean corridorSteering, double corridorHalfWidth, double wallClearance) {
            this(stretchBlocks, hopBlocks, noProgressTicks, maxRetries, noRouteWaitTicks, walkOnlyBlocks, recheckTicks,
                stopOnTrespass, cornerClearance, waypointMargin, recoveryWaitTicks, corridorSteering, corridorHalfWidth,
                wallClearance, NavigationSettings.LOCAL_LEGS_CLEARANCE.defaultValue());
        }

        /** With corridor steering off (plan 4c.3 decision 1; its keys at their code defaults). */
        public Settings(double stretchBlocks, double hopBlocks, int noProgressTicks, int maxRetries,
                        int noRouteWaitTicks, double walkOnlyBlocks, int recheckTicks, boolean stopOnTrespass,
                        double cornerClearance, double waypointMargin, int recoveryWaitTicks) {
            this(stretchBlocks, hopBlocks, noProgressTicks, maxRetries, noRouteWaitTicks, walkOnlyBlocks, recheckTicks,
                stopOnTrespass, cornerClearance, waypointMargin, recoveryWaitTicks,
                NavigationSettings.CORRIDOR_STEERING.defaultValue(),
                NavigationSettings.CORRIDOR_HALF_WIDTH.defaultValue(), NavigationSettings.WALL_CLEARANCE.defaultValue());
        }

        /** With the code defaults for the Phase 3 settings ({@code recheck-ticks}, {@code stop-on-trespass}). */
        public Settings(double stretchBlocks, double hopBlocks, int noProgressTicks, int maxRetries,
                        int noRouteWaitTicks, double walkOnlyBlocks) {
            this(stretchBlocks, hopBlocks, noProgressTicks, maxRetries, noRouteWaitTicks, walkOnlyBlocks,
                NavigationSettings.RECHECK_TICKS.defaultValue(), NavigationSettings.STOP_ON_TRESPASS.defaultValue());
        }

        /** With the code defaults for the 4b.2 settings (corner clearance, waypoint margin, recovery wait). */
        public Settings(double stretchBlocks, double hopBlocks, int noProgressTicks, int maxRetries,
                        int noRouteWaitTicks, double walkOnlyBlocks, int recheckTicks, boolean stopOnTrespass) {
            this(stretchBlocks, hopBlocks, noProgressTicks, maxRetries, noRouteWaitTicks, walkOnlyBlocks, recheckTicks,
                stopOnTrespass, NavigationSettings.CORNER_CLEARANCE.defaultValue(),
                NavigationSettings.WAYPOINT_MARGIN.defaultValue(), NavigationSettings.RECOVERY_WAIT_TICKS.defaultValue());
        }

        public static Settings of(NavigationSettings settings, NavigationSettings.Scope scope) {
            return new Settings(settings.value(NavigationSettings.STRETCH_BLOCKS, scope),
                settings.value(NavigationSettings.HOP_BLOCKS, scope),
                settings.value(NavigationSettings.NO_PROGRESS_TICKS, scope),
                settings.value(NavigationSettings.MAX_RETRIES, scope),
                settings.value(NavigationSettings.NO_ROUTE_WAIT_TICKS, scope),
                settings.value(NavigationSettings.WALK_ONLY_BLOCKS, scope),
                settings.value(NavigationSettings.RECHECK_TICKS, scope),
                settings.value(NavigationSettings.STOP_ON_TRESPASS, scope),
                settings.value(NavigationSettings.CORNER_CLEARANCE, scope),
                settings.value(NavigationSettings.WAYPOINT_MARGIN, scope),
                settings.value(NavigationSettings.RECOVERY_WAIT_TICKS, scope),
                settings.value(NavigationSettings.CORRIDOR_STEERING, scope),
                settings.value(NavigationSettings.CORRIDOR_HALF_WIDTH, scope),
                settings.value(NavigationSettings.WALL_CLEARANCE, scope),
                settings.value(NavigationSettings.LOCAL_LEGS_CLEARANCE, scope),
                settings.value(NavigationSettings.PLAZA_LINES, scope),
                settings.value(NavigationSettings.PLAZA_CORRIDOR_BLOCKS, scope),
                settings.value(NavigationSettings.CROWD_AWARENESS, scope),
                settings.value(NavigationSettings.COLLIDABLE, scope),
                settings.value(NavigationSettings.PLAYERS_PUSH_NPCS, scope),
                settings.value(NavigationSettings.PUSH_RETURN, scope),
                settings.value(NavigationSettings.PUSH_EXEMPT_AFTER, scope),
                settings.value(NavigationSettings.PUSH_EXEMPT_WINDOW_TICKS, scope),
                settings.value(NavigationSettings.PUSH_EXEMPT_TICKS, scope),
                settings.value(NavigationSettings.PUSH_TELEPORT_TICKS, scope));
        }

        /** The waypoint margin for a stretch: {@link CorridorShaper#CHOKE_MARGIN} with an aligned chokepoint. */
        double marginFor(RouteFollower.Stretch stretch) {
            return stretch.chokepoints() > 0 ? Math.min(waypointMargin, CorridorShaper.CHOKE_MARGIN) : waypointMargin;
        }

        public static Settings defaults() {
            return of(NavigationSettings.defaults(), NavigationSettings.Scope.of(null));
        }

        /** These settings with bodies on or off ({@code collidable}), the push resistance keys kept. */
        public Settings withCollidable(boolean on) {
            return new Settings(stretchBlocks, hopBlocks, noProgressTicks, maxRetries, noRouteWaitTicks, walkOnlyBlocks,
                recheckTicks, stopOnTrespass, cornerClearance, waypointMargin, recoveryWaitTicks, corridorSteering,
                corridorHalfWidth, wallClearance, localLegsClearance, plazaLines, plazaCorridorBlocks, crowdAwareness,
                on, playersPushNpcs, pushReturn, pushExemptAfter, pushExemptWindowTicks, pushExemptTicks,
                pushTeleportTicks);
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
        /** The waypoint list last given for the road line, with how far the NPC came along it (link B2); null otherwise. */
        Trail trail;
        /** A step of the line the bridge could not make: walked as a local leg once {@link #trail} is walked (B2). */
        NavPoint gapLeg;
        /** The local leg of {@link #gapLeg} runs: the line restarts where the NPC stands once it is walked. */
        boolean afterGap;
        boolean recovering;
        long lastProgressTick;
        /** The waypoints of the running local leg K&K's own search found (link M6); null for any other order. */
        List<NavPoint> knkLeg;
        /** The rest of {@link #knkLeg} at the last progress, for its watchdog. */
        double knkLegRest = Double.NaN;
        /** A local leg handed to the engine's search whose outcome (a path, or none) is not counted yet. */
        boolean engineLegOpen;
        // ---- stuck spells and the recovery ladder (4b.2 items 6 and 7) ----
        final RecoveryLadder ladder = new RecoveryLadder();
        final RecentSteps steps = new RecentSteps();
        /** The next try of the open spell waits for {@link #waitUntil} (state WAITING). */
        boolean ladderPending;
        /** A back-off or side-step move without the line (a local-leg NPC): its hop is tried again after it. */
        boolean moveOnly;
        /** The progress mark of the running try, measured when the NPC first moves on it. */
        double tryMark = Double.NaN;
        /** What {@link #tryMark} was measured against: the follower, or the hop target. */
        Object tryMarkRef;
        long lastStepTick;
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
        /** A road plan for this NPC is in flight. */
        boolean roadPlanning;
        /** Elements the recheck saw ahead that the router then still routed through: not asked about again. */
        final Set<String> tolerated = new HashSet<>();
        // ---- crowd awareness (plan 4c.3 decision 3, link M4) ----
        /** Held by crowd awareness: waiting for its turn at a narrow place, stepping aside, or behind another NPC. */
        boolean crowdHeld;
        /** How a held NPC goes on when it may. */
        Resume crowdResume = Resume.REPLAN;
        /** The queue spot a held NPC was sent to, or null. */
        NavPoint crowdSpot;
        /** A held NPC behind another (no narrow place) looks again at this tick; MAX_VALUE for a queue. */
        long crowdRetryTick = Long.MAX_VALUE;
        /** When this NPC's current wait behind another began (one count per wait), -1 when none. */
        long behindSince = -1;
        /** The last tick it was seen behind another NPC. */
        long lastBehindTick = Long.MIN_VALUE / 2;
        /** The running engine leg's target and capture, so a held NPC walks it on without a new capture. */
        NavPoint legTarget;
        LocalLeg legPrepared;
        // ---- push resistance (plan 4c.4 item 7, link M7) ----
        /** Push detection from this NPC's own movement. */
        final PushWatch push = new PushWatch();
        /** Where a pushed NPC walks back to (its line or post), or null when it is not returning. */
        NavPoint returnTo;
        /** When the running return began. */
        long returnSince;
        /** The return is a walk of the body only (the NPC stood: no order, waiting, held), not a step of its order. */
        boolean bodyReturn;

        Nav(NpcMover mover, NpcAccessProfile profile, Settings settings, String world) {
            this.mover = mover;
            this.profile = profile;
            this.settings = settings;
            this.world = world;
        }

        @Override
        public void arrived(NpcMover m) {
            timed(() -> {
                settleEngineLeg(this, false);
                if (state == NpcNavState.MOVING) {
                    if (moveOnly) {
                        // a back-off or side-step of an NPC on a local leg: its hop again from here
                        moveOnly = false;
                        enqueue(this);
                        return;
                    }
                    legDone(this);
                }
            });
        }

        @Override
        public void stuck(NpcMover m) {
            stuck(m, NpcMover.StuckKind.STATIONARY);
        }

        @Override
        public void stuck(NpcMover m, NpcMover.StuckKind kind) {
            timed(() -> {
                if (state == NpcNavState.MOVING && kind == NpcMover.StuckKind.STATIONARY && !ladder.isOpen()
                    && holdBehind(this)) {
                    return; // behind another NPC: a wait, not a stuck spell (link M4)
                }
                settleEngineLeg(this, kind == NpcMover.StuckKind.NO_PATH);
                if (state == NpcNavState.MOVING) {
                    onStuck(this, kind == NpcMover.StuckKind.NO_PATH ? RecoveryLadder.Reporter.NO_PATH
                        : RecoveryLadder.Reporter.STATIONARY);
                } else if (goal == null && idleStuck != null) {
                    idleStuck.accept(mover.id(), kind); // a group's follower on waypoints of its group
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
                    spellNotFreed(this, "its chunk unloaded");
                    clearCrowd(this);
                    ladderPending = false;
                    moveOnly = false;
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
                if (settings.collidable()) {
                    collision.enroll(mover, settings.playersPushNpcs()); // the entity is new: its exemptions are gone
                }
                returnTo = null;
                bodyReturn = false;
                mover.position().ifPresent(at -> push.reset(at, false));
                if (state == NpcNavState.PAUSED) {
                    ports.metrics().count(Event.RESUMED);
                    // the entity is new and may stand somewhere else: plan again from where it is
                    dropRoute(this);
                    steps.clear();
                    regions = null;
                    waitTicks(this, 1);
                }
            });
        }

        @Override
        public void contact(NpcMover m, NpcMover.Contact contact) {
            push.contact(contact, tick);
        }

        @Override
        public void engineRuleRemoved(NpcMover m, String names) {
            ports.metrics().count(Event.EXAMINER_LEAK);
            ports.log().accept(m.name() + " engine block rules removed from a local search: " + names);
        }
    }

    private record GateOnLine(int doorId, int index) {
    }

    /** How an NPC held by crowd awareness goes on (link M4). */
    private enum Resume {
        /** Its stretch again from where it stands. */
        TRAIL,
        /** The rest of its K&K local leg. */
        KNK_LEG,
        /** Its engine leg again, with the same capture. */
        ENGINE_LEG,
        /** Plan again from where it stands. */
        REPLAN
    }

    private final Ports ports;
    /** Stuck reports for NPCs without an order of their own here (a group's followers on their group's waypoints). */
    private java.util.function.BiConsumer<Integer, NpcMover.StuckKind> idleStuck;
    private final int maxPlansInFlight;
    private final int ruleChecksPerTick;
    private int ruleCursor;
    private final Map<Integer, Nav> npcs = new LinkedHashMap<>();
    private final Deque<Nav> queue = new ArrayDeque<>();
    private int inFlight;
    private int localInFlight;
    private long tick;
    /** Corridor steering's shaped stretches, shared by every NPC here (plan 4c.3 decision 1.5). */
    /** Blocks a K&K local leg must get shorter between two watchdog looks to count as progress. */
    static final double KNK_LEG_PROGRESS = 0.5;
    private final CorridorShaper corridors;
    /** K&K's own local leg search (plan 4c.3 decision 8, link M6). */
    private final LocalLegSearch localLegs = new LocalLegSearch();
    /** Who stands where, rebuilt every 5 ticks while an NPC here has crowd awareness on (link M4). */
    private final Occupancy occupancy = new Occupancy();
    /** Narrow places taken in turns (link M4). */
    private final CrowdControl crowd = new CrowdControl(occupancy);
    /** Main-thread time of crowd awareness's updates (the hash and the turns), for its share of the cost. */
    private long crowdNanos;
    private java.util.function.IntPredicate unshaped = id -> false;
    /** Who pushes whom (link M7); the engine's adapter. */
    private NpcCollisionPolicy collision = NpcCollisionPolicy.NONE;
    /** Whether a player sees a place, for the teleport home; nobody is asked without a port: never teleport. */
    private NpcSight sight = NpcSight.EVERYBODY;
    /** Repeated pushes per player and NPC, and the exemptions they gave (link M7). */
    private final PushLedger pushLedger = new PushLedger();
    /** Group followers (their group's slot walks bring them back after a push). */
    private java.util.function.IntPredicate groupFollower = id -> false;
    private long pushes;
    private long pushesByPlayers;
    private long pushesByAttacks;
    private long pushReturns;
    private long pushTeleports;
    /** Steps of the line the bridge could not make, walked as local legs (link B2). */
    private long gapLegs;
    /** Main-thread time of push resistance's looks, for its share of the cost. */
    private long pushNanos;
    private java.util.function.IntToDoubleFunction plazaClearance = id -> Double.NaN;
    private long busyNanos;
    private long lastTickNanos;
    private int depth;

    public NpcNavigationService(Ports ports, int maxPlansInFlight) {
        this(ports, maxPlansInFlight, DEFAULT_RULE_CHECKS_PER_TICK);
    }

    /** @param ruleChecksPerTick NPCs whose rules are checked per tick at most (NAV-13: never all at once) */
    public NpcNavigationService(Ports ports, int maxPlansInFlight, int ruleChecksPerTick) {
        crowd.setBodies(id -> {
            Nav nav = npcs.get(id);
            return nav != null && nav.settings.collidable();
        });
        this.ports = Objects.requireNonNull(ports, "ports");
        if (maxPlansInFlight < 1) {
            throw new IllegalArgumentException("maxPlansInFlight must be at least 1");
        }
        if (ruleChecksPerTick < 1) {
            throw new IllegalArgumentException("ruleChecksPerTick must be at least 1");
        }
        this.maxPlansInFlight = maxPlansInFlight;
        this.ruleChecksPerTick = ruleChecksPerTick;
        this.corridors = new CorridorShaper(() -> tick, ports.nanoTime());
    }

    /**
     * Corridor steering's shared shaper (plan 4c.3 decision 1, link M2): its counts and cost, and for later links the
     * corridors it built.
     */
    public CorridorShaper corridors() {
        return corridors;
    }

    /**
     * The movers whose stretches corridor steering leaves alone although their key is on: a group's members, until
     * link M3 fits formations to the corridor (the group path follows the leader's route line).
     */
    public void setUnshapedMembers(java.util.function.IntPredicate unshaped) {
        this.unshaped = unshaped == null ? id -> false : unshaped;
    }

    /**
     * The clearance a group member's plaza lines keep (link M5): the group's formation half width plus
     * {@code wall-clearance} for its leader, so the formation fits round a well; NaN for {@code wall-clearance}.
     */
    public void setPlazaClearance(java.util.function.IntToDoubleFunction clearance) {
        this.plazaClearance = clearance == null ? id -> Double.NaN : clearance;
    }

    /**
     * The corridor steering an NPC's stretches get: null when its {@code corridor-steering} and {@code plaza-lines} are
     * off. A group member, and an NPC with only {@code plaza-lines} on, get plaza lines only (link M5).
     */
    private RouteFollower.Steering steering(Nav nav) {
        CorridorShaper.Options plazas = plazaOptions(nav.mover.id());
        if (nav.settings.corridorSteering() && !unshaped.test(nav.mover.id())) {
            CorridorShaper.Options options = new CorridorShaper.Options(nav.settings.corridorHalfWidth(),
                nav.settings.wallClearance());
            return new RouteFollower.Steering(corridors, plazas == null ? options
                : options.withPlazas(plazas.plazaBlocks(), plazas.plazaClearance()));
        }
        return plazas == null ? null : new RouteFollower.Steering(corridors, plazas);
    }

    /**
     * Plaza lines only, as a mover's settings ask for them (link M5): null when its {@code plaza-lines} is off or it is
     * not registered. A group's path asks with its leader's id.
     */
    public CorridorShaper.Options plazaOptions(int moverId) {
        Nav nav = npcs.get(moverId);
        if (nav == null || !nav.settings.plazaLines()) {
            return null;
        }
        double clearance = plazaClearance.applyAsDouble(moverId);
        return CorridorShaper.Options.plazasOnly(nav.settings.corridorHalfWidth(), nav.settings.wallClearance(),
            nav.settings.plazaCorridorBlocks(), Double.isNaN(clearance) ? nav.settings.wallClearance()
                : Math.max(clearance, nav.settings.wallClearance()));
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
        if (settings.collidable() && mover.isSpawned()) {
            collision.enroll(mover, settings.playersPushNpcs());
        }
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
        nav.returnTo = null;
        nav.bodyReturn = false;
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
        crowd.forget(moverId);
        pushLedger.forget(moverId);
        if (nav.settings.collidable()) {
            collision.release(moverId);
        }
        nav.mover.setListener(null);
    }

    public int count() {
        return npcs.size();
    }

    /**
     * Who hears the engine's stuck reports for registered NPCs that have no order here (a group's followers walk
     * their group's waypoints without one, implementation plan 4b.2 item 9). Null: nobody.
     */
    public void setIdleStuckListener(java.util.function.BiConsumer<Integer, NpcMover.StuckKind> listener) {
        this.idleStuck = listener;
    }

    /** Whether a mover waits for or runs a road plan now (the stats' road plans per group, 4b.2 item 14). */
    public boolean planningRoad(int moverId) {
        Nav nav = npcs.get(moverId);
        return nav != null && nav.roadPlanning;
    }

    /** Local legs being prepared now, server-wide. */
    public int localLegsInFlight() {
        return localInFlight;
    }

    /** Whether a mover has an open stuck spell (diagnostics). */
    public boolean stuckSpellOpen(int moverId) {
        Nav nav = npcs.get(moverId);
        return nav != null && nav.ladder.isOpen();
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
     * The waypoint list last given to the NPC for the road line (a stretch appended to what was left of the one before,
     * link B2), or null while it walks anything else (diagnostics and scenarios).
     */
    public List<NavPoint> trailOf(int moverId) {
        Nav nav = npcs.get(moverId);
        return nav == null || nav.trail == null ? null : nav.trail.points();
    }

    /** Steps of the line the bridge could not make, walked as local legs since start (link B2; diagnostics). */
    public long gapLegs() {
        return gapLegs;
    }

    /** The feet, with the NPC's place on its waypoint list brought up to date. */
    private static NavPoint trailUpdated(Nav nav, NavPoint at) {
        if (nav.trail != null) {
            nav.trail.update(at);
        }
        return at;
    }

    /**
     * The gate doors on the NPC's current route, passed or still ahead, in route order (PandiO/knk-workspace#507: a
     * group's followers walk behind the leader through gates the leader has passed); empty without a route.
     */
    public List<Integer> gateDoorsOf(int moverId) {
        Nav nav = npcs.get(moverId);
        if (nav == null || nav.gates.isEmpty()) {
            return List.of();
        }
        List<Integer> doors = new java.util.ArrayList<>(nav.gates.size());
        for (GateOnLine gate : nav.gates) {
            doors.add(gate.doorId());
        }
        return doors;
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
            if (tick % Occupancy.UPDATE_TICKS == 0 && anyCrowdAware(all)) {
                long crowdStart = ports.nanoTime().getAsLong();
                crowdUpdate(all);
                crowdNanos += ports.nanoTime().getAsLong() - crowdStart;
            }
            for (Nav nav : all) {
                switch (nav.state) {
                    case WAITING -> {
                        if (tick >= nav.waitUntil) {
                            if (nav.ladderPending) {
                                nav.ladderPending = false;
                                nextTry(nav);
                            } else {
                                enqueue(nav);
                            }
                        }
                    }
                    case MOVING -> {
                        watch(nav);
                        if (nav.state == NpcNavState.MOVING) {
                            supervise(nav);
                        }
                    }
                    default -> { }
                }
            }
            pushLooks(all);
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

    private static boolean anyCrowdAware(List<Nav> all) {
        for (Nav nav : all) {
            if (nav.settings.crowdAwareness()) {
                return true;
            }
        }
        return false;
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
        if (nav.mover.isSpawned() && (tick + nav.mover.id()) % HANDOVER_TICKS == 0) {
            footstep(nav);
        }
        if (nav.knkLeg != null && !nav.onTrail && !nav.moveOnly && nav.state == NpcNavState.MOVING
            && nav.mover.isSpawned()) {
            watchKnkLeg(nav);
            return;
        }
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
                nav.lastProgressTick = tick;
                if (!(nav.settings.crowdAwareness() && behind(nav, at, wayAhead(nav, at)))) {
                    onStuck(nav, RecoveryLadder.Reporter.NO_PROGRESS);
                    return;
                }
            }
        }
        if (handover && nav.gapLeg == null) {
            // by progress along the waypoints in 3D (link B2, decision 34.1): on a stacked staircase the stretch end
            // can be right above the NPC and still a flight away
            NavPoint end = nav.follower.sentEndPoint();
            double rest = nav.trail != null ? nav.trail.rest(trailUpdated(nav, at))
                : end == null ? Double.MAX_VALUE : Trail.dist3(at, HopPlanner.feet(end));
            if (rest < HANDOVER_DISTANCE) {
                sendStretch(nav, false);
            }
        }
    }

    /**
     * The watchdog of a K&K local leg (link M6): the engine reports no stuck for waypoint lists (NAV-17), so progress
     * along the leg is watched here as on the line; while a recovery try runs, {@link #supervise} judges it instead.
     */
    private void watchKnkLeg(Nav nav) {
        if ((tick + nav.mover.id()) % WATCHDOG_TICKS != 0 || nav.ladder.tryRunning()) {
            return;
        }
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null) {
            return;
        }
        double rest = restOf(nav.knkLeg, at);
        if (Double.isNaN(nav.knkLegRest) || rest < nav.knkLegRest - KNK_LEG_PROGRESS) {
            nav.knkLegRest = rest;
            nav.lastProgressTick = tick;
        } else if (tick - nav.lastProgressTick >= nav.settings.noProgressTicks()) {
            nav.lastProgressTick = tick;
            if (!(nav.settings.crowdAwareness() && behind(nav, at, wayAhead(nav, at)))) {
                onStuck(nav, RecoveryLadder.Reporter.NO_PROGRESS);
            }
        }
    }

    /** The length of a waypoint list still to walk from {@code at}: to the nearest point of it, then along it. */
    static double restOf(List<NavPoint> way, NavPoint at) {
        double best = Double.MAX_VALUE;
        double tail = 0;
        double bestRest = 0;
        for (int i = way.size() - 1; i >= 0; i--) {
            NavPoint p = way.get(i);
            if (i < way.size() - 1) {
                tail += p.horizontalDistance(way.get(i + 1));
            }
            double d = Math.sqrt(sq(p.x() - at.x()) + sq(p.y() - at.y()) + sq(p.z() - at.z()));
            if (d + tail < best) {
                best = d + tail;
                bestRest = best;
            }
        }
        return bestRest;
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
        if (PathTargets.shortTrip(from, to, nav.settings.hopBlocks())) {
            // a short trip: one local leg, no road (near and on about the same floor, 4b.2 item 5.4)
            nav.replanFor = null;
            nav.blockedBy = null;
            nav.stillBlocked = null;
            nav.hops.add(HopPlanner.snapToFloor(ports.world(), to));
            planHop(nav, generation, at);
            return;
        }
        long start = ports.nanoTime().getAsLong();
        inFlight++;
        nav.roadPlanning = true;
        CompletableFuture<RoadPlanner.RoadPlan> planning;
        try {
            planning = ports.roads().plan(subject(nav, at), from, to);
        } catch (RuntimeException e) {
            planning = CompletableFuture.failedFuture(e);
        }
        planning.whenComplete((plan, error) -> timed(() -> {
            inFlight = Math.max(0, inFlight - 1);
            nav.roadPlanning = false;
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
                startRoute(nav, generation, at, plan.line(), plan.gates(), plan.plazas(), from, to);
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
                    startRoute(nav, generation, at, partial, plan.gates(), plan.plazas(), from, null);
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
                            List<RoadPlanner.GateCrossing> gates, List<RoadPlanner.PlazaCrossing> plazas,
                            NavPoint from, NavPoint to) {
        nav.follower = new RouteFollower(line, plazas);
        nav.trail = null;
        nav.gapLeg = null;
        nav.afterGap = false;
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
        localInFlight++;
        CompletableFuture<LocalLegPreparer.Prepared> preparing;
        try {
            preparing = ports.legs().prepare(subject(nav, at), at, target);
        } catch (RuntimeException e) {
            preparing = CompletableFuture.failedFuture(e);
        }
        preparing.whenComplete((prepared, error) -> timed(() -> {
            inFlight = Math.max(0, inFlight - 1);
            localInFlight = Math.max(0, localInFlight - 1);
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
                    settleEngineLeg(nav, false);
                    nav.knkLeg = null;
                    List<NavPoint> way = nav.settings.localLegsClearance() ? knkLeg(nav, at, target, prepared.leg())
                        : null;
                    nav.state = NpcNavState.MOVING;
                    if (way != null) {
                        // K&K's own leg with the corridor's clearance (plan 4c.3 decision 8, link M6)
                        ports.metrics().count(Event.LOCAL_LEG_KNK);
                        nav.knkLeg = way;
                        nav.knkLegRest = Double.NaN;
                        nav.lastProgressTick = tick;
                        nav.trail = null;
                        nav.mover.follow(NavGeometry.onSurface(ports.world(), way), nav.settings.waypointMargin());
                    } else {
                        nav.engineLegOpen = true; // counted engine or none once the engine's search answered
                        nav.legTarget = target;
                        nav.legPrepared = prepared.leg();
                        nav.trail = null;
                        nav.mover.walkTo(HopPlanner.feet(target), prepared.leg());
                    }
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
     * K&K's own search for a local leg (link M6): the waypoints from {@code at} to the hop {@code target}, or null when
     * it found none within its budget (the engine's search takes the leg then). The NPC's position at the time the leg
     * is handed over is used, since the preparation took a while.
     */
    private List<NavPoint> knkLeg(Nav nav, NavPoint planned, NavPoint target, LocalLeg leg) {
        NavPoint at = nav.mover.position().orElse(planned);
        LocalLegSearch.Result result;
        try {
            int self = nav.mover.id();
            result = localLegs.find(ports.world(), at, target, leg, nav.settings.wallClearance(),
                nav.settings.crowdAwareness() ? (x, y, z) -> occupancy.occupied(at.world(), x, y, z, self)
                    ? LocalLegSearch.OCCUPIED_COST : 0 : null);
        } catch (RuntimeException e) {
            ports.log().accept(nav.mover.name() + " K&K local leg search failed: " + e);
            return null;
        }
        if (!result.found()) {
            ports.log().accept(String.format(Locale.ROOT, "%s local leg from %s to %s: K&K found no path (%s, %d cells),"
                    + " the engine searches", nav.mover.name(), NavGeometry.block(at),
                NavGeometry.block(HopPlanner.feet(target)), result.reason(), result.expansions()));
            return null;
        }
        return result.waypoints();
    }

    /** The outcome of the open engine leg, counted once: no path when the engine found none, else engine. */
    private void settleEngineLeg(Nav nav, boolean noPath) {
        if (!nav.engineLegOpen) {
            return;
        }
        nav.engineLegOpen = false;
        ports.metrics().count(noPath ? Event.LOCAL_LEG_NONE : Event.LOCAL_LEG_ENGINE);
    }

    /** K&K's local legs (link M6): their search counters. */
    public LocalLegSearch localLegs() {
        return localLegs;
    }

    // ==================== crowd awareness (plan 4c.3 decision 3, link M4) ====================

    /** A held NPC behind another looks again after this many ticks. */
    static final int BEHIND_RETRY_TICKS = 20;
    /** Blocks ahead along its way in which another NPC's cell makes an NPC "behind" it. */
    static final double BEHIND_BLOCKS = 1.5;

    /** The narrow places and their queues (link M4). */
    public CrowdControl crowd() {
        return crowd;
    }

    /** Who stands where (link M4), as of the last rebuild. */
    public Occupancy occupancy() {
        return occupancy;
    }

    /** Main-thread time crowd awareness's updates took so far (the hash, the turns, the holds), in nanoseconds. */
    public long crowdNanos() {
        return crowdNanos;
    }

    /** Whether a registered mover has {@code crowd-awareness} on. */
    public boolean crowdAwareness(int moverId) {
        Nav nav = npcs.get(moverId);
        return nav != null && nav.settings.crowdAwareness();
    }

    /** Whether crowd awareness holds a mover now (it waits for its turn, steps aside, or stands behind another). */
    public boolean crowdHeld(int moverId) {
        Nav nav = npcs.get(moverId);
        return nav != null && nav.crowdHeld;
    }

    /**
     * A group leader's followers, by the leader's mover id (empty for anyone else): the leader keeps its turn at a
     * narrow place until its followers are through.
     */
    public void setGroupMembers(java.util.function.IntFunction<int[]> members) {
        crowd.setGroupMembers(members);
    }

    /**
     * Whether a mover with {@code crowd-awareness} on stands behind another NPC: a cell within
     * {@link #BEHIND_BLOCKS} along {@code wayAhead} (feet points from the mover on) has another NPC in it. When it does,
     * the wait is counted once per wait ({@code crowd_wait}) and the caller does not count it as stuck (decision 3.4:
     * a group's follower behind a waiting one). False with the key off, and after {@link CrowdControl#MAX_WAIT_TICKS}
     * of waiting behind the same jam (it is stuck then).
     */
    public boolean waitsBehind(int moverId, NavPoint at, List<NavPoint> wayAhead) {
        Nav nav = npcs.get(moverId);
        if (nav == null || !nav.settings.crowdAwareness() || at == null) {
            return false;
        }
        return behind(nav, at, wayAhead);
    }

    private boolean behind(Nav nav, NavPoint at, List<NavPoint> wayAhead) {
        if (!occupiedAhead(nav, at, wayAhead)) {
            return false;
        }
        boolean newWait = tick - nav.lastBehindTick > nav.settings.noProgressTicks() + WATCHDOG_TICKS;
        if (newWait) {
            nav.behindSince = tick;
            countCrowdWait();
            ports.log().accept(String.format(Locale.ROOT, "%s waits behind another NPC at %s (not stuck)",
                nav.mover.name(), NavGeometry.block(at)));
        } else if (nav.behindSince >= 0 && tick - nav.behindSince > CrowdControl.MAX_WAIT_TICKS) {
            return false; // the same jam for 30 s: stuck after all
        }
        nav.lastBehindTick = tick;
        return true;
    }

    private void countCrowdWait() {
        crowd.countWait();
        ports.metrics().count(Event.CROWD_WAIT);
    }

    /** Whether another NPC stands in a cell within {@link #BEHIND_BLOCKS} along the way (from 0.5 on). */
    private boolean occupiedAhead(Nav nav, NavPoint at, List<NavPoint> way) {
        int self = nav.mover.id();
        NavPoint prev = at;
        double walked = 0;
        double next = 0.5;
        for (NavPoint p : way) {
            if (!p.world().equals(at.world())) {
                break;
            }
            double len = Math.hypot(p.x() - prev.x(), p.z() - prev.z());
            while (len > 1e-6 && next <= walked + len && next <= BEHIND_BLOCKS) {
                double t = (next - walked) / len;
                double x = prev.x() + (p.x() - prev.x()) * t;
                double z = prev.z() + (p.z() - prev.z()) * t;
                int y = (int) Math.floor(prev.y() + (p.y() - prev.y()) * t + 1e-6);
                if (occupancy.occupied(at.world(), (int) Math.floor(x), y, (int) Math.floor(z), self)
                    && !(Math.floor(x) == at.blockX() && Math.floor(z) == at.blockZ())) {
                    return true;
                }
                next += 0.5;
            }
            walked += len;
            prev = p;
            if (next > BEHIND_BLOCKS) {
                break;
            }
        }
        return false;
    }

    /** Whether crowd awareness keeps a recovery step of this NPC off a feet cell: another NPC stands there. */
    private boolean crowdOccupied(Nav nav, NavPoint feet) {
        return nav.settings.crowdAwareness() && occupancy.occupied(feet, nav.mover.id());
    }

    /** The feet points of an NPC's way ahead (about 8 blocks): its route line, its K&K leg or the engine's path. */
    private List<NavPoint> wayAhead(Nav nav, NavPoint at) {
        List<NavPoint> out = new java.util.ArrayList<>();
        if (nav.onTrail && nav.follower != null) {
            List<NavPoint> line = nav.follower.line();
            double walked = 0;
            NavPoint prev = at;
            for (int i = nav.follower.nearestIndex(at) + 1; i < line.size() && walked < 8; i++) {
                NavPoint feet = HopPlanner.feet(line.get(i));
                walked += prev.horizontalDistance(feet);
                out.add(feet);
                prev = feet;
            }
            return out;
        }
        List<NavPoint> way = nav.knkLeg != null ? nav.knkLeg : nav.mover.pathAhead();
        if (nav.knkLeg != null) {
            int start = nearestOf(way, at);
            return way.subList(Math.min(way.size(), start + 1), way.size());
        }
        return way;
    }

    private static int nearestOf(List<NavPoint> way, NavPoint at) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < way.size(); i++) {
            double d = way.get(i).horizontalDistance(at);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Rebuilds the occupancy and lets each walking NPC with the key on ask about the narrow place ahead. */
    private void crowdUpdate(List<Nav> all) {
        long start = ports.nanoTime().getAsLong();
        occupancy.begin();
        for (Nav nav : all) {
            if (nav.mover.isSpawned()) {
                NavPoint at = nav.mover.position().orElse(null);
                if (at != null) {
                    occupancy.add(nav.mover.id(), at);
                }
            }
        }
        occupancy.finish(ports.nanoTime().getAsLong() - start);
        crowd.housekeeping(tick);
        for (Nav nav : all) {
            if (!nav.settings.crowdAwareness() || nav.goal == null || !nav.mover.isSpawned()
                || npcs.get(nav.mover.id()) != nav) {
                continue;
            }
            NavPoint at = nav.mover.position().orElse(null);
            if (at == null) {
                continue;
            }
            if (nav.crowdHeld) {
                heldLook(nav, at);
                continue;
            }
            if (nav.state != NpcNavState.MOVING || nav.ladder.isOpen() || nav.moveOnly) {
                continue;
            }
            List<NavPoint> way = wayAhead(nav, at);
            if (way.isEmpty()) {
                continue;
            }
            CrowdControl.Decision d = crowd.decide(ports.world(), nav.mover.id(), at, way, tick);
            switch (d.kind()) {
                case GO -> {
                    if (d.overdue()) {
                        ports.log().accept(nav.mover.name() + " waited " + CrowdControl.MAX_WAIT_TICKS / 20
                            + " s at a narrow place: goes");
                    }
                }
                case WAIT, YIELD -> hold(nav, at, d);
            }
        }
    }

    /** Holds an NPC for its turn at a narrow place: it stops and walks to its queue spot (decision 3.2, 3.3). */
    private void hold(Nav nav, NavPoint at, CrowdControl.Decision d) {
        nav.crowdHeld = true;
        nav.crowdResume = nav.onTrail && nav.follower != null ? Resume.TRAIL
            : nav.knkLeg != null ? Resume.KNK_LEG
            : nav.legTarget != null && nav.legPrepared != null ? Resume.ENGINE_LEG : Resume.REPLAN;
        nav.crowdRetryTick = Long.MAX_VALUE;
        nav.crowdSpot = null;
        countCrowdWait();
        NavPoint middle = d.place().centre();
        if (d.kind() == CrowdControl.Kind.YIELD) {
            ports.metrics().count(Event.CROWD_YIELD);
            ports.log().accept(String.format(Locale.ROOT, "%s meets an oncoming NPC in the narrow place at %s: steps "
                + "back out and waits (yield)", nav.mover.name(), NavGeometry.block(middle)));
        } else {
            ports.log().accept(String.format(Locale.ROOT, "%s waits for its turn at the narrow place at %s (queue %d)",
                nav.mover.name(), NavGeometry.block(middle), d.queueIndex() + 1));
        }
        nav.state = NpcNavState.WAITING; // before the stop: a held NPC is neither moving nor stuck
        nav.waitUntil = Long.MAX_VALUE;
        nav.trail = null;
        nav.mover.stop();
        toSpot(nav, at, d.spot());
    }

    private void toSpot(Nav nav, NavPoint at, NavPoint spot) {
        if (spot == null || (nav.crowdSpot != null && nav.crowdSpot.horizontalDistance(spot) < 0.4)) {
            return;
        }
        nav.crowdSpot = spot;
        if (at.horizontalDistance(spot) > 0.4) {
            nav.trail = null;
            nav.mover.follow(NavGeometry.onSurface(ports.world(), List.of(spot)), 0.3);
        }
    }

    /** A held NPC looks again: its turn, or (behind another) the way ahead is free. */
    private void heldLook(Nav nav, NavPoint at) {
        if (nav.crowdRetryTick != Long.MAX_VALUE) {
            if (tick >= nav.crowdRetryTick) {
                resume(nav, at);
            }
            return;
        }
        CrowdControl.Decision d = crowd.decideHeld(ports.world(), nav.mover.id(), at, tick);
        if (d.kind() == CrowdControl.Kind.GO) {
            if (d.overdue()) {
                ports.log().accept(nav.mover.name() + " waited " + CrowdControl.MAX_WAIT_TICKS / 20
                    + " s at a narrow place: goes");
            }
            resume(nav, at);
        } else {
            toSpot(nav, at, d.spot());
        }
    }

    /** A held NPC goes on the way it was going. */
    private void resume(Nav nav, NavPoint at) {
        Resume how = nav.crowdResume;
        nav.crowdHeld = false;
        nav.crowdSpot = null;
        nav.crowdRetryTick = Long.MAX_VALUE;
        nav.lastProgressTick = tick;
        nav.state = NpcNavState.MOVING;
        switch (how) {
            case TRAIL -> {
                if (nav.follower == null) {
                    enqueue(nav);
                    return;
                }
                nav.follower.restartAtNpc(at);
                sendStretch(nav, true);
            }
            case KNK_LEG -> {
                if (nav.knkLeg == null) {
                    enqueue(nav);
                    return;
                }
                List<NavPoint> rest = nav.knkLeg.subList(Math.min(nav.knkLeg.size() - 1, nearestOf(nav.knkLeg, at)),
                    nav.knkLeg.size());
                nav.knkLegRest = Double.NaN;
                nav.trail = null;
                nav.mover.follow(NavGeometry.onSurface(ports.world(), List.copyOf(rest)),
                    nav.settings.waypointMargin());
            }
            case ENGINE_LEG -> {
                if (nav.legTarget == null || nav.legPrepared == null) {
                    enqueue(nav);
                    return;
                }
                nav.trail = null;
                nav.mover.walkTo(HopPlanner.feet(nav.legTarget), nav.legPrepared);
            }
            case REPLAN -> enqueue(nav);
        }
    }

    /** Ends crowd awareness's hold and claims for an NPC (its route was dropped, its order ended). */
    private void clearCrowd(Nav nav) {
        crowd.forget(nav.mover.id());
        nav.crowdHeld = false;
        nav.crowdSpot = null;
        nav.crowdRetryTick = Long.MAX_VALUE;
        nav.behindSince = -1;
    }

    /**
     * The engine reported a local-leg NPC stuck in one block while another NPC stands just ahead of it (collision on):
     * it waits a moment and walks its leg again instead of opening a stuck spell (decision 3.4).
     */
    private boolean holdBehind(Nav nav) {
        if (!nav.settings.crowdAwareness() || nav.knkLeg != null || nav.onTrail) {
            return false;
        }
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null || !behind(nav, at, wayAhead(nav, at))) {
            return false;
        }
        nav.crowdHeld = true;
        nav.crowdResume = nav.legTarget != null && nav.legPrepared != null ? Resume.ENGINE_LEG : Resume.REPLAN;
        nav.crowdRetryTick = tick + BEHIND_RETRY_TICKS;
        nav.state = NpcNavState.WAITING;
        nav.waitUntil = Long.MAX_VALUE;
        return true;
    }

    /**
     * Hands the mover the next stretch of the line. {@code required}: the NPC needs it now; otherwise it is the early
     * hand-over, which is skipped when the stretch is not loaded yet.
     */
    // ==================== push resistance (plan 4c.4 item 7, link M7) ====================

    /**
     * A pushed NPC within this many blocks of where it returns to is back: under {@link PushWatch#STANDING_PUSH}, so
     * a push it noticed always walks it back, and the walk back's own margin (0.3) ends within it.
     */
    static final double RETURNED_BLOCKS = 0.35;
    /** An NPC with no order of its own, pushed this far from its post by another NPC, walks back (a guard). */
    static final double NPC_SHOVE_BLOCKS = 1.0;
    /** An unexplained push with another NPC this near is that NPC's body (two bodies 0.6 wide, and a step). */
    static final double NPC_NEAR_BLOCKS = 1.5;
    /** A teleport home only when the NPC is further than this from home. */
    static final double TELEPORT_MIN_BLOCKS = 1.5;
    /** Ticks between two looks at the per-player exemptions whose time is up. */
    static final int EXEMPT_EXPIRY_TICKS = 20;

    /**
     * The engine's side of bodies (link M7): who pushes whom, and whether a player sees a place (the teleport home).
     * Call before registering NPCs; without it bodies collide as the engine's settings say and nothing teleports.
     */
    public void setCollision(NpcCollisionPolicy policy, NpcSight sight) {
        this.collision = policy == null ? NpcCollisionPolicy.NONE : policy;
        this.sight = sight == null ? NpcSight.EVERYBODY : sight;
    }

    /** Registered NPCs that follow a group's leader: a push leaves their return to the group's slot walks. */
    public void setGroupFollowers(java.util.function.IntPredicate followers) {
        this.groupFollower = followers == null ? id -> false : followers;
    }

    /** Pushes seen (all, by players, by attacks), returns, exemptions given and in force, teleports home. */
    public long[] pushCounts() {
        return new long[] {pushes, pushesByPlayers, pushesByAttacks, pushReturns, pushLedger.given(),
            pushLedger.active(), pushTeleports};
    }

    /** Main-thread time push resistance's looks took so far, in nanoseconds. */
    public long pushNanos() {
        return pushNanos;
    }

    /** Whether a mover is walking back after a push now (diagnostics, scenarios). */
    public boolean returning(int moverId) {
        Nav nav = npcs.get(moverId);
        return nav != null && nav.returnTo != null;
    }

    /** Every {@link PushWatch#LOOK_TICKS}: each spawned NPC with collision on is looked at; exemptions expire. */
    private void pushLooks(List<Nav> all) {
        if (tick % EXEMPT_EXPIRY_TICKS == 0) {
            for (PushLedger.Exemption e : pushLedger.expire(tick)) {
                collision.exempt(e.moverId(), e.playerId(), false);
                Nav nav = npcs.get(e.moverId());
                ports.log().accept((nav == null ? "NPC " + e.moverId() : nav.mover.name()) + " collides with player "
                    + e.playerId() + " again");
            }
        }
        long start = -1;
        for (Nav nav : all) {
            if (!nav.settings.collidable() || (tick + nav.mover.id()) % PushWatch.LOOK_TICKS != 0
                || !nav.mover.isSpawned()) {
                continue;
            }
            if (start < 0) {
                start = ports.nanoTime().getAsLong();
            }
            pushLook(nav);
        }
        if (start >= 0) {
            pushNanos += ports.nanoTime().getAsLong() - start;
        }
    }

    private void pushLook(Nav nav) {
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null) {
            return;
        }
        boolean walking = nav.mover.isMoving();
        // the walking direction is known only on the road line; a back-off, a walk to a queue spot, a return or a
        // winding local leg may go any way, so only its speed is judged then
        boolean lineWalk = walking && nav.state == NpcNavState.MOVING && nav.onTrail && nav.follower != null
            && nav.returnTo == null && !nav.crowdHeld && !nav.moveOnly && !nav.ladder.isOpen();
        double[] dir = lineWalk ? walkingDirection(nav, at) : new double[] {0, 0};
        PushWatch.Push p = nav.push.look(at, tick, walking, dir[0], dir[1]);
        if (p != null) {
            onPushed(nav, p, at);
        }
        if (nav.returnTo != null && (p == null || p.continued())) {
            watchReturn(nav, at);
        }
    }

    /** A push: counted, logged with who did it, the pusher maybe exempted, and the walk back started. */
    private void onPushed(Nav nav, PushWatch.Push p, NavPoint at) {
        if (p.kind() == null && npcNear(nav, at)) {
            // a body's push without a contact report (the engine reports only the pushed side of some pushes): another
            // NPC within reach did it
            p = new PushWatch.Push(p.from(), p.to(), NpcMover.ContactKind.NPC, null, p.walking(), false, p.continued());
        }
        if (p.continued()) {
            onStillPushed(nav, p, at);
            return;
        }
        pushes++;
        if (p.byPlayer()) {
            pushesByPlayers++;
        }
        if (p.kind() == NpcMover.ContactKind.ATTACK) {
            pushesByAttacks++;
        }
        ports.metrics().count(Event.PUSHED);
        boolean nearDrop = EdgeGuard.nextToDrop(ports.world(), at);
        boolean towardsDrop = nearDrop && EdgeGuard.towardsDrop(ports.world(), p.from(), at);
        String who = p.kind() == null ? "something unknown"
            : p.kind().name().toLowerCase(Locale.ROOT) + (p.by() == null ? "" : " " + p.by());
        ports.log().accept(String.format(Locale.ROOT, "%s pushed %.1f blocks from %s to %s by %s%s", nav.mover.name(),
            p.blocks(), NavGeometry.block(p.from()), NavGeometry.block(at), who,
            towardsDrop ? " (towards a drop)" : nearDrop ? " (next to a drop)" : ""));
        int id = nav.mover.id();
        if (nav.settings.playersPushNpcs() && p.byPlayer() && p.kind() == NpcMover.ContactKind.PLAYER
            && !pushLedger.isExempt(id, p.by())) {
            boolean exempt = pushLedger.record(id, p.by(), tick, nav.settings.pushExemptAfter(),
                nav.settings.pushExemptWindowTicks()) || towardsDrop;
            if (exempt) {
                pushLedger.exempted(id, p.by(), tick + nav.settings.pushExemptTicks());
                collision.exempt(id, p.by(), true);
                ports.metrics().count(Event.PUSH_EXEMPT);
                ports.log().accept(String.format(Locale.ROOT, "%s: player %s %s; collision between them off for %d s",
                    nav.mover.name(), p.by(), towardsDrop ? "pushed it towards a drop"
                        : "pushed it " + nav.settings.pushExemptAfter() + " times", nav.settings.pushExemptTicks() / 20));
            }
        }
        if (!nav.settings.pushReturn()) {
            return;
        }
        if (wantsReturn(nav, p, at, towardsDrop)) {
            startReturn(nav, nav.returnTo != null ? nav.returnTo : p.from(), at);
        }
    }

    /**
     * Whether a push makes the NPC walk back: always after a player, an attack or something unknown; after another
     * NPC's body only towards a drop, or for an NPC standing at its post (no order) moved {@link #NPC_SHOVE_BLOCKS} or
     * more (crowds jostle; on its way, in a queue or at its goal it is the crowd's business). Never for a group
     * follower (its group's slot walks bring it back).
     */
    private boolean wantsReturn(Nav nav, PushWatch.Push p, NavPoint at, boolean towardsDrop) {
        if (!nav.settings.pushReturn() || (nav.goal == null && groupFollower.test(nav.mover.id()))) {
            return false;
        }
        if (p.kind() != NpcMover.ContactKind.NPC || towardsDrop) {
            return true;
        }
        return nav.goal == null && !p.walking() && at.horizontalDistance(p.from()) >= NPC_SHOVE_BLOCKS;
    }

    /**
     * A look in an open push (the body is still being moved): an NPC found back too soon walks back again; when the
     * push now goes towards a drop, the edge guard acts at once: a player pushing is exempted (with
     * {@code players-push-npcs} on), and an NPC that was let walk on (jostled by another NPC) walks back after all.
     */
    private void onStillPushed(Nav nav, PushWatch.Push p, NavPoint at) {
        boolean towardsDrop = EdgeGuard.towardsDrop(ports.world(), p.from(), at);
        if (nav.returnTo == null && at.horizontalDistance(p.from()) > RETURNED_BLOCKS
            && wantsReturn(nav, p, at, towardsDrop)) {
            startReturn(nav, p.from(), at); // it was back too soon: the push carried it on
        }
        if (!towardsDrop) {
            return;
        }
        int id = nav.mover.id();
        if (nav.settings.playersPushNpcs() && p.byPlayer() && p.kind() == NpcMover.ContactKind.PLAYER
            && !pushLedger.isExempt(id, p.by())) {
            pushLedger.exempted(id, p.by(), tick + nav.settings.pushExemptTicks());
            collision.exempt(id, p.by(), true);
            ports.metrics().count(Event.PUSH_EXEMPT);
            ports.log().accept(String.format(Locale.ROOT, "%s: player %s pushed it towards a drop at %s; collision "
                + "between them off for %d s", nav.mover.name(), p.by(), NavGeometry.block(at),
                nav.settings.pushExemptTicks() / 20));
        }
        if (nav.returnTo == null && wantsReturn(nav, p, at, true)) {
            startReturn(nav, p.from(), at);
        }
    }

    /** Another spawned NPC here stands within {@link #NPC_NEAR_BLOCKS} of {@code at} (looked at only on a push). */
    private boolean npcNear(Nav nav, NavPoint at) {
        for (Nav other : npcs.values()) {
            if (other == nav || !other.mover.isSpawned()) {
                continue;
            }
            NavPoint o = other.mover.position().orElse(null);
            if (o != null && o.world().equals(at.world()) && Math.abs(o.y() - at.y()) < 2
                && o.horizontalDistance(at) <= NPC_NEAR_BLOCKS) {
                return true;
            }
        }
        return false;
    }

    /** Starts the walk back to {@code home}: a step of the order for a walking NPC, else a walk of the body only. */
    private void startReturn(Nav nav, NavPoint home, NavPoint at) {
        if (nav.returnTo == null) {
            nav.returnSince = tick;
        }
        nav.returnTo = home;
        if (at.horizontalDistance(home) <= RETURNED_BLOCKS) {
            returned(nav, at, "walked");
            return;
        }
        if (nav.goal != null && nav.state == NpcNavState.MOVING && !nav.crowdHeld) {
            nav.bodyReturn = false;
            returnOnOrder(nav, home, at);
        } else if (NavGeometry.straightWalkable(ports.world(), at, home)) {
            nav.bodyReturn = true;
            nav.trail = null;
            nav.mover.follow(NavGeometry.onSurface(ports.world(), List.of(home)), 0.3);
        } else if (nav.goal == null) {
            // no straight way back to its post: an order home, under K&K's rules (NAV-02), whose result nobody needs
            long since = nav.returnSince;
            navigate(nav.mover.id(), home, result -> { });
            nav.returnTo = home;
            nav.returnSince = since;
            nav.bodyReturn = false;
        } else {
            // waiting or held, no straight way back: it plans on from where it stands when its wait ends
            nav.bodyReturn = true;
        }
    }

    /**
     * The return order of an NPC with an order (research report § 6.5 item 2): straight back where it was pushed from
     * when that is a straight walk, else back along its own footsteps, else a local leg (K&K's search with
     * {@code local-legs-clearance} on, else the engine's); then on along its route from there.
     */
    private void returnOnOrder(Nav nav, NavPoint home, NavPoint at) {
        if (nav.ladder.isOpen()) {
            spellNotFreed(nav, "pushed");
        }
        List<NavPoint> back = null;
        String how;
        if (NavGeometry.straightWalkable(ports.world(), at, home)) {
            back = new java.util.ArrayList<>(List.of(home));
            how = "straight back";
        } else {
            List<NavPoint> steps = nav.steps.wayBack(at, at.horizontalDistance(home) + 0.5);
            if (!steps.isEmpty() && NavGeometry.straightWalkable(ports.world(), at, steps.get(0))
                && steps.get(steps.size() - 1).horizontalDistance(home) <= 1.0) {
                back = new java.util.ArrayList<>(steps);
                how = "back along its footsteps";
            } else {
                how = "a local leg back";
            }
        }
        ports.log().accept(String.format(Locale.ROOT, "%s returns %s to %s", nav.mover.name(), how,
            NavGeometry.block(home)));
        boolean onLine = nav.follower != null && (nav.onTrail || nav.recovering || nav.hops.isEmpty());
        nav.lastProgressTick = tick;
        nav.lastStepTick = tick;
        if (back != null) {
            if (onLine) {
                nav.follower.restartAtNpc(home);
                RouteFollower.Stretch rest = nav.follower.next(ports.world(), nav.settings.stretchBlocks(),
                    home.blockY() - 1, nav.settings.cornerClearance(), steering(nav));
                if (rest.kind() == RouteFollower.Kind.WAYPOINTS) {
                    back.addAll(rest.waypoints());
                }
                nav.hops.clear();
                nav.recovering = false;
                nav.onTrail = true;
                nav.moveOnly = false;
            } else {
                nav.onTrail = false;
                nav.moveOnly = true; // then its hop again from there
            }
            nav.knkLeg = null;
            nav.state = NpcNavState.MOVING;
            nav.mover.follow(NavGeometry.onSurface(ports.world(), back), nav.settings.waypointMargin());
            nav.trail = nav.onTrail && !back.isEmpty() ? new Trail(back) : null;
            return;
        }
        NavPoint floor = HopPlanner.floor(home);
        if (onLine) {
            nav.follower.restartAtNpc(home);
            nav.hops.clear();
            nav.hops.add(floor);
            nav.onTrail = false;
            nav.recovering = true;
        } else {
            nav.hops.addFirst(floor);
        }
        nav.moveOnly = false;
        nav.knkLeg = null;
        nav.state = NpcNavState.WAITING; // before the stop, so its cancel is not taken for a stuck NPC
        nav.trail = null;
        nav.mover.stop();
        enqueue(nav);
    }

    /** A returning NPC: back when near home; the teleport home, out of sight, when it took too long. */
    private void watchReturn(Nav nav, NavPoint at) {
        NavPoint home = nav.returnTo;
        double off = at.horizontalDistance(home);
        if (off <= RETURNED_BLOCKS && Math.abs(at.y() - home.y()) < 1.5) {
            returned(nav, at, "walked");
            return;
        }
        int limit = nav.settings.pushTeleportTicks();
        if (limit <= 0 || tick - nav.returnSince < limit || off < TELEPORT_MIN_BLOCKS
            || (tick + nav.mover.id()) % EXEMPT_EXPIRY_TICKS != 0) {
            return;
        }
        if (sight.seen(at) || sight.seen(home)) {
            return; // seen: it keeps walking (or waiting); looked at again in a second
        }
        nav.trail = null;
        if (!nav.mover.teleport(home)) {
            return;
        }
        pushTeleports++;
        ports.metrics().count(Event.PUSH_TELEPORT);
        ports.log().accept(String.format(Locale.ROOT, "%s could not walk back in %d s: teleported home to %s, out of "
            + "every player's sight", nav.mover.name(), (tick - nav.returnSince) / 20, NavGeometry.block(home)));
        nav.steps.clear();
        nav.push.reset(home, false);
        boolean body = nav.bodyReturn;
        returned(nav, home, "teleported");
        if (!body && nav.goal != null) {
            replan(nav); // its order goes on from home
        }
    }

    private void returned(Nav nav, NavPoint at, String how) {
        long ticks = tick - nav.returnSince;
        nav.push.settle(nav.returnTo); // a post stays where it was, it does not creep with each return
        nav.returnTo = null;
        nav.bodyReturn = false;
        pushReturns++;
        ports.metrics().count(Event.PUSH_RETURNED);
        ports.log().accept(String.format(Locale.ROOT, "%s is back at %s (%s, %.1f s)", nav.mover.name(),
            NavGeometry.block(at), how, ticks / 20.0));
    }

    private void sendStretch(Nav nav, boolean required) {
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null) {
            nav.state = NpcNavState.PAUSED;
            return;
        }
        RouteFollower.Stretch stretch = nav.follower.next(ports.world(), nav.settings.stretchBlocks(),
            at.blockY() - 1, nav.settings.cornerClearance(), steering(nav));
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
                nav.knkLeg = null;
                if (!nav.onTrail || nav.follower.bestIndex() < 0) {
                    nav.lastProgressTick = tick;
                }
                // appended to the waypoints not yet walked, not replacing them (link B2, decision 34.1)
                boolean append = !required && nav.onTrail && nav.trail != null;
                List<NavPoint> way = append ? nav.trail.appended(stretch.waypoints()) : stretch.waypoints();
                if (append && nav.mover.onGround()) {
                    way = Trail.fromFeet(ports.world(), at, way); // not back to a waypoint it is already past
                }
                // bridged from the feet, not from the last waypoint; a step the bridge cannot make is a local leg
                WaypointChain.Split split = WaypointChain.split(ports.world(), nav.mover.onGround() ? at : null, way);
                nav.onTrail = true;
                nav.state = NpcNavState.MOVING;
                ports.metrics().count(Event.STRETCH);
                nav.gapLeg = split.gap();
                if (split.gap() != null) {
                    ports.log().accept(String.format(Locale.ROOT, "%s: no walk from %s to %s on the line, a local leg "
                        + "takes that step", nav.mover.name(), NavGeometry.block(split.gapIndex() == 0 ? at
                        : way.get(split.gapIndex() - 1)), NavGeometry.block(split.gap())));
                    if (split.walk().isEmpty()) {
                        startGapLeg(nav);
                        return;
                    }
                }
                nav.trail = new Trail(split.walk());
                // the engine gets the waypoints at the real walking surface (link B4, PandiO/knk-workspace#673): a
                // half-block stair step is walked up, not jumped to a whole-block height; the trail keeps floor + 1
                nav.mover.follow(NavGeometry.onSurface(ports.world(), nav.mover.onGround() ? at : null, split.walk()),
                    nav.settings.marginFor(stretch));
            }
            default -> { }
        }
    }

    /**
     * The step of the line the bridge could not make (link B2, decision 34.1, item 4) as a local leg: the engine's
     * search under K&K's rules to the waypoint after the step; the line goes on from there ({@link #legDone}).
     */
    private void startGapLeg(Nav nav) {
        NavPoint target = nav.gapLeg;
        nav.gapLeg = null;
        nav.trail = null;
        nav.onTrail = false;
        nav.afterGap = true;
        nav.hops.clear();
        nav.hops.add(HopPlanner.floor(target));
        gapLegs++;
        enqueue(nav);
    }

    /** A stretch or a local leg was walked: the next hop, the next stretch, the tail, or the end of the order. */
    private void legDone(Nav nav) {
        if (nav.onTrail && nav.gapLeg != null) {
            startGapLeg(nav); // the waypoints up to the step the bridge could not make are walked
            return;
        }
        nav.retries = 0;
        nav.knkLeg = null;
        if (!nav.onTrail && !nav.hops.isEmpty()) {
            nav.hops.pollFirst();
            if (nav.ladder.isOpen()) {
                spellFreed(nav); // the local leg of the spell, or the hop it was stuck on, was walked
            }
        }
        nav.recovering = false;
        if (nav.hops.isEmpty() && nav.afterGap && nav.follower != null) {
            // the step the bridge could not make was walked: the line goes on from where the NPC stands
            nav.afterGap = false;
            nav.mover.position().ifPresent(nav.follower::restartAtNpc);
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
            crowd.forgetWidths(); // a door changed: narrow places may have opened or closed
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
                    // PandiO/knk-workspace#593: the trespass guard takes a fresh look now, so an entry made under the
                    // old rules (before this event told of the change) is not judged by the new ones
                    if (nav.regions != null) {
                        nav.mover.position().ifPresent(feet -> nav.regions = regionsAtFeet(feet));
                    }
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
     * entry is judged by the current rules from the regions it came from. An availability event takes a fresh look
     * first ({@link #onAvailabilityChanged}), so entries made before the plugin knew of a change are not counted.
     * Independent of the planner and of the engine's block rules: it watches where NPCs really are. Must stay 0.
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
            // a K&K local leg's own waypoints (link M6), else the engine's path
            for (NavPoint feet : nav.knkLeg != null ? nav.knkLeg : nav.mover.pathAhead()) {
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
        nav.ladderPending = false;
        nav.moveOnly = false;
        dropRoute(nav);
        nav.retries = 0;
        queue.remove(nav);
        nav.state = NpcNavState.WAITING;
        enqueue(nav); // QUEUED before the stop below, so the engine's cancel is not taken for a stuck NPC
        if (nav.mover.isSpawned()) {
            nav.trail = null;
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

    // ==================== stuck: spells and the recovery ladder (4b.2 items 6 and 7) ====================

    /** One footstep and the fall check, every few ticks while the NPC moves. */
    private void footstep(Nav nav) {
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null) {
            return;
        }
        double fell = nav.steps.record(at, nav.mover.onGround(), nav.mover.fallDistance(),
            nav.mover.verticalSpeed());
        if (fell > 0) {
            ports.metrics().count(Event.FELL);
            ports.log().accept(String.format(Locale.ROOT, "%s fell %.1f blocks to %s (%s)", nav.mover.name(), fell,
                NavGeometry.block(at), nav.onTrail ? "on the road line" : "on a local leg"));
        }
    }

    /** Where the NPC walks to from here: the next waypoint ahead on the line, or the hop it walks to. */
    private double[] walkingDirection(Nav nav, NavPoint at) {
        NavPoint aim = null;
        if (nav.onTrail && nav.follower != null) {
            List<NavPoint> line = nav.follower.line();
            int i = Math.min(line.size() - 1, nav.follower.nearestIndex(at) + 1);
            aim = line.get(i);
        } else if (!nav.hops.isEmpty()) {
            aim = nav.hops.peekFirst();
        }
        return aim == null ? new double[] {0, 0} : new double[] {aim.x() - at.x(), aim.z() - at.z()};
    }

    /** Whether the current rules let the NPC stand at a feet position: every region there it is not in may be entered. */
    private boolean mayStand(Nav nav, NavPoint here, NavPoint feet) {
        Set<String> now = regionsAtFeet(here);
        Set<String> there = regionsAtFeet(feet);
        if (now.containsAll(there)) {
            return true;
        }
        Predicate<String> mayEnter = ports.rules().mayEnter(subject(nav, here.world(), now));
        for (String region : there) {
            if (!now.contains(region) && !mayEnter.test(region)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A stuck report while the NPC moves (4b.2 item 6): the first one opens a spell and takes the first step at once;
     * one during a running try ends that try (the next step after {@code recovery-wait-ticks}); a failed engine path
     * search ends a try without a new spell ({@link Event#RECOVERY_NO_PATH}).
     */
    private void onStuck(Nav nav, RecoveryLadder.Reporter by) {
        NavPoint at = nav.mover.position().orElse(null);
        nav.gapLeg = null; // a recovery step hands the line out again from where it leaves the NPC
        if (by == RecoveryLadder.Reporter.NO_PATH) {
            ports.metrics().count(Event.RECOVERY_NO_PATH);
        }
        double[] dir = at == null ? new double[] {0, 0} : walkingDirection(nav, at);
        boolean wall = at != null && RecoveryMoves.nearWall(ports.world(), at, dir[0], dir[1]);
        switch (nav.ladder.report(tick, at, by, wall, nav.settings.recoveryWaitTicks())) {
            case STARTED -> {
                ports.metrics().spellStarted(by != RecoveryLadder.Reporter.NO_PROGRESS, wall);
                nav.trail = null;
                nav.mover.stop();
                nextTry(nav);
            }
            case TRY_FAILED -> {
                if (by == RecoveryLadder.Reporter.NO_PATH) {
                    ports.log().accept(nav.mover.name() + " " + (nav.ladder.current() == null ? "recovery"
                        : nav.ladder.current().label()) + " found no path at " + NavGeometry.block(at));
                }
                waitForNextTry(nav);
            }
            case JOINED -> { }
        }
    }

    /** The NPC waits {@code recovery-wait-ticks} before the next try (state WAITING). */
    private void waitForNextTry(Nav nav) {
        nav.moveOnly = false;
        nav.recovering = false;
        if (nav.mover.isSpawned()) {
            nav.state = NpcNavState.WAITING; // before the stop, so its cancel is not taken for a stuck NPC
            nav.trail = null;
            nav.mover.stop();
        }
        nav.ladderPending = true;
        waitTicks(nav, Math.max(0, nav.ladder.nextTryTick() - tick));
        if (nav.waitUntil <= tick) {
            nav.ladderPending = false;
            nextTry(nav);
        }
    }

    /** Takes the next step of the open spell's ladder: back-off, side-step, local leg ahead, or the replan. */
    private void nextTry(Nav nav) {
        nav.knkLeg = null;
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null) {
            nav.state = NpcNavState.PAUSED;
            return;
        }
        double[] dir = walkingDirection(nav, at);
        RecoveryMoves.Wall wall = RecoveryMoves.blockingFace(ports.world(), at, dir[0], dir[1]);
        java.util.concurrent.atomic.AtomicReference<List<NavPoint>> move = new java.util.concurrent.atomic.AtomicReference<>();
        NavPoint[] legTarget = new NavPoint[1];
        boolean onLine = nav.follower != null && (nav.onTrail || nav.recovering || nav.hops.isEmpty());
        double markBefore = onLine ? nav.follower.alongAt(at) : Double.NaN;
        RecoveryLadder.Step step = nav.ladder.nextStep(tick, nav.settings.maxRetries(), candidate -> switch (candidate) {
            case BACK_OFF -> {
                List<NavPoint> back = nav.steps.wayBack(at, 2);
                move.set(back);
                // only a way back it can walk straight (a pushed body's footsteps may lie behind a wall)
                yield !back.isEmpty() && NavGeometry.straightWalkable(ports.world(), at, back.get(0))
                    && !crowdOccupied(nav, back.get(back.size() - 1));
            }
            case SIDE_STEP -> {
                NavPoint side = RecoveryMoves.sideStep(ports.world(), at, wall, null,
                    feet -> mayStand(nav, at, feet) && !crowdOccupied(nav, feet));
                move.set(side == null ? List.of() : List.of(side));
                yield side != null;
            }
            case LOCAL_LEG -> {
                if (!onLine) {
                    yield !nav.hops.isEmpty(); // a local leg's own hop is the point ahead
                }
                legTarget[0] = nav.follower.recoveryTarget(ports.world(), at, nav.settings.hopBlocks(),
                    feet -> mayStand(nav, at, feet) && !crowdOccupied(nav, feet));
                yield legTarget[0] != null;
            }
            case REPLAN -> true;
        });
        String detail = switch (step) {
            case BACK_OFF, SIDE_STEP -> "to " + NavGeometry.block(move.get().get(move.get().size() - 1));
            case LOCAL_LEG -> "to " + NavGeometry.block(legTarget[0] != null ? HopPlanner.feet(legTarget[0])
                : HopPlanner.feet(nav.hops.peekFirst()));
            case REPLAN -> null;
        };
        ports.log().accept(nav.ladder.stepLine(nav.mover.name(), RecoveryLadder.what(nav.ladder.reporter(),
            nav.settings.noProgressTicks()), at, wall, nav.settings.maxRetries(), detail));
        nav.tryMark = Double.NaN;
        nav.tryMarkRef = null;
        nav.lastProgressTick = tick;
        nav.lastStepTick = tick;
        switch (step) {
            case BACK_OFF, SIDE_STEP -> {
                List<NavPoint> waypoints = new java.util.ArrayList<>(move.get());
                if (onLine && nav.follower != null) {
                    // then the rest of the stretch again from there, corner clearance applied
                    NavPoint end = waypoints.get(waypoints.size() - 1);
                    nav.follower.restartAtNpc(end);
                    RouteFollower.Stretch rest = nav.follower.next(ports.world(), nav.settings.stretchBlocks(),
                        end.blockY() - 1, nav.settings.cornerClearance(), steering(nav));
                    if (rest.kind() == RouteFollower.Kind.WAYPOINTS) {
                        waypoints.addAll(rest.waypoints());
                    }
                    nav.hops.clear();
                    nav.recovering = false;
                    nav.onTrail = true;
                    nav.moveOnly = false;
                    nav.tryMarkRef = nav.follower;
                    nav.tryMark = markBefore; // measured where it got stuck, before it walks back
                } else {
                    nav.onTrail = false;
                    nav.moveOnly = true;
                }
                nav.state = NpcNavState.MOVING;
                nav.mover.follow(NavGeometry.onSurface(ports.world(), waypoints), nav.settings.waypointMargin());
                nav.trail = nav.onTrail && !waypoints.isEmpty() ? new Trail(waypoints) : null;
            }
            case LOCAL_LEG -> {
                if (legTarget[0] != null) {
                    nav.hops.clear();
                    nav.hops.add(legTarget[0]);
                    nav.onTrail = false;
                    nav.recovering = true;
                }
                nav.moveOnly = false;
                nav.state = NpcNavState.WAITING;
                enqueue(nav); // through the planning queue: capture and access first
            }
            case REPLAN -> {
                ports.metrics().count(Event.RETRIES_EXHAUSTED);
                nav.retries = 0;
                dropRoute(nav);
                if (++nav.replans <= MAX_REPLANS) {
                    nav.state = NpcNavState.WAITING;
                    enqueue(nav);
                } else {
                    spellNotFreed(nav, "retries and replans used up");
                    end(nav, failed("stuck at " + NavGeometry.block(at) + ": retries and replans used up"));
                }
            }
        }
    }

    /**
     * Watches the running try of an open spell (every {@link #HANDOVER_TICKS}): freed when the NPC came at least
     * {@link RecoveryLadder#FREED_BLOCKS} further along its path, failed when {@code no-progress-ticks} passed without.
     */
    private void supervise(Nav nav) {
        if (!nav.ladder.isOpen() || !nav.ladder.tryRunning() || (tick + nav.mover.id()) % HANDOVER_TICKS != 0) {
            return;
        }
        NavPoint at = nav.mover.position().orElse(null);
        if (at == null) {
            return;
        }
        Object ref = nav.onTrail && nav.follower != null ? nav.follower
            : nav.moveOnly || nav.hops.isEmpty() ? null : nav.hops.peekFirst();
        if (ref == null) {
            nav.ladder.holdTryClock(tick); // a back-off of a local-leg NPC: measured on its hop after it
            return;
        }
        double mark = ref instanceof RouteFollower follower ? follower.alongAt(at)
            : -Math.sqrt(sq(((NavPoint) ref).x() - at.x()) + sq(((NavPoint) ref).y() + 1 - at.y())
                + sq(((NavPoint) ref).z() - at.z()));
        if (ref != nav.tryMarkRef || Double.isNaN(nav.tryMark)) {
            nav.tryMarkRef = ref;
            nav.tryMark = mark;
            nav.ladder.holdTryClock(tick);
            return;
        }
        if (mark >= nav.tryMark + RecoveryLadder.FREED_BLOCKS) {
            spellFreed(nav);
        } else if (tick - nav.ladder.tryStartTick() >= nav.settings.noProgressTicks()) {
            nav.ladder.tryFailed(tick, nav.settings.recoveryWaitTicks());
            waitForNextTry(nav);
        }
    }

    private void spellFreed(Nav nav) {
        if (!nav.ladder.isOpen()) {
            return;
        }
        NavPoint at = nav.mover.position().orElse(nav.ladder.spot());
        RecoveryLadder.Step by = nav.ladder.current();
        int steps = nav.ladder.taken();
        long ticks = tick - nav.ladder.startTick();
        boolean wall = nav.ladder.nearWall();
        Event event = nav.ladder.freed(tick, at);
        ports.metrics().spellEnded(wall, event, steps, ticks, at);
        ports.log().accept(RecoveryLadder.freedLine(nav.mover.name(), at, by, steps, ticks));
    }

    private void spellNotFreed(Nav nav, String why) {
        if (!nav.ladder.isOpen()) {
            return;
        }
        NavPoint at = nav.mover.position().orElse(nav.ladder.spot());
        int steps = nav.ladder.taken();
        long ticks = tick - nav.ladder.startTick();
        boolean wall = nav.ladder.nearWall();
        nav.ladder.notFreed(tick, at);
        ports.metrics().spellEnded(wall, Event.NOT_FREED, steps, ticks, at);
        ports.log().accept(RecoveryLadder.notFreedLine(nav.mover.name(), at, why));
    }

    private static double sq(double v) {
        return v * v;
    }

    private void dropRoute(Nav nav) {
        settleEngineLeg(nav, false);
        clearCrowd(nav);
        nav.legTarget = null;
        nav.legPrepared = null;
        nav.knkLeg = null;
        nav.hops.clear();
        nav.tail.clear();
        nav.follower = null;
        nav.onTrail = false;
        nav.recovering = false;
        nav.gates = List.of();
    }

    // ==================== results ====================

    private static String describe(NavigationResult result) {
        return switch (result) {
            case NavigationResult.Failed f -> "failed: " + f.reason();
            case NavigationResult.Cancelled c -> "cancelled: " + c.reason();
            default -> result.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        };
    }

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
        if (result instanceof NavigationResult.Arrived) {
            spellFreed(nav);
        } else {
            spellNotFreed(nav, "the order ended (" + describe(result) + ")");
        }
        nav.ladderPending = false;
        nav.moveOnly = false;
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
                nav.trail = null;
                nav.mover.stop();
            }
        }
        if (onResult != null) {
            onResult.accept(result);
        }
    }
}
