package net.knightsandkings.knk.core.npc.nav.group;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import net.knightsandkings.knk.core.gates.target.GateBox;
import net.knightsandkings.knk.core.npc.nav.BlockingElement;
import net.knightsandkings.knk.core.npc.nav.HopPlanner;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcAccessSubject;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavState;
import net.knightsandkings.knk.core.npc.nav.NavGeometry;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.PathTargets;
import net.knightsandkings.knk.core.npc.nav.RecentSteps;
import net.knightsandkings.knk.core.npc.nav.RecoveryLadder;
import net.knightsandkings.knk.core.npc.nav.RecoveryMoves;
import net.knightsandkings.knk.core.npc.nav.RouteFollower;
import net.knightsandkings.knk.core.npc.nav.WaypointChain;

/**
 * A group of NPCs that plans once (implementation plan Phase 4; BEHAVIOR_MODEL NAV-05). Only the leader has an order
 * in {@link net.knightsandkings.knk.core.npc.nav.NpcNavigationService}: it plans the road route, is rechecked, is
 * blocked and is woken like any single NPC, so a rule change costs the group one plan, and the group has one blocked
 * state and one wake-up - the leader's. The followers get no plan of their own: they walk the group's path (the
 * leader's route line and, off the road, its footsteps) as waypoint lists, each in its {@link Formation} slot a fixed
 * distance behind the leader and to the side of the path.
 *
 * <p>Where the path is too narrow for the formation (a gate, stairs, a wall walk) the group folds into a column and
 * takes up its formation again after. A follower behind its slot walks faster ({@code group-catch-up-speed}); one
 * that left its lane or made no progress plans its own leg back to the path through the navigation service - one
 * local leg, never the whole route - and after {@link #MAX_REJOINS} failed legs the group goes on without it while it
 * keeps trying slowly. When a follower falls further behind than {@code group-regroup-blocks} the leader waits
 * until the group has closed up.
 *
 * <p>Rules stay binding for followers: a lane point never lies in a WorldGuard region the path's own point is not
 * in, and before a follower is sent on, every region ahead that it does not stand in yet is asked about with the
 * current rules; it stops before the first one it may not enter. The same goes for the gate doors on the leader's
 * routes (PandiO/knk-workspace#507): a follower stops {@link #DOOR_MARGIN} short of the box of a door that blocks it
 * now (closed, moving, jammed), so a door that starts closing between the leader and its followers does not push
 * them aside; a follower already in that box when the door starts moving walks on out of it. A gate or availability
 * event hands every follower its waypoints again under the new rules (PandiO/knk-workspace#506), and a trespass
 * guard of their own counts a follower that entered a region the current rules deny it ({@link Event#TRESPASS}).
 *
 * <p>Main thread only. Created and ticked by {@link NavGroupService}.
 */
public final class NavGroup {

    /** The group looks at its members this often. */
    static final int GROUP_TICKS = 5;
    /** While the leader walks its line, followers are sent this many blocks past their slots, so they do not halt. */
    static final double LOOKAHEAD = 12;
    /** A follower gets its next waypoints within this many blocks of the end of the ones it has. */
    static final double HANDOVER = 4;
    /** The leader is on its route line within this many blocks of it. */
    static final double ON_LINE = 2.5;
    /** Off the route line, a footstep of the leader joins the path every this many blocks. */
    static final double FOOTSTEP = 1.0;
    /** A follower's place on the path is looked for this many points ahead of its last one. */
    static final int WINDOW = 16;
    /** A follower joining the path may start this far from it without a leg of its own. */
    static final double JOIN_DISTANCE = 8;
    /** A follower this far behind its slot walks faster, until it is within {@link #CAUGHT_UP_LAG}. */
    static final double CATCH_UP_LAG = 1.0;
    static final double CAUGHT_UP_LAG = 0.25;
    /** A follower this far ahead of its slot waits for it. */
    static final double AHEAD_TOLERANCE = 0.75;
    /** Failed own legs in a row before the group goes on without a follower. */
    public static final int MAX_REJOINS = 3;
    /** Ticks before a failed own leg is tried again. */
    static final int REJOIN_RETRY_TICKS = 40;
    /** After the leader arrived, the followers get this long to reach their slots. */
    static final int SETTLE_TICKS = 400;
    /** The leader waits for followers at most this long; who is still behind then is left to catch up alone. */
    static final int MAX_HOLD_TICKS = 600;
    /** Waypoints handed to a follower at once at most. */
    static final int MAX_WAYPOINTS = 32;
    /**
     * A follower keeps this many blocks (horizontally, and one block up and down) out of the box of a gate door that
     * blocks it; inside that margin when the door starts moving, it walks on out (PandiO/knk-workspace#507).
     */
    static final double DOOR_MARGIN = 1.0;
    /** With the recheck off ({@code recheck-ticks: 0}) the followers' trespass guard still looks this often. */
    static final int GUARD_TICKS = 40;
    /** A left-behind follower's tries are spaced out up to this (60 s; developer decision 2026-11-15, 4b.4 question 11). */
    static final int MAX_LOST_RETRY_TICKS = 1200;
    /** A lane is back on the path's centre this many blocks before a place where it has no floor. */
    static final double LANE_RETURN = 2.0;
    /** A left-behind follower waits on a free cell at most this far beside its lane. */
    static final int ASIDE_BLOCKS = 2;

    public enum FollowerState {
        /** Walks the group's path in its slot. */
        FOLLOWING,
        /** Walks its own leg back to the path (an order of its own in the navigation service). */
        REJOINING,
        /** Its own leg failed: waits before the next try. */
        RETRY_WAIT,
        /**
         * Finishes its trip of the group's last order to its slot at the goal before it follows the group's current
         * order ({@code group-followers-finish-trip}; implementation plan 4b.3 item 10).
         */
        FINISHING
    }

    /** Hears about single members of the group (implementation plan 4b.3 item 10: a courier hands over its goods). */
    @FunctionalInterface
    public interface MemberListener {
        /** A follower that finished its trip reached its slot at the goal of the order it was on. */
        void onMemberArrived(NpcMover member, NavPoint goal);
    }

    /** A point of a finishing follower's own trip: where its feet go and the regions of the group's path there. */
    private record TripPoint(NavPoint feet, Set<String> regions) {
    }

    private final class Follower {
        final NpcMover mover;
        FollowerState state = FollowerState.FOLLOWING;
        int pathIdx = -1;
        /** Just found its place on the path: it may still be {@link #JOIN_DISTANCE} away. */
        boolean joining;
        double sentEndArc = Double.NaN;
        double progressArc;
        long progressTick;
        boolean boosted;
        int failures;
        boolean lost;
        long retryAt;
        int rejoinSeq;
        int rejoinNode = -1;
        /** Stopped before a region the current rules deny it. */
        boolean heldByRule;
        /** Its waypoints were cut short by a rule (a door, a district edge) at its last hand-out. */
        boolean cut;
        /** Followers ahead of it cut short by a rule: it stops this many spacings further back (a queue, not a pile). */
        int queue;
        /** The waypoints it has lead into a region it did not stand in when it got them. */
        boolean sentIntoRegion;
        /** The rules may have changed since it got its waypoints: they are handed out again. */
        boolean recheck;
        /** The regions it stood in at the trespass guard's last look; null before the first (PandiO/knk-workspace#506). */
        Set<String> regions;
        long guardTick;
        /** Walks out of the way of a gate door that started moving with it in its box; not stopped on the way. */
        boolean leavingDoor;
        /** How far behind its slot (negative: ahead), from the last look. */
        double lag;
        boolean settled = true;
        // ---- stuck spells and the recovery ladder (implementation plan 4b.2 items 9 to 11) ----
        final RecoveryLadder ladder = new RecoveryLadder();
        final RecentSteps steps = new RecentSteps();
        /** A back-off or side-step of the ladder is being walked: its lane is not handed out meanwhile. */
        boolean recoveryMove;
        /** Where along the path it was when the running try started. */
        double tryMark;
        /** The engine reported it stuck since the last look (null: not). */
        NpcMover.StuckKind engineStuck;
        /** The wait before a left-behind follower's next try: doubles up to {@link #MAX_LOST_RETRY_TICKS}. */
        int lostRetryTicks;
        /** The path index an own leg back failed to reach, and from where (never aimed at again from there). */
        int failedTarget = -1;
        NavPoint failedFrom;
        // ---- its own trip to the goal while FINISHING (implementation plan 4b.3 item 10) ----
        List<TripPoint> trip = List.of();
        int tripIdx;
        /** The trip index of the last waypoint handed to it; -1: none. */
        int tripSent = -1;
        /** The gate doors of the order it finishes (the group's current order has its own). */
        Set<Integer> tripDoors = Set.of();
        NavPoint tripGoal;
        int tripTries;

        Follower(NpcMover mover) {
            this.mover = mover;
        }
    }

    private record Verdict(Set<String> from, String region) {
    }

    private final int id;
    private final NavGroupService.Ports ports;
    private final NpcAccessProfile profile;
    private final GroupSettings settings;
    private NpcMover leader;
    private final List<Follower> followers = new ArrayList<>();
    /** The formation's widths, widest first, the column last, and per width its slots and extent (4b.3 item 3). */
    private List<Formation.Width> widths = List.of();
    private List<List<Formation.Slot>> levelSlots = List.of();
    private double[] levelHalf = {0};
    private double[] levelDepth = {0};
    private double[] levelFront = {0};
    private double maxDepth;
    private double maxFront;
    /** The width the group walks in now (an index into {@link #widths}). */
    private int level;
    /** A new path: the next look takes the width that fits at once, without the hysteresis. */
    private boolean widthFresh = true;
    private MemberListener memberListener;

