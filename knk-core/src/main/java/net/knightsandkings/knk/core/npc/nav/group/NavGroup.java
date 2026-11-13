package net.knightsandkings.knk.core.npc.nav.group;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import net.knightsandkings.knk.core.npc.nav.BlockingElement;
import net.knightsandkings.knk.core.npc.nav.HopPlanner;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcAccessSubject;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavState;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.RouteFollower;

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
 * current rules; it stops before the first one it may not enter. Closed gates are the leader's affair on the way
 * ahead; behind the leader a closed gate stops a follower bodily and its own leg back then waits at the gate under
 * the navigation service's rules.
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

    public enum FollowerState {
        /** Walks the group's path in its slot. */
        FOLLOWING,
        /** Walks its own leg back to the path (an order of its own in the navigation service). */
        REJOINING,
        /** Its own leg failed: waits before the next try. */
        RETRY_WAIT
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
        /** The waypoints it has lead into a region it did not stand in when it got them. */
        boolean sentIntoRegion;
        /** The rules may have changed since it got its waypoints: they are handed out again. */
        boolean recheck;
        /** How far behind its slot (negative: ahead), from the last look. */
        double lag;
        boolean settled = true;

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
    private List<Formation.Slot> formed = List.of();
    private List<Formation.Slot> column = List.of();
    private double halfWidth;
    private double depth;
    private double front;

    private final GroupPath path = new GroupPath();
    private int leaderIdx = -1;
    private RouteFollower line;
    private int lineStart;
    private int lineBase;
    private int lineAdded;
    private boolean onLine;
    private boolean folded;

    private NavPoint goal;
    private Consumer<NavigationResult> onResult;
    private int seq;
    private long leaderDoneTick = -1;
    private boolean holding;
    private long holdingSince;
    private boolean blockedReported;
    private BlockingElement reportedElement;
    private final Map<Verdict, Boolean> verdicts = new HashMap<>();
    private long verdictTick;
    private long now;
    private boolean disbanded;

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
        formed = formation.slots(n);
        column = formation.columnSlots(n);
        halfWidth = formation.halfWidth(n);
        depth = formation.depth(n);
        front = formation.front(n);
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

    /** Whether the group walks as a column now because its path is too narrow for its formation. */
    public boolean folded() {
        return folded;
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
            f.failures = 0;
            f.lost = false;
            f.lag = 0;
            f.settled = true;
            f.progressTick = now;
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

    /** A gate or an access rule changed: followers waiting on the rules or on a failed leg try again now. */
    void rulesChanged() {
        verdicts.clear();
        for (Follower f : followers) {
            f.recheck = true;
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
        goal = null;
        onResult = null;
        seq++;
        holding = false;
        leaderDoneTick = -1;
        if (!(result instanceof NavigationResult.Arrived)) {
            ports.navigation().cancel(leader.id(), "the group's order ended");
        }
        for (Follower f : followers) {
            releaseFollower(f);
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
        f.state = FollowerState.FOLLOWING;
        f.pathIdx = -1;
        f.sentEndArc = Double.NaN;
        f.heldByRule = false;
        f.sentIntoRegion = false;
        f.recheck = false;
        normalSpeed(f);
    }

    private void resetPath() {
        path.clear();
        leaderIdx = -1;
        line = null;
        onLine = false;
        for (Follower f : followers) {
            releaseFollower(f);
        }
    }

    // ==================== tick ====================

    void tick(long tick) {
        now = tick;
        if (goal == null || (tick + id) % GROUP_TICKS != 0) {
            return;
        }
        NavPoint at = leader.position().orElse(null);
        if (at == null) {
            return; // the leader's chunk is unloaded: navigation resumes it; the followers finish what they have
        }
        if (tick - verdictTick >= Math.max(20, settings.recheckTicks())) {
            // a rule change that came without an event: ask again, and look again at waypoints that cross into
            // a region (the navigation service's own recheck does the same for the leader)
            verdicts.clear();
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
        decideFold();
        NpcNavState leaderState = ports.navigation().state(leader.id());
        boolean walking = leaderState == NpcNavState.MOVING && onLine && leaderDoneTick < 0 && !holding;
        double leaderArc = path.arcOf(at, leaderIdx);
        double lookahead = walking ? Math.max(0, Math.min(LOOKAHEAD, path.endArc() - leaderArc - front)) : 0;
        List<Formation.Slot> slots = folded ? column : formed;
        double maxLag = 0;
        boolean settled = true;
        for (int i = 0; i < followers.size(); i++) {
            Follower f = followers.get(i);
            step(f, slots.get(i), leaderArc, lookahead);
            if (f.mover.isSpawned() && !f.lost && !f.heldByRule) {
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
                double wanted = LOOKAHEAD + front + 2 * RouteFollower.STEP;
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

    /** Column where the path is too narrow for the formation under the group, the formation again after. */
    private void decideFold() {
        boolean narrow = false;
        if (halfWidth > 0.01) {
            double leaderArc = path.arc(leaderIdx);
            double to = leaderArc + front + 1;
            for (int i = path.indexAtArc(Math.max(0, leaderArc - depth - 1)); i < path.size() && path.arc(i) <= to; i++) {
                if (!path.wide(ports.world(), i, halfWidth)) {
                    narrow = true;
                    break;
                }
            }
        }
        if (narrow == folded) {
            return;
        }
        folded = narrow;
        ports.metrics().count(narrow ? Event.GROUP_FOLD : Event.GROUP_REFORM);
        NavPoint at = path.get(leaderIdx).floor;
        ports.log().accept(String.format(Locale.ROOT, "group %d (%s, %d NPCs) %s at %d %d %d", id, leader.name(), size(),
            narrow ? "folds into a column: the path is too narrow for " + settings.formation()
                : "takes up its formation again", at.blockX(), at.blockY() + 1, at.blockZ()));
    }

    private void step(Follower f, Formation.Slot slot, double leaderArc, double lookahead) {
        if (!f.mover.isSpawned()) {
            f.pathIdx = -1;
            f.sentEndArc = Double.NaN;
            f.progressTick = now;
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
            rejoin(f, slotArc, "is off its lane");
            return;
        }
        double a = path.arcOf(at, f.pathIdx);
        f.lag = slotArc - a;
        speed(f);
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
        } else if (f.heldByRule) {
            f.progressTick = now;
        } else if (targetArc - a > 1 && now - f.progressTick >= settings.noProgressTicks()) {
            ports.metrics().count(Event.STUCK_NO_PROGRESS);
            rejoin(f, slotArc, "made no progress for " + settings.noProgressTicks() + " ticks");
        }
    }

    /** Hands a follower its lane from where it is to {@code targetArc}, as far as the current rules let it walk. */
    private void send(Follower f, Formation.Slot slot, NavPoint at, double a, double targetArc) {
        Set<String> here = ports.world().regionsAt(at.world(), at.blockX(), at.blockY(), at.blockZ());
        int end = path.indexAtArc(targetArc);
        List<NavPoint> waypoints = new ArrayList<>();
        double endArc = a;
        boolean cut = false;
        f.recheck = false;
        f.sentIntoRegion = false;
        int k = path.arc(f.pathIdx) > a + 0.3 ? f.pathIdx : f.pathIdx + 1;
        for (; k <= end && waypoints.size() < MAX_WAYPOINTS; k++) {
            if (!mayWalk(f, here, k)) {
                cut = true;
                break;
            }
            add(waypoints, lanePoint(k, 0, slot.side()));
            endArc = path.arc(k);
        }
        if (!cut && k > end) {
            // the slot itself: between two path points, or past the path's end (a slot ahead of the leader)
            double along = targetArc - path.arc(end);
            if (along > 0.2 && mayWalk(f, here, end)) {
                add(waypoints, lanePoint(end, along, slot.side()));
                endArc = targetArc;
            }
        }
        f.heldByRule = cut && waypoints.isEmpty();
        if (waypoints.isEmpty()) {
            if (f.mover.isMoving()) {
                f.mover.stop();
            }
            f.sentEndArc = a;
            return;
        }
        f.mover.follow(waypoints);
        f.sentEndArc = endArc;
    }

    private static void add(List<NavPoint> waypoints, NavPoint p) {
        if (waypoints.isEmpty() || waypoints.get(waypoints.size() - 1).horizontalDistance(p) > 0.3
            || waypoints.get(waypoints.size() - 1).y() != p.y()) {
            waypoints.add(p);
        }
    }

    /**
     * The lane's point at a path point; the path's centre where the lane has no floor of its own there or at the
     * path points next to it (so a lane leaves the centre a step after a narrow place and is back on it a step
     * before the next, not around its corner).
     */
    private NavPoint lanePoint(int index, double along, double side) {
        NavPoint p = null;
        if (side != 0 && laneFree(index - 1, side) && laneFree(index, side) && laneFree(index + 1, side)
            && (along == 0 || laneFree(index + 2, side))) {
            p = path.point(ports.world(), index, along, side);
        }
        if (p == null && along != 0) {
            p = path.point(ports.world(), index, along, 0);
        }
        return p != null ? p : HopPlanner.feet(path.get(index).floor);
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
        return path.point(ports.world(), index, 0, side) != null;
    }

    /** Whether a follower standing in the regions {@code here} may walk on to a path point under the current rules. */
    private boolean mayWalk(Follower f, Set<String> here, int index) {
        Set<String> regions = path.regions(ports.world(), index);
        if (here.containsAll(regions)) {
            return true; // a region it stands in is not entered
        }
        f.sentIntoRegion = true;
        for (String region : regions) {
            if (!here.contains(region) && !verdicts.computeIfAbsent(new Verdict(here, region), v -> ports.rules()
                .mayEnter(new NpcAccessSubject(f.mover.name(), profile, path.get(index).floor.world(), here))
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
        double reach = Math.max(2, settings.hopBlocks() - 2);
        int target = -1;
        for (int k = Math.min(path.indexAtArc(Math.max(0, slotArc)), leaderIdx); k >= 0; k--) {
            NavPoint p = path.get(k).floor;
            if (at.horizontalDistance(p) <= reach && Math.abs(p.y() + 1 - at.y()) <= 6) {
                target = k;
                break;
            }
        }
        if (target < 0) {
            target = path.nearest(at, 0, leaderIdx + 1); // further than one leg: navigation plans what it takes
        }
        if (f.mover.isMoving()) {
            f.mover.stop();
        }
        normalSpeed(f);
        f.state = FollowerState.REJOINING;
        f.heldByRule = false;
        f.rejoinNode = target;
        f.settled = false;
        int order = ++f.rejoinSeq;
        ports.metrics().count(Event.GROUP_REJOIN);
        ports.log().accept(String.format(Locale.ROOT, "%s (group %d) %s at %d %d %d: its own leg back to the group's path",
            f.mover.name(), id, why, at.blockX(), at.blockY(), at.blockZ()));
        if (!ports.navigation().navigate(f.mover.id(), HopPlanner.feet(path.get(target).floor),
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
        if (++f.failures >= MAX_REJOINS) {
            if (!f.lost) {
                f.lost = true;
                ports.metrics().count(Event.GROUP_LOST);
                ports.log().accept(f.mover.name() + " (group " + id + ") could not get back to the group's path ("
                    + reason + "): the group goes on without it, it keeps trying");
            }
            f.retryAt = now + settings.noRouteWaitTicks();
        } else {
            f.retryAt = now + REJOIN_RETRY_TICKS;
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