    private final GroupPath path = new GroupPath();
    private int leaderIdx = -1;
    private RouteFollower line;
    private int lineStart;
    private int lineBase;
    private int lineAdded;
    private boolean onLine;

    private NavPoint goal;
    private Consumer<NavigationResult> onResult;
    private int seq;
    private long leaderDoneTick = -1;
    private boolean holding;
    private long holdingSince;
    private boolean blockedReported;
    private BlockingElement reportedElement;
    private final Map<Verdict, Boolean> verdicts = new HashMap<>();
    /** The gate doors on the leader's routes in this order: a door the leader has passed stays one (#507). */
    private final Set<Integer> routeDoors = new LinkedHashSet<>();
    private final Map<Integer, Optional<GateBox>> doorBoxes = new HashMap<>();
    /** Whether a door blocks the group's NPCs now; asked again with the region verdicts. */
    private final Map<Integer, Boolean> doorVerdicts = new HashMap<>();
    private long verdictTick;
    private long now;
    private boolean disbanded;
    /** A rule changed since the last look: the next tick looks at the group whatever its turn. */
    private boolean rulesPending;

    NavGroup(int id, NavGroupService.Ports ports, NpcMover leader, List<NpcMover> followers, NpcAccessProfile profile,
             GroupSettings settings) {
        this.id = id;
        this.ports = ports;
        this.leader = Objects.requireNonNull(leader, "leader");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.settings = Objects.requireNonNull(settings, "settings");
        for (NpcMover follower : followers) {
            this.followers.add(new Follower(follower));
        }
        slotsChanged();
    }

    private void slotsChanged() {
        Formation formation = settings.formation();
        int n = followers.size();
        widths = formation.widths();
        int count = widths.size();
        List<List<Formation.Slot>> slots = new ArrayList<>(count);
        levelHalf = new double[count];
        levelDepth = new double[count];
        levelFront = new double[count];
        maxDepth = 0;
        maxFront = 0;
        for (int l = 0; l < count; l++) {
            slots.add(formation.slots(n, l));
            levelHalf[l] = formation.halfWidth(n, l);
            levelDepth[l] = formation.depth(n, l);
            levelFront[l] = formation.front(n, l);
            maxDepth = Math.max(maxDepth, levelDepth[l]);
            maxFront = Math.max(maxFront, levelFront[l]);
        }
        if (n > 0 && levelHalf[count - 1] <= 0.01) {
            levelHalf[count - 1] = 0;
        }
        levelSlots = slots;
        level = Math.min(level, count - 1);
        path.levels(levelHalf);
    }

    // ==================== callers ====================

    public int id() {
        return id;
    }

    public NpcMover leader() {
        return leader;
    }

    /** Leader included. */
    public int size() {
        return followers.size() + 1;
    }

    public GroupSettings settings() {
        return settings;
    }

    /** Whether the group has an order. */
    public boolean hasOrder() {
        return goal != null;
    }

    /** Whether the group walks as a column now because its path is too narrow for 2 abreast. */
    public boolean folded() {
        return widths.size() > 1 && level == widths.size() - 1;
    }

    /** The width the group walks in now (implementation plan 4b.3 item 3). */
    public Formation.Width width() {
        return widths.get(level);
    }

    /** Listens for followers that finished their trip (implementation plan 4b.3 item 10); null: nobody. */
    public void onMemberArrived(MemberListener listener) {
        this.memberListener = listener;
    }

    /** Whether the leader waits for followers that fell behind. */
    public boolean holding() {
        return holding;
    }

    /** What blocks the group now (the leader's blocking element), or null. */
    public BlockingElement blockedBy() {
        return ports.navigation().blockingOf(leader.id());
    }

    /** How many followers are in a state. */
    public int followers(FollowerState state) {
        int n = 0;
        for (Follower f : followers) {
            n += f.state == state ? 1 : 0;
        }
        return n;
    }

    /** Followers the group goes on without (their own legs back keep failing). */
    public int lost() {
        int n = 0;
        for (Follower f : followers) {
            n += f.lost ? 1 : 0;
        }
        return n;
    }

    /** The largest distance a follower is behind its slot, from the group's last look (0 with none behind). */
    public double maxLag() {
        double max = 0;
        for (Follower f : followers) {
            max = Math.max(max, f.lag);
        }
        return max;
    }

    /** The state of a follower, or null when {@code moverId} is not one. */
    public FollowerState followerState(int moverId) {
        for (Follower f : followers) {
            if (f.mover.id() == moverId) {
                return f.state;
            }
        }
        return null;
    }

    /**
     * Sends the group to {@code goalFeet}: the leader walks there, the followers to their slots behind it.
     * {@code onResult} hears {@link NavigationResult.Blocked} once per blocking element (the order goes on by itself)
     * and then exactly one final result: Arrived (the leader arrived and the followers reached their slots or were
     * given up waiting for), Failed or Cancelled. A running order is cancelled first ("replaced"). The callback may
     * give the group its next order.
     *
     * @return false when the group was disbanded
     */
    public boolean navigate(NavPoint goalFeet, Consumer<NavigationResult> onResult) {
        Objects.requireNonNull(goalFeet, "goalFeet");
        if (disbanded) {
            return false;
        }
        if (goal != null) {
            finish(new NavigationResult.Cancelled("replaced"));
        }
        goal = goalFeet;
        this.onResult = onResult;
        leaderDoneTick = -1;
        holding = false;
        blockedReported = false;
        reportedElement = null;
        resetPath();
        for (Follower f : followers) {
            if (f.state == FollowerState.FINISHING) {
                continue; // still on the trip of an order that arrived: it follows this one when it is done
            }
            f.failures = 0;
            f.lost = false;
            f.lag = 0;
            f.settled = true;
            f.progressTick = now;
            f.regions = null;
            f.lostRetryTicks = 0;
            f.failedTarget = -1;
        }
        orderLeader();
        return true;
    }

    /** Ends the group's order with {@link NavigationResult.Cancelled}; false when it has none. */
    public boolean cancel(String reason) {
        if (goal == null) {
            return false;
        }
        finish(new NavigationResult.Cancelled(reason));
        return true;
    }

    /**
     * Takes a member out of the group (it stays under navigation). Without its leader the next follower leads and a
     * running order is given again; the last member's leaving ends the order and the group.
     */
    public void remove(int moverId) {
        if (leader.id() == moverId) {
            if (followers.isEmpty()) {
                disband();
                return;
            }
            boolean hadOrder = goal != null;
            seq++;
            ports.navigation().cancel(leader.id(), "left its group");
            Follower next = followers.remove(0);
            releaseFollower(next);
            leader = next.mover;
            slotsChanged();
            if (hadOrder) {
                holding = false;
                resetPath();
                orderLeader();
            }
            return;
        }
        for (int i = 0; i < followers.size(); i++) {
            if (followers.get(i).mover.id() == moverId) {
                releaseFollower(followers.remove(i));
                slotsChanged();
                return;
            }
        }
    }

    void disband() {
        if (goal != null) {
            finish(new NavigationResult.Cancelled("the group was disbanded"));
        }
        disbanded = true;
    }

    /**
     * A gate or an access rule changed: every follower gets its waypoints again under the new rules (one that may no
     * longer walk on stops at the edge, PandiO/knk-workspace#506), and followers waiting on a failed leg try again now.
     */
    void rulesChanged() {
        verdicts.clear();
        doorVerdicts.clear();
        doorBoxes.clear();
        rulesPending = goal != null || finishing() > 0;
        for (Follower f : followers) {
            // the trespass guard judges entries from here on by the new rules, not the ones made under the old rules
            if (goal != null && f.state != FollowerState.REJOINING) {
                f.mover.position().ifPresent(feet -> {
                    f.regions = regionsAt(feet);
                    f.guardTick = now;
                });
            }
            f.recheck = true;
            f.lostRetryTicks = 0; // a gate or access event: a left-behind follower's tries start again from the shortest
            f.failedTarget = -1;
            if (f.state == FollowerState.RETRY_WAIT) {
                f.retryAt = now;
            }
        }
    }

    // ==================== the leader's order ====================

    private void orderLeader() {
        int order = ++seq;
        if (!ports.navigation().navigate(leader.id(), goal, result -> onLeader(order, result))) {
            finish(new NavigationResult.Failed("the group's leader is not under navigation"));
        }
    }

    private void onLeader(int order, NavigationResult result) {
        if (order != seq || goal == null) {
            return; // an order this group replaced or held back itself
        }
        switch (result) {
            case NavigationResult.Arrived arrived -> leaderDoneTick = now;
            case NavigationResult.Blocked blocked -> {
                if (!blockedReported || !Objects.equals(blocked.blockingElement(), reportedElement)) {
                    blockedReported = true;
                    reportedElement = blocked.blockingElement();
                    if (onResult != null) {
                        onResult.accept(blocked);
                    }
                }
            }
            default -> finish(result);
        }
    }

    private void finish(NavigationResult result) {
        Consumer<NavigationResult> callback = onResult;
        NavPoint ended = goal;
        boolean arrived = result instanceof NavigationResult.Arrived;
        if (arrived && settings.followersFinishTrip()) {
            beginTrips(ended); // before the path is gone: followers still on their way keep their part of it
        }
        goal = null;
        onResult = null;
        seq++;
        holding = false;
        leaderDoneTick = -1;
        if (!(result instanceof NavigationResult.Arrived)) {
            ports.navigation().cancel(leader.id(), "the group's order ended");
        }
        for (Follower f : followers) {
            if (!(arrived && f.state == FollowerState.FINISHING)) {
                releaseFollower(f); // a replaced, cancelled or failed order releases finishing followers too (item 11)
            }
        }
        if (callback != null) {
            callback.accept(result);
        }
    }

    /** Ends whatever a follower does for the group: its own leg, its waypoints, its catching up. */
    private void releaseFollower(Follower f) {
        if (f.state == FollowerState.REJOINING) {
            f.rejoinSeq++;
            ports.navigation().cancel(f.mover.id(), "the group's order ended");
        } else if (f.mover.isSpawned() && f.mover.isMoving()) {
            f.mover.stop();
        }
        if (f.ladder.isOpen()) {
            spellNotFreed(f, "the group's order ended");
        }
        f.recoveryMove = false;
        f.engineStuck = null;
        f.trip = List.of();
        f.tripSent = -1;
        f.tripDoors = Set.of();
        f.tripGoal = null;
        f.state = FollowerState.FOLLOWING;
        f.pathIdx = -1;
        f.sentEndArc = Double.NaN;
        f.heldByRule = false;
        f.sentIntoRegion = false;
        f.recheck = false;
        f.leavingDoor = false;
        f.cut = false;
        normalSpeed(f);
    }

    private void resetPath() {
        path.clear();
        routeDoors.clear();
        leaderIdx = -1;
        line = null;
        onLine = false;
        widthFresh = true;
        for (Follower f : followers) {
            if (f.state != FollowerState.FINISHING) {
                releaseFollower(f);
            }
        }
    }

    // ==================== tick ====================

    void tick(long tick) {
        now = tick;
        if ((tick + id) % GROUP_TICKS != 0 && !rulesPending) {
            return;
        }
        if (goal == null) {
            if (rulesPending || finishing() > 0) {
                rulesPending = false;
                int queued = 0;
                for (Follower f : new ArrayList<>(followers)) {
                    if (f.state == FollowerState.FINISHING) {
                        f.queue = queued;
                        step(f, null, 0, 0); // finishing followers walk on while the group has no order
                        queued += f.cut && f.state == FollowerState.FINISHING ? 1 : 0;
                    }
                }
            }
            return;
        }
        rulesPending = false;
        NavPoint at = leader.position().orElse(null);
        if (at == null) {
            return; // the leader's chunk is unloaded: navigation resumes it; the followers finish what they have
        }
        if (tick - verdictTick >= Math.max(20, settings.recheckTicks())) {
            // a rule change that came without an event: ask again, and look again at waypoints that cross into
            // a region (the navigation service's own recheck does the same for the leader)
            verdicts.clear();
            doorVerdicts.clear();
            verdictTick = tick;
            for (Follower f : followers) {
                f.recheck |= f.sentIntoRegion;
            }
        }
        if (blockedReported && ports.navigation().blockingOf(leader.id()) == null
            && ports.navigation().state(leader.id()) == NpcNavState.MOVING) {
            blockedReported = false; // the way is free again: the next block is news
            reportedElement = null;
        }
        trackLeader(at);
        routeDoors.addAll(ports.navigation().gateDoorsOf(leader.id()));
        decideWidth();
        NpcNavState leaderState = ports.navigation().state(leader.id());
        boolean walking = leaderState == NpcNavState.MOVING && onLine && leaderDoneTick < 0 && !holding;
        double leaderArc = path.arcOf(at, leaderIdx);
        double lookahead = walking ? Math.max(0, Math.min(LOOKAHEAD, path.endArc() - leaderArc - levelFront[level])) : 0;
        List<Formation.Slot> slots = levelSlots.get(level);
        double maxLag = 0;
        boolean settled = true;
        int queued = 0;
        for (int i = 0; i < followers.size(); i++) {
            Follower f = followers.get(i);
            f.queue = queued;
            step(f, slots.get(i), leaderArc, lookahead);
            queued += f.cut && (f.state == FollowerState.FOLLOWING || f.state == FollowerState.FINISHING) ? 1 : 0;
            if (f.mover.isSpawned() && !f.lost && !f.heldByRule && f.state != FollowerState.FINISHING) {
                maxLag = Math.max(maxLag, f.lag);
                settled &= f.settled;
            }
        }
        if (leaderDoneTick >= 0) {
            if (settled || tick - leaderDoneTick >= SETTLE_TICKS) {
                finish(new NavigationResult.Arrived());
            }
            return;
        }
        closeUp(leaderState, maxLag);
    }

    /** Keeps the path up to date with where the leader is: its route line while it is on it, else its footsteps. */
    private void trackLeader(NavPoint at) {
        RouteFollower follower = ports.navigation().followerOf(leader.id());
        int leaderFloorY = at.blockY() - 1;
        boolean on = false;
        if (follower != null) {
            List<NavPoint> points = follower.line();
            int nearest = follower.nearestIndex(at);
            NavPoint p = points.get(nearest);
            if (at.horizontalDistance(p) <= ON_LINE && Math.abs(p.y() + 1 - at.y()) <= 2.5) {
                on = true;
                if (follower != line || !onLine || nearest < lineStart) {
                    // a new line, or back on it: what lay ahead of the leader on the path is stale
                    path.truncate(leaderIdx + 1);
                    line = follower;
                    lineStart = nearest;
                    lineBase = path.size();
                    lineAdded = 0;
                }
                int index = lineBase + (nearest - lineStart);
                double wanted = Math.max(LOOKAHEAD, settings.widthHysteresisBlocks() + 1) + maxFront
                    + 2 * RouteFollower.STEP;
                while (lineStart + lineAdded < points.size() && (lineBase + lineAdded <= index
                    || path.endArc() - path.arc(index) < wanted)) {
                    path.addSnapped(ports.world(), points.get(lineStart + lineAdded), leaderFloorY);
                    lineAdded++;
                }
                leaderIdx = Math.max(Math.min(index, path.size() - 1), Math.min(leaderIdx, path.size() - 1));
            }
        }
        if (!on) {
            if (onLine || leaderIdx < 0) {
                path.truncate(leaderIdx + 1);
            }
            NavPoint floor = HopPlanner.floor(at);
            if (path.isEmpty() || path.get(path.size() - 1).floor.horizontalDistance(floor) >= FOOTSTEP
                || Math.abs(path.get(path.size() - 1).floor.y() - floor.y()) >= 1) {
                path.add(floor);
            }
            leaderIdx = path.size() - 1;
            line = null;
        }
        onLine = on;
    }

    /**
     * The width the group walks in (implementation plan 4b.3 items 3 to 7): the widest of its formation's widths that
     * fits over the stretch of path under it (from the rearmost slot to the foremost, plus a block). A column is
     * taken at once (a gate, a door, a stair); a narrower width that is still 2 abreast or more only when the narrow
     * part lasts longer than {@code group-width-hysteresis-blocks} along the path (a shorter bump is passed by the
     * lanes that do not fit stepping in for those points); a wider width only when it fits the stretch plus the
     * hysteresis ahead of the foremost slot. One pass over the stretch, a cached look per point.
     */
    private void decideWidth() {
        int count = widths.size();
        if (count <= 1 || leaderIdx < 0) {
            return;
        }
        double leaderArc = path.arc(leaderIdx);
        double hysteresis = settings.widthHysteresisBlocks();
        int from = path.indexAtArc(Math.max(0, leaderArc - maxDepth - 1));
        double toArc = leaderArc + maxFront + 1 + hysteresis;
        int to = from;
        while (to + 1 < path.size() && path.arc(to + 1) <= toArc) {
            to++;
        }
        int[] widest = new int[to - from + 1];
        for (int i = from; i <= to; i++) {
            widest[i - from] = path.widest(ports.world(), i);
        }
        int target = level;
        int narrowAt = -1;
        if (widthFresh) {
            widthFresh = false;
            target = count - 1;
            for (int l = 0; l < count; l++) {
                if (firstMisfit(widest, from, leaderArc, l, 0) < 0) {
                    target = l;
                    break;
                }
            }
            narrowAt = target > level ? firstMisfit(widest, from, leaderArc, level, 0) : -1;
        } else {
            int misfit = firstMisfit(widest, from, leaderArc, level, 0);
            if (misfit >= 0) {
                int next = count - 1;
                for (int l = level + 1; l < count - 1; l++) {
                    if (firstMisfit(widest, from, leaderArc, l, 0) < 0) {
                        next = l;
                        break;
                    }
                }
                // to the column at once; to a narrower width that still is 2 abreast or more when it lasts
                if (next == count - 1 || narrowLasts(misfit, level, hysteresis)) {
                    target = next;
                    narrowAt = misfit;
                }
            } else {
                boolean more = onLine && line != null && lineStart + lineAdded < line.line().size();
                for (int l = 0; l < level; l++) {
                    boolean seen = !more || path.endArc() >= leaderArc + levelFront[l] + 1 + hysteresis;
                    if (seen && firstMisfit(widest, from, leaderArc, l, hysteresis) < 0) {
                        target = l;
                        break;
                    }
                }
            }
        }
        if (target != level) {
            changeWidth(target, narrowAt >= 0 ? narrowAt : leaderIdx);
        }
    }

    /**
     * The first path point of the stretch under width {@code l} (plus {@code ahead} blocks before its foremost slot)
     * where it does not fit, or -1 when it fits all of it.
     */
    private int firstMisfit(int[] widest, int from, double leaderArc, int l, double ahead) {
        double rear = leaderArc - levelDepth[l] - 1;
        double fore = leaderArc + levelFront[l] + 1 + ahead;
        for (int k = 0; k < widest.length; k++) {
            double arc = path.arc(from + k);
            if (arc > fore) {
                break;
            }
            if (arc >= rear && widest[k] > l) {
                return from + k;
            }
        }
        return -1;
    }

    /** Whether the stretch where width {@code l} does not fit, from {@code misfit} on, is longer than the hysteresis. */
    private boolean narrowLasts(int misfit, int l, double hysteresis) {
        double start = path.arc(misfit);
        for (int i = misfit + 1; i < path.size(); i++) {
            if (path.arc(i) - start > hysteresis) {
                return true;
            }
            if (path.widest(ports.world(), i) <= l) {
                return false; // it fits again within the hysteresis: a bump
            }
        }
        // narrow up to the end of the path known so far: lasting if no more of the leader's line is to come
        return !(onLine && line != null && lineStart + lineAdded < line.line().size()) && path.endArc() - start > hysteresis;
    }

    private void changeWidth(int target, int atIndex) {
        int before = level;
        Formation.Width was = widths.get(before);
        Formation.Width now = widths.get(target);
        int count = widths.size();
        level = target;
        NavPoint at = path.get(Math.max(0, Math.min(atIndex, path.size() - 1))).floor;
        String what;
        Event event;
        if (now.column()) {
            event = Event.GROUP_FOLD;
            what = "folds into a column: the path is too narrow for " + widths.get(count - 2).describe();
        } else if (was.column()) {
            event = Event.GROUP_REFORM;
            what = "takes up " + now.describe() + " again";
        } else if (target > before) {
            event = Event.GROUP_NARROW;
            what = "narrows to " + now.describe() + ": the path is too narrow for " + was.describe();
        } else {
            event = Event.GROUP_WIDEN;
            what = "widens to " + now.describe();
        }
        ports.metrics().count(event);
        ports.log().accept(String.format(Locale.ROOT, "group %d (%s, %d NPCs) %s at %d %d %d", id, leader.name(), size(),
            what, at.blockX(), at.blockY() + 1, at.blockZ()));
    }

    private void step(Follower f, Formation.Slot slot, double leaderArc, double lookahead) {
        if (!f.mover.isSpawned()) {
            f.pathIdx = -1;
            f.sentEndArc = Double.NaN;
            f.progressTick = now;
            return;
        }
        f.mover.position().ifPresent(feet -> {
            double fell = f.steps.record(feet, f.mover.onGround(), f.mover.fallDistance());
            if (fell > 0) {
                ports.metrics().count(Event.FELL);
                ports.log().accept(String.format(Locale.ROOT, "%s (group %d) fell %.1f blocks to %s", f.mover.name(), id,
                    fell, NavGeometry.block(feet)));
            }
        });
        if (f.state == FollowerState.REJOINING) {
            f.regions = null; // on a leg of its own the navigation service's trespass guard watches it
        } else if (now - f.guardTick >= (settings.recheckTicks() > 0 ? settings.recheckTicks() : GUARD_TICKS)) {
            f.mover.position().ifPresent(feet -> guard(f, feet, regionsAt(feet)));
        }
        if (f.state == FollowerState.FINISHING) {
            f.lag = 0; // not waited for: it counts like a held follower (4b.3 item 10)
            finishingStep(f);
            return;
        }
        double slotArc = leaderArc - slot.back();
        if (f.state != FollowerState.FOLLOWING) {
            // on a leg of its own, or between two tries: how far behind it is, for the leader's waiting
            f.settled = false;
            f.mover.position().ifPresent(feet ->
                f.lag = slotArc - path.arcOf(feet, path.nearest(feet, 0, leaderIdx + 1)));
        }
        if (f.state == FollowerState.REJOINING) {
            return;
        }
        if (f.state == FollowerState.RETRY_WAIT) {
            if (now >= f.retryAt) {
                f.state = FollowerState.FOLLOWING; // look again: it may be back in its lane by now
                f.pathIdx = -1;
                f.progressTick = now;
            }
            return;
        }
        double targetArc = slotArc + lookahead;
        NavPoint at = f.mover.position().orElse(null);
        if (targetArc <= 0 || at == null) {
            f.lag = 0; // the path has not reached its slot yet
            f.settled = true;
            f.progressTick = now;
            return;
        }
        if (f.pathIdx < 0 || f.pathIdx >= path.size()) {
            f.pathIdx = path.nearest(at, 0, path.size());
            f.joining = true;
            f.progressArc = path.arcOf(at, f.pathIdx);
            f.progressTick = now;
        } else {
            f.pathIdx = path.nearest(at, f.pathIdx, f.pathIdx + WINDOW);
        }
        NavPoint node = path.get(f.pathIdx).floor;
        double allowed = Math.abs(slot.side()) + (f.joining ? Math.max(JOIN_DISTANCE, settings.rejoinBlocks())
            : settings.rejoinBlocks());
        if (at.horizontalDistance(node) > allowed || Math.abs(node.y() + 1 - at.y()) > 3) {
            if (aheadOnLeadersLine(at, allowed)) {
                // it stands on the leader's route further along than the group's path is known (a group that turned
                // back with followers held behind it, group-followers-finish-trip false: 4b.3 item 9): it waits for
                // the column to reach it and falls in, rather than walking an own leg to the leader
                if (f.mover.isMoving()) {
                    f.mover.stop();
                }
                f.pathIdx = -1;
                f.lag = 0;
                f.settled = true;
                f.progressTick = now;
                return;
            }
            rejoin(f, slotArc, "is off its lane");
            return;
        }
        double a = path.arcOf(at, f.pathIdx);
        f.lag = slotArc - a;
        speed(f);
        if (f.ladder.isOpen() && recoveryTick(f, slot, slotArc, at, a)) {
            return; // on a step of its ladder, or waiting before the next
        }
        if (f.engineStuck != null && f.state == FollowerState.FOLLOWING && targetArc - a > 1) {
            NpcMover.StuckKind kind = f.engineStuck;
            f.engineStuck = null;
            stuck(f, slot, slotArc, at, a, kind == NpcMover.StuckKind.NO_PATH ? RecoveryLadder.Reporter.NO_PATH
                : RecoveryLadder.Reporter.STATIONARY);
            return;
        }
        f.engineStuck = null;
        if (f.leavingDoor) {
            if (f.mover.isMoving()) {
                f.settled = false;
                f.progressTick = now;
                return; // out of a moving door's way first, wherever its slot is
            }
            f.leavingDoor = false;
        }
        if (f.recheck && !routeDoors.isEmpty() && inBlockingDoorWay(f, at)) {
            send(f, slot, at, a, targetArc); // a door started moving with it in the way, its slot or not: out
            f.settled = false;
            f.progressTick = now;
            return;
        }
        if (a >= targetArc - 0.3 || f.lag < -AHEAD_TOLERANCE) {
            // in its place, or ahead of its slot: wait for the slot
            if (f.mover.isMoving() && !(f.sentEndArc <= Math.max(targetArc, a) + 0.5)) {
                f.mover.stop();
                f.sentEndArc = a;
            }
            f.settled = !f.mover.isMoving();
            f.progressArc = a;
            f.progressTick = now;
            return;
        }
        if (!f.mover.isMoving() && targetArc - a <= 1 && Math.abs(f.sentEndArc - targetArc) < 0.1) {
            // as near its slot as its lane lets it stand: not sent there again
            f.settled = true;
            f.progressTick = now;
            return;
        }
        f.settled = false;
        boolean overshoot = f.sentEndArc > targetArc + 0.5;
        if (!f.mover.isMoving() || overshoot || f.recheck || Double.isNaN(f.sentEndArc)
            || (f.sentEndArc - a < HANDOVER && targetArc - f.sentEndArc > 1)) {
            send(f, slot, at, a, targetArc);
        }
        if (a > f.progressArc + 0.5) {
            f.progressArc = a;
            f.progressTick = now;
            f.joining = false;
            f.failures = 0;
            f.lost = false;
            f.lostRetryTicks = 0;
        } else if (f.heldByRule) {
            f.progressTick = now;
        } else if (targetArc - a > 1 && now - f.progressTick >= settings.noProgressTicks()) {
            stuck(f, slot, slotArc, at, a, RecoveryLadder.Reporter.NO_PROGRESS);
        }
    }

    /** Whether a feet position lies on the leader's route line beyond the part of it that is on the group's path. */
    private boolean aheadOnLeadersLine(NavPoint at, double allowed) {
        if (!onLine || line == null) {
            return false;
        }
        List<NavPoint> points = line.line();
        for (int i = lineStart + lineAdded; i < points.size(); i++) {
            NavPoint p = points.get(i);
            if (at.horizontalDistance(p) <= allowed && Math.abs(p.y() + 1 - at.y()) <= 3) {
                return true;
            }
        }
        return false;
    }

    // ==================== a stuck follower's ladder (implementation plan 4b.2 items 9 to 11) ====================

    /** The engine reported a follower of this group stuck on its waypoints; false when it is not one of them. */
    boolean engineStuck(int moverId, NpcMover.StuckKind kind) {
        for (Follower f : followers) {
            if (f.mover.id() == moverId) {
                if ((goal != null && f.state == FollowerState.FOLLOWING) || f.state == FollowerState.FINISHING) {
                    if (kind == NpcMover.StuckKind.NO_PATH) {
                        ports.metrics().count(Event.RECOVERY_NO_PATH);
                    }
                    f.engineStuck = kind;
                    rulesPending = true; // looked at in the next tick
                }
                return true;
            }
        }
        return false;
    }

    /** The direction along the path at a follower's place. */
    private double[] pathDirection(Follower f) {
        return path.heading(Math.max(0, Math.min(f.pathIdx, path.size() - 1)));
    }

    /** A stuck report for a follower: opens a spell (first step at once) or ends its running try. */
    private void stuck(Follower f, Formation.Slot slot, double slotArc, NavPoint at, double a, RecoveryLadder.Reporter by) {
        double[] dir = pathDirection(f);
        boolean wall = RecoveryMoves.nearWall(ports.world(), at, dir[0], dir[1]);
        f.progressTick = now;
        switch (f.ladder.report(now, at, by, wall, settings.recoveryWaitTicks())) {
            case STARTED -> {
                ports.metrics().spellStarted(by != RecoveryLadder.Reporter.NO_PROGRESS, wall);
                nextTry(f, slot, slotArc, at, a);
            }
            case TRY_FAILED -> {
                f.recoveryMove = false;
                if (f.mover.isMoving()) {
                    f.mover.stop();
                }
            }
            case JOINED -> { }
        }
    }

    /**
     * A follower with an open spell: watches its running try (freed when it came {@link RecoveryLadder#FREED_BLOCKS}
     * further along the path, failed after {@code no-progress-ticks}) and takes the next step when its wait is over.
     * Returns true while the ladder holds the follower (its lane is not handed out).
     */
    private boolean recoveryTick(Follower f, Formation.Slot slot, double slotArc, NavPoint at, double a) {
        if (f.ladder.tryRunning()) {
            if (a >= f.tryMark + RecoveryLadder.FREED_BLOCKS) {
                spellFreed(f, at);
                f.recoveryMove = false;
                return false;
            }
            if (now - f.ladder.tryStartTick() >= settings.noProgressTicks()) {
                f.ladder.tryFailed(now, settings.recoveryWaitTicks());
                f.recoveryMove = false;
                if (f.mover.isMoving()) {
                    f.mover.stop();
                }
                return true;
            }
            if (f.recoveryMove && f.mover.isMoving()) {
                return true; // walking its back-off or side-step
            }
            f.recoveryMove = false;
            return false; // the step is walked: its lane again, the try still watched
        }
        if (now >= f.ladder.nextTryTick()) {
            nextTry(f, slot, slotArc, at, a);
        }
        return true;
    }

    /** Takes a follower's next step: back-off, side-step (away from the path's centre), its own leg ahead, again. */
    private void nextTry(Follower f, Formation.Slot slot, double slotArc, NavPoint at, double a) {
        double[] dir = pathDirection(f);
        RecoveryMoves.Wall wall = RecoveryMoves.blockingFace(ports.world(), at, dir[0], dir[1]);
        // away from the path's centre: the side of its lane, or the side it stands on
        double side = slot.side() != 0 ? Math.signum(slot.side())
            : Math.signum((at.x() - path.get(f.pathIdx).floor.x()) * -dir[1] + (at.z() - path.get(f.pathIdx).floor.z()) * dir[0]);
        double[] away = side == 0 ? null : new double[] {-dir[1] * side, dir[0] * side};
        List<NavPoint> move = new ArrayList<>();
        Set<String> here = regionsAt(at);
        RecoveryLadder.Step step = f.ladder.nextStep(now, settings.maxRetries(), candidate -> switch (candidate) {
            case BACK_OFF -> {
                move.clear();
                move.addAll(f.steps.wayBack(at, 2));
                yield !move.isEmpty() && NavGeometry.straightWalkable(ports.world(), at, move.get(0));
            }
            case SIDE_STEP -> {
                move.clear();
                NavPoint p = RecoveryMoves.sideStep(ports.world(), at, wall, away, feet -> mayStand(f, here, feet));
                if (p != null) {
                    move.add(p);
                }
                yield p != null;
            }
            case LOCAL_LEG, REPLAN -> true;
        });
        String detail = move.isEmpty() || step == RecoveryLadder.Step.LOCAL_LEG || step == RecoveryLadder.Step.REPLAN
            ? null : "to " + NavGeometry.block(move.get(move.size() - 1));
        ports.log().accept(f.ladder.stepLine(f.mover.name() + " (group " + id + ")", RecoveryLadder.what(
            f.ladder.reporter(), settings.noProgressTicks()), at, wall, settings.maxRetries(), detail));
        f.tryMark = a;
        f.progressTick = now;
        switch (step) {
            case BACK_OFF, SIDE_STEP -> {
                f.recoveryMove = true;
                f.sentEndArc = Double.NaN;
                f.mover.follow(move, settings.waypointMargin());
            }
            case LOCAL_LEG, REPLAN -> rejoin(f, slotArc, step == RecoveryLadder.Step.LOCAL_LEG ? "is stuck"
                : "is still stuck (steps used up)");
        }
    }

    /** Whether the current rules let a follower standing in {@code here} stand at a feet position. */
    private boolean mayStand(Follower f, Set<String> here, NavPoint feet) {
        Set<String> there = regionsAt(feet);
        for (String region : there) {
            if (!here.contains(region) && !verdicts.computeIfAbsent(new Verdict(here, region), v -> ports.rules()
                .mayEnter(new NpcAccessSubject(f.mover.name(), profile, feet.world(), here)).test(region))) {
                return false;
            }
        }
        return true;
    }

    private void spellFreed(Follower f, NavPoint at) {
        if (!f.ladder.isOpen()) {
            return;
        }
        RecoveryLadder.Step by = f.ladder.current();
        int taken = f.ladder.taken();
        long ticks = now - f.ladder.startTick();
        boolean wall = f.ladder.nearWall();
        Event event = f.ladder.freed(now, at);
        ports.metrics().spellEnded(wall, event, taken, ticks, at);
        ports.log().accept(RecoveryLadder.freedLine(f.mover.name() + " (group " + id + ")", at, by, taken, ticks));
    }

    private void spellNotFreed(Follower f, String why) {
        if (!f.ladder.isOpen()) {
            return;
        }
        NavPoint at = f.mover.position().orElse(f.ladder.spot());
        int taken = f.ladder.taken();
        long ticks = now - f.ladder.startTick();
        boolean wall = f.ladder.nearWall();
        f.ladder.notFreed(now, at);
        ports.metrics().spellEnded(wall, Event.NOT_FREED, taken, ticks, at);
        ports.log().accept(RecoveryLadder.notFreedLine(f.mover.name() + " (group " + id + ")", at, why));
    }

    /**
     * A follower the group goes on without waits beside its lane, not on it (implementation plan 4b.2 item 10): on a
     * free standable cell at most {@link #ASIDE_BLOCKS} to the side of the path, away from its centre first.
     */
    private void stepAside(Follower f) {
        NavPoint at = f.mover.position().orElse(null);
        if (at == null || path.isEmpty()) {
            return;
        }
        int i = path.nearest(at, 0, path.size());
        NavPoint centre = path.get(i).floor;
        double[] h = path.heading(i);
        double side = Math.signum((at.x() - centre.x()) * -h[1] + (at.z() - centre.z()) * h[0]);
        Set<String> here = regionsAt(at);
        NavPoint floorHere = HopPlanner.floor(at);
        for (int d = ASIDE_BLOCKS; d >= 1; d--) {
            for (double s : side >= 0 ? new double[] {1, -1} : new double[] {-1, 1}) {
                NavPoint probe = new NavPoint(at.world(), Math.floor(centre.x() - h[1] * s * d) + 0.5, floorHere.blockY(),
                    Math.floor(centre.z() + h[0] * s * d) + 0.5);
                NavPoint floor = HopPlanner.floorNear(ports.world(), probe, floorHere.blockY());
                if (floor == null || probe.horizontalDistance(centre) < 1.5 || !NavGeometry.hasHeadroom(ports.world(), floor)) {
                    continue;
                }
                NavPoint feet = HopPlanner.feet(floor);
                if (NavGeometry.wallAt(ports.world(), at.world(), feet.blockX(), feet.blockY(), feet.blockZ())
                    || !mayStand(f, here, feet)) {
                    continue;
                }
                if (feet.blockX() != at.blockX() || feet.blockZ() != at.blockZ()) {
                    f.mover.follow(List.of(feet), settings.waypointMargin());
                }
                return;
            }
        }
    }

    /** Hands a follower its lane from where it is to {@code targetArc}, as far as the current rules let it walk. */
    private void send(Follower f, Formation.Slot slot, NavPoint at, double a, double targetArc) {
        Set<String> here = regionsAt(at);
        guard(f, at, here);
        List<GateBox> doors = blockingDoors(f, at.world(), here);
        GateBox inDoor = null;
        for (GateBox box : doors) {
            if (inDoorWay(box, at)) {
                if (!underDoor(box, at) && aheadUnder(f, box)) {
                    backOutOf(f, box); // in the margin before a moving door: a step back, not through it
                    return;
                }
                inDoor = box; // under the door when it starts moving: it walks on out, never stops in there
                break;
            }
        }
        int end = path.indexAtArc(targetArc);
        List<NavPoint> waypoints = new ArrayList<>();
        List<Double> arcs = new ArrayList<>();
        double endArc = a;
        boolean cut = false;
        f.recheck = false;
        f.sentIntoRegion = false;
        int k = path.arc(f.pathIdx) > a + 0.3 ? f.pathIdx : f.pathIdx + 1;
        for (; k <= end && waypoints.size() < MAX_WAYPOINTS; k++) {
            if (!mayWalk(f, here, k) || intoDoor(doors, inDoor, HopPlanner.feet(path.get(k).floor))) {
                cut = true;
                break;
            }
            if (add(waypoints, continuous(waypoints, at, lanePoint(k, 0, slot.side()), k))) {
                arcs.add(path.arc(k));
            }
            endArc = path.arc(k);
        }
        if (cut && f.queue > 0) {
            // followers ahead of it stand before the same door or edge: it stops a spacing behind each of them,
            // counted from the last point before the door or edge (where the first of them stands)
            double keep = f.queue * settings.formation().spacing();
            int stopIdx = Math.max(0, k - 1);
            double stopArc = path.arc(stopIdx);
            while (!waypoints.isEmpty() && stopArc - arcs.get(arcs.size() - 1) < keep) {
                waypoints.remove(waypoints.size() - 1);
                arcs.remove(arcs.size() - 1);
            }
            endArc = arcs.isEmpty() ? a : arcs.get(arcs.size() - 1);
        }
        f.cut = cut;
        if (!cut && k > end) {
            // the slot itself: between two path points, or past the path's end (a slot ahead of the leader)
            double along = targetArc - path.arc(end);
            if (along > 0.2 && mayWalk(f, here, end)) {
                NavPoint p = lanePoint(end, along, slot.side());
                if (!intoDoor(doors, inDoor, p)) {
                    add(waypoints, p);
                    endArc = targetArc;
                }
            }
        }
        if (inDoor != null && !cut) {
            // its slot lies in the door's way: on along the path until it is out of it
            for (; k < path.size() && waypoints.size() < MAX_WAYPOINTS
                && (waypoints.isEmpty() || inDoorWay(inDoor, waypoints.get(waypoints.size() - 1))); k++) {
                if (!mayWalk(f, here, k) || intoDoor(doors, inDoor, HopPlanner.feet(path.get(k).floor))) {
                    break;
                }
                add(waypoints, continuous(waypoints, at, lanePoint(k, 0, slot.side()), k));
                endArc = Math.max(endArc, path.arc(k));
            }
        }
        f.heldByRule = cut && waypoints.isEmpty();
        f.leavingDoor = inDoor != null && !waypoints.isEmpty();
        if (waypoints.isEmpty()) {
            if (f.mover.isMoving()) {
                f.mover.stop();
            }
            f.sentEndArc = a;
            return;
        }
        // every step a straight walk on floor: a lane point across a wall corner or a ledge from the one before it gets
        // the cells between (#592, lanes running into a landing's corner)
        f.mover.follow(WaypointChain.bridged(ports.world(), at, waypoints));
        f.sentEndArc = endArc;
    }

    /**
     * A lane waypoint more than a block above or below the waypoint before it (or the follower's feet) is the path's
     * centre instead: a lane never steps onto a wall top beside a stair and drops off it (implementation plan 4b.2
     * item 12, falls; found by the {@code npc-corners-walk} scenario).
     */
    private NavPoint continuous(List<NavPoint> waypoints, NavPoint at, NavPoint lane, int index) {
        NavPoint before = waypoints.isEmpty() ? at : waypoints.get(waypoints.size() - 1);
        if (Math.abs(lane.y() - before.y()) <= 1) {
            return lane;
        }
        return HopPlanner.feet(path.get(index).floor);
    }

    /** Adds a waypoint unless it is where the last one is; true when added. */
    private static boolean add(List<NavPoint> waypoints, NavPoint p) {
        if (waypoints.isEmpty() || waypoints.get(waypoints.size() - 1).horizontalDistance(p) > 0.3
            || waypoints.get(waypoints.size() - 1).y() != p.y()) {
            waypoints.add(p);
            return true;
        }
        return false;
    }

    /**
     * The lane's point at a path point; the path's centre where the lane has no floor of its own there or at the
     * path points next to it (so a lane leaves the centre a step after a narrow place and is back on it a step
     * before the next, not around its corner).
     */
    private NavPoint lanePoint(int index, double along, double side) {
        NavPoint p = null;
        double lane = laneSide(index, side);
        if (Math.abs(lane) > 1e-9 && laneFree(index - 1, side, lane) && laneAhead(index, along, side, lane)) {
            p = path.point(ports.world(), index, along, lane);
        }
        if (p == null && along != 0) {
            p = path.point(ports.world(), index, along, 0);
        }
        return p != null ? p : HopPlanner.feet(path.get(index).floor);
    }

    /**
     * Whether the lane exists from a path point on for {@link #LANE_RETURN} blocks along the path (and the next point
     * past it): a lane is back on the path's centre that far before a place where it has no floor, so a follower
     * steps in behind the one ahead before a 1-wide opening, never diagonally across its wall corner (found by
     * {@code npc-corners-walk} and {@code npc-group-narrows} once 2 abreast fit the scene's 2-wide passage).
     */
    private boolean laneAhead(int index, double along, double side, double lane) {
        if (!laneFree(index, side, lane) || !laneFree(index + 1, side, lane)
            || (along != 0 && !laneFree(index + 2, side, lane))) {
            return false;
        }
        if (index < 0 || index >= path.size()) {
            return true;
        }
        double until = path.arc(index) + along + LANE_RETURN;
        for (int k = index + 2; k < path.size() && path.arc(k - 1) <= until; k++) {
            if (!laneFree(k, side, lane)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a lane exists at a path point both where the slot's lane lies there and {@code lane} blocks beside the
     * path (the lane of the point it is asked for): where the width's shift changes between two points the walk from
     * one lane point to the next stays on floor and in the path's regions (true before the path's start).
     */
    private boolean laneFree(int index, double side, double lane) {
        return laneFree(index, side) && (index < 0 || index >= path.size()
            || path.point(ports.world(), index, 0, lane) != null);
    }

    /** Whether the lane {@code side} blocks beside the path exists at a path point (true before the path's start). */
    private boolean laneFree(int index, double side) {
        if (index < 0) {
            return true;
        }
        if (index >= path.size()) {
            // past the path's last point: free, unless more of the leader's line is still to come (not known yet)
            return !(onLine && line != null && lineStart + lineAdded < line.line().size());
        }
        return path.point(ports.world(), index, 0, laneSide(index, side)) != null;
    }

    /**
     * A slot's lane at a path point: its side plus the shift that lets the group's current width fit there (4b.3
     * item 4: 2 abreast in a 2-wide passage whose line runs down one column); the plain side where the width does
     * not fit (a bump: the lane falls back to the path's centre).
     */
    private double laneSide(int index, double side) {
        if (index < 0 || index >= path.size()) {
            return side;
        }
        double shift = path.shift(ports.world(), index, level);
        return Double.isNaN(shift) ? side : side + shift;
    }

    private Set<String> regionsAt(NavPoint feet) {
        return ports.world().regionsAt(feet.world(), feet.blockX(), feet.blockY(), feet.blockZ());
    }

    /**
     * The boxes of the gate doors on the leader's routes that block the group's NPCs now (PandiO/knk-workspace#507);
     * doors whose box is not known are left out.
     */
    private List<GateBox> blockingDoors(Follower f, String world, Set<String> here) {
        return blockingDoors(f, world, here, routeDoors);
    }

    private List<GateBox> blockingDoors(Follower f, String world, Set<String> here, Set<Integer> doorIds) {
        if (doorIds.isEmpty()) {
            return List.of();
        }
        List<GateBox> out = new ArrayList<>(2);
        for (int doorId : doorIds) {
            Optional<GateBox> box = doorBoxes.computeIfAbsent(doorId, id -> ports.rules().doorBox(id));
            if (box.isPresent() && doorVerdicts.computeIfAbsent(doorId, id -> ports.rules()
                .gateBlocks(new NpcAccessSubject(f.mover.name(), profile, world, here), id))) {
                out.add(box.get());
            }
        }
        return out;
    }

    private boolean inBlockingDoorWay(Follower f, NavPoint at) {
        for (GateBox box : blockingDoors(f, at.world(), regionsAt(at))) {
            if (inDoorWay(box, at)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a point lies in a blocking door's box or within {@link #DOOR_MARGIN} of it, other than {@code inDoor}. */
    private static boolean intoDoor(List<GateBox> doors, GateBox inDoor, NavPoint feet) {
        for (GateBox box : doors) {
            if (box != inDoor && inDoorWay(box, feet)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an NPC with its feet here stands under a door: in its box itself, not only in the margin round it. */
    static boolean underDoor(GateBox box, NavPoint feet) {
        return feet.x() >= box.minX() && feet.x() <= box.maxX() && feet.z() >= box.minZ() && feet.z() <= box.maxZ()
            && feet.y() + 2 > box.minY() && feet.y() < box.maxY();
    }

    /** Whether the group's path passes under a door within a few points ahead of a follower: it has the door ahead. */
    private boolean aheadUnder(Follower f, GateBox box) {
        if (f.pathIdx < 0 || f.pathIdx >= path.size()) {
            return false;
        }
        double until = path.arc(f.pathIdx) + 2 * DOOR_MARGIN + 1;
        for (int k = f.pathIdx; k < path.size() && path.arc(k) <= until; k++) {
            if (underDoor(box, HopPlanner.feet(path.get(k).floor))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A follower in the margin before a door that started moving steps back along its lane out of the door's way and
     * waits there like a follower stopped by a rule (implementation plan 4b.3, found by
     * {@code npc-group-gate-finish-trip}: a closing portcullis blocks a body a third of the way into its close, so a
     * follower a block short of it never got through and was pressed against it).
     */
    private void backOutOf(Follower f, GateBox box) {
        NavPoint back = null;
        int k = Math.min(f.pathIdx, path.size() - 1);
        double edge = Double.NaN;
        for (; k >= 0; k--) {
            NavPoint p = HopPlanner.feet(path.get(k).floor);
            if (!inDoorWay(box, p)) {
                if (Double.isNaN(edge)) {
                    edge = path.arc(k);
                }
                // behind the followers that stand before the same door already: a queue, not a pile
                if (edge - path.arc(k) >= f.queue * settings.formation().spacing() || k == 0) {
                    back = p;
                    break;
                }
            }
        }
        f.recheck = false;
        f.cut = true;
        f.heldByRule = true;
        f.progressTick = now;
        if (back == null) {
            if (f.mover.isMoving()) {
                f.mover.stop();
            }
            f.leavingDoor = false;
            return;
        }
        f.mover.follow(List.of(back), settings.waypointMargin());
        f.sentEndArc = path.arc(k);
        f.leavingDoor = true; // not interrupted on its way back
    }

    /** Whether an NPC with its feet here is in a door's way: in its box or within {@link #DOOR_MARGIN} of it. */
    static boolean inDoorWay(GateBox box, NavPoint feet) {
        return feet.x() >= box.minX() - DOOR_MARGIN && feet.x() <= box.maxX() + DOOR_MARGIN
            && feet.z() >= box.minZ() - DOOR_MARGIN && feet.z() <= box.maxZ() + DOOR_MARGIN
            && feet.y() + 2 > box.minY() - DOOR_MARGIN && feet.y() < box.maxY() + DOOR_MARGIN;
    }

    /**
     * The followers' trespass guard (PandiO/knk-workspace#506; the navigation service's guard watches leaders and
     * followers on a leg of their own): a region the follower stands in now and did not stand in at the last look
     * was entered; the entry is judged by the current rules from the regions it came from. Logged and counted as
     * {@link Event#TRESPASS}; must stay 0.
     */
    private void guard(Follower f, NavPoint at, Set<String> here) {
        Set<String> before = f.regions;
        f.regions = here;
        f.guardTick = now;
        if (before == null || before.containsAll(here)) {
            return;
        }
        for (String region : here) {
            if (!before.contains(region) && !verdicts.computeIfAbsent(new Verdict(before, region), v -> ports.rules()
                .mayEnter(new NpcAccessSubject(f.mover.name(), profile, at.world(), before)).test(region))) {
                ports.metrics().count(Event.TRESPASS);
                ports.log().accept(String.format(Locale.ROOT, "TRESPASS %s entered %s at %d %d %d (a follower of group %d)",
                    f.mover.name(), region, at.blockX(), at.blockY(), at.blockZ(), id));
            }
        }
    }

    /** Whether a follower standing in the regions {@code here} may walk on to a path point under the current rules. */
    private boolean mayWalk(Follower f, Set<String> here, int index) {
        return mayEnterAll(f, here, path.regions(ports.world(), index), path.get(index).floor.world());
    }

    /** Whether a follower standing in the regions {@code here} may walk into {@code regions} under the current rules. */
    private boolean mayEnterAll(Follower f, Set<String> here, Set<String> regions, String world) {
        if (here.containsAll(regions)) {
            return true; // a region it stands in is not entered
        }
        f.sentIntoRegion = true;
        for (String region : regions) {
            if (!here.contains(region) && !verdicts.computeIfAbsent(new Verdict(here, region), v -> ports.rules()
                .mayEnter(new NpcAccessSubject(f.mover.name(), profile, world, here))
                .test(region))) {
                return false;
            }
        }
        return true;
    }

    private void speed(Follower f) {
        if (settings.catchUpSpeed() <= 1) {
            return;
        }
        if (!f.boosted && f.lag > CATCH_UP_LAG) {
            f.boosted = true;
            f.mover.setSpeedFactor(settings.catchUpSpeed());
        } else if (f.boosted && f.lag < CAUGHT_UP_LAG) {
            normalSpeed(f);
        }
    }

    private void normalSpeed(Follower f) {
        if (f.boosted) {
            f.boosted = false;
            f.mover.setSpeedFactor(1);
        }
    }

    // ==================== a follower's own leg back ====================

    /**
     * One leg of the follower's own, through the navigation service (its capture, its rules, its retries): to the
     * path point nearest its slot that one local leg reaches, never along the whole route.
     */
    private void rejoin(Follower f, double slotArc, String why) {
        NavPoint at = f.mover.position().orElse(null);
        if (at == null || path.isEmpty()) {
            return;
        }
        // the group path's nearest reachable point ahead of it, on its own floor, never past its slot (4b.2 items 5, 9)
        double reach = Math.max(2, settings.hopBlocks() - 2);
        int place = path.nearest(at, 0, leaderIdx + 1);
        int last = Math.min(path.indexAtArc(Math.max(0, slotArc)), leaderIdx);
        int from = place;
        if (f.failedTarget >= 0 && f.failedFrom != null && f.failedFrom.horizontalDistance(at) <= 1
            && Math.abs(f.failedFrom.y() - at.y()) < 1) {
            from = Math.max(from, f.failedTarget + 1); // never the target that failed from this spot again
        }
        Set<String> here = regionsAt(at);
        PathTargets.Target ahead = from > last ? null : PathTargets.ahead(ports.world(), path.floors(), place, from,
            last, at.blockY() - 1, reach, feet -> mayStand(f, here, feet));
        if (ahead == null && from > place) {
            // every point ahead failed from here: start over with them rather than aim at nothing
            f.failedTarget = -1;
            ahead = PathTargets.ahead(ports.world(), path.floors(), place, place, last, at.blockY() - 1, reach,
                feet -> mayStand(f, here, feet));
        }
        int target = ahead != null ? ahead.index() : Math.max(place, 0);
        if (target > leaderIdx) {
            target = Math.max(0, leaderIdx); // further than one leg: navigation plans what it takes
        }
        NavPoint aim = ahead != null ? ahead.floor() : path.get(target).floor;
        if (at.horizontalDistance(aim) <= 1.5 && Math.abs(aim.y() + 1 - at.y()) <= 2) {
            // nothing ahead to aim at but the point it stands on: an own leg there would "arrive" at once and hide
            // that it is stuck (4b.2 item 11)
            f.rejoinNode = target;
            rejoinFailed(f, "no point ahead on its floor to aim at");
            return;
        }
        if (f.mover.isMoving()) {
            f.mover.stop();
        }
        normalSpeed(f);
        f.leavingDoor = false;
        f.state = FollowerState.REJOINING;
        f.heldByRule = false;
        f.rejoinNode = target;
        f.settled = false;
        int order = ++f.rejoinSeq;
        ports.metrics().count(Event.GROUP_REJOIN);
        f.failedFrom = at;
        ports.log().accept(String.format(Locale.ROOT, "%s (group %d) %s at %d %d %d: its own leg back to the group's path"
            + " at %s", f.mover.name(), id, why, at.blockX(), at.blockY(), at.blockZ(),
            NavGeometry.block(HopPlanner.feet(ahead != null ? ahead.floor() : path.get(target).floor))));
        if (!ports.navigation().navigate(f.mover.id(), HopPlanner.feet(aim),
            result -> onRejoin(f, order, result))) {
            rejoinFailed(f, "it is not under navigation");
        }
    }

    private void onRejoin(Follower f, int order, NavigationResult result) {
        if (order != f.rejoinSeq || f.state != FollowerState.REJOINING) {
            return;
        }
        switch (result) {
            case NavigationResult.Arrived arrived -> {
                f.state = FollowerState.FOLLOWING;
                f.pathIdx = f.rejoinNode < path.size() ? f.rejoinNode : -1;
                f.joining = true;
                f.failures = 0;
                f.lost = false;
                f.lostRetryTicks = 0;
                f.failedTarget = -1;
                f.mover.position().ifPresent(feet -> spellFreed(f, feet));
                f.sentEndArc = Double.NaN;
                f.progressArc = -1;
                f.progressTick = now;
            }
            case NavigationResult.Blocked blocked -> { } // it waits at the block under navigation's rules
            case NavigationResult.Failed failed -> rejoinFailed(f, failed.reason());
            case NavigationResult.Cancelled cancelled -> rejoinFailed(f, cancelled.reason());
        }
    }

    private void rejoinFailed(Follower f, String reason) {
        f.state = FollowerState.RETRY_WAIT;
        f.pathIdx = -1;
        f.sentEndArc = Double.NaN;
        f.failedTarget = f.rejoinNode;
        if (f.ladder.isOpen()) {
            if (f.ladder.current() == RecoveryLadder.Step.REPLAN) {
                spellNotFreed(f, "its own leg back failed (" + reason + ")");
            } else {
                // the local leg of its ladder failed: the next step, a replan of that leg, after the wait
                f.ladder.tryFailed(now, settings.recoveryWaitTicks());
                f.retryAt = now + settings.recoveryWaitTicks();
                return;
            }
        }
        if (++f.failures >= MAX_REJOINS) {
            if (!f.lost) {
                f.lost = true;
                ports.metrics().count(Event.GROUP_LOST);
                ports.log().accept(f.mover.name() + " (group " + id + ") could not get back to the group's path ("
                    + reason + "): the group goes on without it, it keeps trying");
            }
            // tries spaced out: doubling from no-route-wait-ticks up to 60 s, back to the start after any progress or
            // a gate or access event (developer decision 2026-11-15)
            f.lostRetryTicks = f.lostRetryTicks <= 0 ? settings.noRouteWaitTicks()
                : Math.min(MAX_LOST_RETRY_TICKS, f.lostRetryTicks * 2);
            f.retryAt = now + f.lostRetryTicks;
            stepAside(f);
        } else {
            f.retryAt = now + REJOIN_RETRY_TICKS;
        }
    }

    // ==================== followers that finish their trip (implementation plan 4b.3 items 8 to 11) ====================

    /** How many followers finish a trip of their own now. */
    public int finishing() {
        return followers(FollowerState.FINISHING);
    }

    /**
     * The leader of an order with {@code group-followers-finish-trip} on has arrived and the order ends: every
     * follower still on its way keeps the group's path from its place to its slot at the goal as a trip of its own.
     */
    private void beginTrips(NavPoint ended) {
        NavPoint at = leader.position().orElse(null);
        if (at == null || path.isEmpty() || leaderIdx < 0) {
            return;
        }
        double leaderArc = path.arcOf(at, leaderIdx);
        List<Formation.Slot> slots = levelSlots.get(level);
        for (int i = 0; i < followers.size(); i++) {
            Follower f = followers.get(i);
            if (f.state != FollowerState.FOLLOWING || !f.mover.isSpawned() || f.lost || f.ladder.isOpen()
                || f.pathIdx < 0 || f.pathIdx >= path.size() || !(f.heldByRule || !f.settled || f.lag > CATCH_UP_LAG)) {
                continue;
            }
            NavPoint feet = f.mover.position().orElse(null);
            if (feet == null) {
                continue;
            }
            Formation.Slot slot = slots.get(i);
            double slotArc = leaderArc - slot.back();
            int end = path.indexAtArc(slotArc);
            double a = path.arcOf(feet, f.pathIdx);
            List<TripPoint> trip = new ArrayList<>();
            List<NavPoint> points = new ArrayList<>();
            for (int k = path.arc(f.pathIdx) > a + 0.3 ? f.pathIdx : f.pathIdx + 1; k <= end; k++) {
                NavPoint p = continuous(points, feet, lanePoint(k, 0, slot.side()), k);
                if (points.isEmpty() || points.get(points.size() - 1).horizontalDistance(p) > 0.3
                    || points.get(points.size() - 1).y() != p.y()) {
                    points.add(p);
                    trip.add(new TripPoint(p, path.regions(ports.world(), k)));
                }
            }
            double along = slotArc - path.arc(end);
            if (along > 0.2) {
                trip.add(new TripPoint(lanePoint(end, along, slot.side()), path.regions(ports.world(), end)));
            }
            if (trip.isEmpty() || feet.horizontalDistance(trip.get(trip.size() - 1).feet()) <= 1.0) {
                continue; // at its slot after all
            }
            f.state = FollowerState.FINISHING;
            f.trip = trip;
            f.tripIdx = 0;
            f.tripSent = -1;
            f.tripTries = 0;
            f.tripDoors = new LinkedHashSet<>(routeDoors);
            f.tripGoal = ended;
            f.recheck = true;
            f.progressArc = trip.size();
            f.progressTick = now;
            NavPoint last = trip.get(trip.size() - 1).feet();
            ports.log().accept(String.format(Locale.ROOT, "%s (group %d) finishes its trip to %d %d %d first%s",
                f.mover.name(), id, last.blockX(), last.blockY(), last.blockZ(), f.heldByRule ? " (held by a rule now)" : ""));
        }
    }

    /**
     * A finishing follower: its trip with the group's lanes, rule checks, door box and trespass guard; it waits at a
     * door or district edge that stops it and walks on when the rule allows. A stuck report or no progress for
     * {@code no-progress-ticks} hands it its waypoints again; after {@code max-retries} such tries it gives the
     * trip up and follows the group.
     */
    private void finishingStep(Follower f) {
        NavPoint at = f.mover.position().orElse(null);
        if (at == null) {
            return;
        }
        int size = f.trip.size();
        NavPoint last = f.trip.get(size - 1).feet();
        if (at.horizontalDistance(last) <= 1.0 && Math.abs(last.y() - at.y()) < 1.5) {
            tripDone(f, at);
            return;
        }
        int best = f.tripIdx;
        double bestD = Double.MAX_VALUE;
        for (int k = f.tripIdx; k < Math.min(size, f.tripIdx + WINDOW); k++) {
            NavPoint p = f.trip.get(k).feet();
            double d = at.horizontalDistance(p) + Math.abs(p.y() - at.y());
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        f.tripIdx = best;
        double remaining = size - best;
        if (remaining < f.progressArc - 0.5) {
            f.progressArc = remaining;
            f.progressTick = now;
            f.tripTries = 0;
        }
        boolean stuck = f.engineStuck != null
            || (!f.heldByRule && now - f.progressTick >= settings.noProgressTicks());
        f.engineStuck = null;
        if (stuck) {
            f.progressTick = now;
            if (++f.tripTries > Math.max(1, settings.maxRetries())) {
                ports.log().accept(String.format(Locale.ROOT, "%s (group %d) gives up its trip at %d %d %d: it follows the"
                    + " group", f.mover.name(), id, at.blockX(), at.blockY(), at.blockZ()));
                releaseFollower(f);
                return;
            }
            if (f.mover.isMoving()) {
                f.mover.stop();
            }
            f.recheck = true;
        }
        boolean handover = f.tripSent >= 0 && f.tripSent < size - 1 && f.tripSent - best < 3;
        if (f.recheck || handover || !f.mover.isMoving()) {
            // a held one looks again every turn, as a following follower does: a door that was still opening at its
            // event lets it through once it is open
            sendTrip(f, at);
        }
    }

    /** Hands a finishing follower the next stretch of its trip, as far as the current rules let it walk. */
    private void sendTrip(Follower f, NavPoint at) {
        Set<String> here = regionsAt(at);
        guard(f, at, here);
        List<GateBox> doors = blockingDoors(f, at.world(), here, f.tripDoors);
        GateBox inDoor = null;
        for (GateBox box : doors) {
            if (inDoorWay(box, at)) {
                inDoor = box;
                break;
            }
        }
        f.recheck = false;
        f.sentIntoRegion = false;
        List<NavPoint> waypoints = new ArrayList<>();
        boolean cut = false;
        int k = f.tripIdx;
        if (k < f.trip.size() - 1 && at.horizontalDistance(f.trip.get(k).feet()) < 0.75) {
            k++;
        }
        int sent = -1;
        for (; k < f.trip.size() && waypoints.size() < MAX_WAYPOINTS; k++) {
            TripPoint p = f.trip.get(k);
            if (!here.containsAll(p.regions())) {
                f.sentIntoRegion = true;
            }
            if (!mayEnterAll(f, here, p.regions(), at.world()) || intoDoor(doors, inDoor, p.feet())) {
                cut = true;
                break;
            }
            add(waypoints, p.feet());
            sent = k;
        }
        if (cut && f.queue > 0 && k < f.trip.size()) {
            NavPoint stop = f.trip.get(k).feet();
            double keep = f.queue * settings.formation().spacing();
            while (!waypoints.isEmpty() && stop.horizontalDistance(waypoints.get(waypoints.size() - 1)) < keep) {
                waypoints.remove(waypoints.size() - 1);
            }
        }
        f.cut = cut;
        f.heldByRule = cut && waypoints.isEmpty();
        f.tripSent = sent;
        if (waypoints.isEmpty()) {
            if (f.mover.isMoving()) {
                f.mover.stop();
            }
            f.progressTick = now; // waiting at a door or an edge is not being stuck
            return;
        }
        f.mover.follow(WaypointChain.bridged(ports.world(), f.mover.position().orElse(null), waypoints),
            settings.waypointMargin());
    }

    private void tripDone(Follower f, NavPoint at) {
        NavPoint goalOfTrip = f.tripGoal;
        ports.metrics().count(Event.GROUP_FINISH_TRIP);
        ports.log().accept(String.format(Locale.ROOT, "%s (group %d) finished its trip at %d %d %d", f.mover.name(), id,
            at.blockX(), at.blockY(), at.blockZ()));
        releaseFollower(f); // it follows the group's current order from where it is (an own leg back if far off)
        f.progressTick = now;
        f.failures = 0;
        f.lost = false;
        f.lag = 0;
        f.settled = true;
        f.regions = null;
        if (memberListener != null) {
            memberListener.onMemberArrived(f.mover, goalOfTrip);
        }
    }

    // ==================== closing up ====================

    /** The leader waits when a follower fell too far behind, and walks on when the group has closed up. */
    private void closeUp(NpcNavState leaderState, double maxLag) {
        double limit = settings.regroupBlocks();
        if (limit <= 0) {
            return;
        }
        if (!holding) {
            if (maxLag > limit && leaderState == NpcNavState.MOVING) {
                holding = true;
                holdingSince = now;
                seq++; // the leader's Cancelled is this group's own doing
                ports.navigation().cancel(leader.id(), "waits for its group");
                ports.metrics().count(Event.GROUP_REGROUP);
                ports.log().accept(String.format(Locale.ROOT, "group %d (%s) waits: a follower is %.0f blocks behind its slot",
                    id, leader.name(), maxLag));
            }
            return;
        }
        boolean timeout = now - holdingSince >= MAX_HOLD_TICKS;
        if (maxLag <= limit / 2 || timeout) {
            if (timeout) {
                for (Follower f : followers) {
                    if (f.lag > limit / 2 && !f.lost) {
                        f.lost = true; // not waited for any longer; it still walks after the group
                        ports.metrics().count(Event.GROUP_LOST);
                    }
                }
                ports.log().accept("group " + id + " (" + leader.name() + ") walks on: a follower did not close up in "
                    + MAX_HOLD_TICKS + " ticks");
            }
            holding = false;
            orderLeader();
        }
    }
}
