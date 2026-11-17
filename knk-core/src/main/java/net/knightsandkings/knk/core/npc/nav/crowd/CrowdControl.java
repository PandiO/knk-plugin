package net.knightsandkings.knk.core.npc.nav.crowd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;

import net.knightsandkings.knk.core.npc.nav.HopPlanner;
import net.knightsandkings.knk.core.npc.nav.NavGeometry;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavWorld;
import net.knightsandkings.knk.core.npc.nav.corridor.FreeWidth;

/**
 * Narrow places taken in turns (implementation plan 4c.3 decision 3.2 and 3.3, link M4; BEHAVIOR_MODEL NAV-26).
 *
 * <p>A <b>narrow place</b> is a run of points on an NPC's way ahead where the free width across the way (M1's
 * {@link FreeWidth}: to a face that stops a body, or a drop) is under {@link #NARROW_WIDTH} blocks: one lane, too
 * narrow for two NPCs to pass (a door, an arch, a 1-wide passage). Runs closer than {@link #MERGE_GAP} are one place.
 * NPCs that see the same cells share one {@link Place}; its axis is the first NPC's way through it, and an NPC's
 * direction is the sign of its way along that axis.
 *
 * <p><b>Turn order</b> (decided by the developer 2026-11-15, for now): an NPC already in the place goes first, then
 * first come. An NPC <i>arrives</i> when the place's first cell is within {@link #APPROACH} blocks along its way. It may
 * enter when (1) nobody of the other direction is in the place (a group's leader holds its turn until its followers are
 * through, {@link #setGroupMembers}), (2) it is the earliest of the NPCs waiting at the place, either direction, and
 * (3) the last NPC of its own direction is {@link #QUEUE_SPACING} into the place. Otherwise it waits in a queue on its
 * side, {@link #FIRST_GAP} before the place and {@link #QUEUE_SPACING} apart, {@link #SIDE_OFFSET} to the side of the
 * line where the floor allows, so the way out stays free. <b>Oncoming in a narrow place:</b> an NPC found in a place
 * without its turn while one of the other direction entered before it steps back out to its queue spot (a yield) and
 * waits there. A queue that does not move for {@link #MAX_WAIT_TICKS} lets the NPC go (a deadlock guard): since link B3
 * the time counts from its arrival or from the last time anyone entered the place, whichever is later, so a long single
 * file that drains steadily is never let go, and a place nobody enters still is after 30 s.
 *
 * <p><b>In 3D</b> (link B3, PandiO/knk-workspace#672): a place has a floor along its axis, flat or a flight's slope
 * ({@link Place#levelGap}). Progress and the distance across are measured along it; feet {@link #LEVEL_GAP} or more
 * above or below it are on another level. A holder on another level has passed (it climbed on to the flight above, or
 * fell): its turn ends unless a follower on the place's floor still keeps it. A holder or follower on another level
 * never stops a waiter, and a follower there keeps no turn, so no queue forms against an NPC a flight up or down.
 *
 * <p><i>Future updates (plan 4c.7), hooks only:</i> {@link #earlier} is the one place that orders two NPCs; an
 * authority value per NPC (item 1) and closed formations first (item 2) would go there.
 *
 * <p>The free width per cell and heading is cached (cleared every {@link #WIDTH_CACHE_TICKS} ticks and when a gate
 * changes), so NPCs on the same way read the world once. Engine-neutral over {@link NavWorld}; main thread.
 */
public final class CrowdControl {

    /** A free width under this many blocks is one lane (as the corridor's chokepoint, plan 4c.3 decision 1.4). */
    public static final double NARROW_WIDTH = 2.0;
    /** Blocks of the way ahead looked at for a narrow place. */
    public static final double LOOK_AHEAD = 6.0;
    /** An NPC arrives at a place when its first cell is this many blocks along its way. */
    public static final double APPROACH = 4.0;
    /** The first queue spot is this many blocks before the place. */
    public static final double FIRST_GAP = 1.5;
    /** Blocks between two queue spots, and how far into the place the one ahead must be (the body's 0.6 plus 0.4). */
    public static final double QUEUE_SPACING = 1.0;
    /** Queue spots stand this far to the side of the line where the floor allows. */
    public static final double SIDE_OFFSET = 1.0;
    /**
     * An NPC has passed a place when it is this far beyond the place's end (the body's half width 0.3 plus 0.2): the
     * next NPC of the other direction starts from its queue spot, at least {@link #FIRST_GAP} away.
     */
    public static final double PASSED = 0.5;
    /**
     * Feet this far above or below the place's floor are on another level (link B3, PandiO/knk-workspace#672): a step
     * (1) or a jump (1.25) still counts as the place's floor, a flight or floor stacked above or below (feet 3 or more
     * apart) does not. Measured from the band of feet heights the floor allows at that point along the axis
     * ({@link Place#levelGap}).
     */
    public static final double LEVEL_GAP = 1.5;
    /**
     * A queue that has not moved for this long (30 s: nobody entered the place since the NPC arrived or since the last
     * one entered) lets it go (logged, counted). Before link B3 the time counted from its arrival only, so the last of a
     * long single file that kept moving was let go too.
     */
    public static final long MAX_WAIT_TICKS = 600;
    /** A turn held longer than this (30 s) is dropped (the NPC is stuck in the place; recovery has it). */
    public static final long STALE_TICKS = 600;
    /** The free-width cache is cleared this often. */
    public static final long WIDTH_CACHE_TICKS = 200;
    /** The steepest floor a place is fitted with (a full-block stair rises 1 per block). */
    static final double MAX_SLOPE = 1.0;
    /** Narrow runs closer than this along the way are one place. */
    static final double MERGE_GAP = 2.0;
    /** Points looked at along the way, this far apart. */
    static final double SAMPLE = 0.5;
    /** A follower this far before the place (or in it) keeps its leader's turn. */
    static final double MEMBER_BEFORE = 1.5;
    /** A place nobody used for this long is forgotten. */
    static final long PLACE_IDLE_TICKS = 1200;
    /** Tickets of NPCs not seen for this long are dropped. */
    static final long TICKET_UNSEEN_TICKS = 40;

    /** What an NPC does now. */
    public enum Kind {
        /** Walk on: no narrow place near, or its turn. */
        GO,
        /** Wait at {@link Decision#spot()} (null: where it stands) for its turn. */
        WAIT,
        /** It is in a narrow place without its turn and someone of the other direction entered first: step back out. */
        YIELD
    }

    /**
     * @param spot       the queue spot to stand on (feet), null to stand where it is
     * @param queueIndex its place in the queue of its direction, 0 first
     * @param place      the narrow place, null for {@link Kind#GO} without one
     * @param overdue    a GO because the queue did not move for {@link #MAX_WAIT_TICKS}
     */
    public record Decision(Kind kind, NavPoint spot, int queueIndex, Place place, boolean overdue) {
        static final Decision GO = new Decision(Kind.GO, null, 0, null, false);
    }

    /** A narrow place NPCs take in turns. */
    public static final class Place {
        final int id;
        final String world;
        final Set<Long> cells = new HashSet<>();
        /** The axis (unit) of the first way through it. */
        final double ax;
        final double az;
        double cx;
        double cz;
        double cy;
        double halfLength;
        /** The floor along the axis (feet level at the middle, rise per block along +axis, the cells' span). */
        double levelMid;
        double slope;
        double sLo;
        double sHi;
        final List<Ticket> entered = new ArrayList<>();
        final List<Ticket> waiting = new ArrayList<>();
        long lastUsed;
        int longestQueue;

        Place(int id, String world, double ax, double az) {
            this.id = id;
            this.world = world;
            this.ax = ax;
            this.az = az;
        }

        public int id() {
            return id;
        }

        /** Its middle (feet height of its first cell). */
        public NavPoint centre() {
            return new NavPoint(world, cx, cy, cz);
        }

        /** Half its length along its axis, to the cells' faces. */
        public double halfLength() {
            return halfLength;
        }

        /** NPCs waiting for their turn now. */
        public int waiting() {
            return waiting.size();
        }

        /** NPCs with their turn now. */
        public int inside() {
            return entered.size();
        }

        /** How far along {@code dir} (+1 or -1 along the axis) a feet position is from the middle. */
        double progress(int dir, NavPoint feet) {
            return ((feet.x() - cx) * ax + (feet.z() - cz) * az) * dir;
        }

        double lateral(NavPoint feet) {
            return Math.abs(-(feet.x() - cx) * az + (feet.z() - cz) * ax);
        }

        /**
         * How far a feet position is above or below the place's floor at its point along the axis, 0 on it (link B3).
         * The floor is the cells' feet level fitted along the axis (a flat place, or a flight's slope). Over the cells
         * a body's feet may be anywhere in its cell, so the band is [level, level + 1). Before and after the cells the
         * band spans from the end's level to the slope carried on for up to {@link #APPROACH} blocks: the flat landing
         * at a flight's ends and the flight's own steps both count.
         */
        double levelGap(NavPoint feet) {
            double s = progress(1, feet);
            double end = levelMid + slope * Math.max(sLo, Math.min(sHi, s));
            double on = levelMid + slope * Math.max(sLo - APPROACH, Math.min(sHi + APPROACH, s));
            double lo = Math.min(end, on);
            double hi = Math.max(end, on) + 1;
            double y = feet.y();
            return y < lo ? lo - y : y > hi ? y - hi : 0;
        }

        /** The floor's feet level at a position's point along the axis, clamped to the cells (diagnostics). */
        double levelAt(NavPoint feet) {
            return levelMid + slope * Math.max(sLo, Math.min(sHi, progress(1, feet)));
        }

        /** Whether a feet position is on another level than the place's floor ({@link #LEVEL_GAP} or more off it). */
        boolean offLevel(NavPoint feet) {
            return levelGap(feet) >= LEVEL_GAP;
        }

        /** Fits the floor along the axis to the cells' feet levels (least squares of level over progress). */
        private void fitLevel() {
            double n = cells.size();
            double ss = 0;
            double sy = 0;
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            for (long c : cells) {
                double along = (unpackX(c) + 0.5 - cx) * ax + (unpackZ(c) + 0.5 - cz) * az;
                ss += along;
                sy += unpackY(c);
                lo = Math.min(lo, along);
                hi = Math.max(hi, along);
            }
            double ms = ss / n;
            double my = sy / n;
            double cov = 0;
            double var = 0;
            for (long c : cells) {
                double along = (unpackX(c) + 0.5 - cx) * ax + (unpackZ(c) + 0.5 - cz) * az;
                cov += (along - ms) * (unpackY(c) - my);
                var += (along - ms) * (along - ms);
            }
            slope = var < 1e-6 ? 0 : Math.max(-MAX_SLOPE, Math.min(MAX_SLOPE, cov / var));
            levelMid = my - slope * ms;
            sLo = lo - 0.5;
            sHi = hi + 0.5;
        }

        void add(Set<Long> more, int y) {
            if (cells.isEmpty()) {
                cy = y;
            }
            cells.addAll(more);
            double sx = 0;
            double sz = 0;
            for (long c : cells) {
                sx += unpackX(c) + 0.5;
                sz += unpackZ(c) + 0.5;
            }
            cx = sx / cells.size();
            cz = sz / cells.size();
            double half = 0;
            for (long c : cells) {
                double along = Math.abs((unpackX(c) + 0.5 - cx) * ax + (unpackZ(c) + 0.5 - cz) * az);
                half = Math.max(half, along + 0.5);
            }
            halfLength = half;
            fitLevel();
        }
    }

    /** One NPC's claim on a place: waiting for its turn, or in it. */
    static final class Ticket {
        final int npc;
        final Place place;
        final int dir;
        final long arrived;
        final long seq;
        boolean entered;
        long enteredSeq;
        long enteredTick;
        long seen;
        /** Others that entered the place while this one waited (for "no longer than the queue needs"). */
        int passedAhead;
        /** When it arrived, or when the last one entered the place while it waited: the queue last moved (link B3). */
        long queueMoved;
        boolean yielded;
        /** What kept it waiting, by kind and NPC (diagnostics, link B3): how often, and where that NPC stood last. */
        final Map<String, Block> blocks = new java.util.LinkedHashMap<>();

        void noteBlock(String kind, int other, NavPoint at, double progress, double levelGap) {
            String key = kind + other;
            Block b = blocks.get(key);
            blocks.put(key, new Block(kind, other, b == null ? 1 : b.looks() + 1, at, progress, levelGap));
        }

        Ticket(int npc, Place place, int dir, long arrived, long seq) {
            this.npc = npc;
            this.place = place;
            this.dir = dir;
            this.arrived = arrived;
            this.queueMoved = arrived;
            this.seq = seq;
        }
    }

    /** A narrow run found on a way. */
    record Run(Set<Long> cells, int y, double entryDist, double ax, double az) {
    }

    private final Occupancy occupancy;
    private final Map<String, Map<Long, Place>> placeByCell = new HashMap<>();
    private final Map<Integer, Ticket> tickets = new HashMap<>();
    private final Map<String, Map<Long, Float>> widths = new HashMap<>();
    private IntFunction<int[]> groupMembers = id -> new int[0];
    private long seq;
    private long widthsClearedAt;
    private int nextPlaceId = 1;
    // ---- counts (stats) ----
    private long waits;
    private long yields;
    private long overdue;
    private int longestQueue;
    private long longestWaitTicks;
    /** The longest wait per NPC that passed ahead of the waiting one, in ticks (the queue's "need"). */
    private double worstWaitPerPass;
    private long reads;

    public CrowdControl(Occupancy occupancy) {
        this.occupancy = Objects.requireNonNull(occupancy, "occupancy");
    }

    /** The occupancy this control reads positions from. */
    public Occupancy occupancy() {
        return occupancy;
    }

    /**
     * A group leader's followers (their mover ids; empty for anyone else): the leader keeps its turn at a place until
     * its followers are through, so nobody of the other direction cuts into the column.
     */
    public void setGroupMembers(IntFunction<int[]> members) {
        this.groupMembers = members == null ? id -> new int[0] : members;
    }

    /**
     * NPCs with bodies (collision on, link M7): their queue spots keep the edge guard (a spot on a cell next to a drop
     * is skipped, so a waiting NPC a push moves has a cell of margin). Default none.
     */
    public void setBodies(java.util.function.IntPredicate bodies) {
        this.bodies = bodies == null ? id -> false : bodies;
    }

    private java.util.function.IntPredicate bodies = id -> false;

    // ==================== the update ====================

    /**
     * Housekeeping at each occupancy rebuild: turns of NPCs that passed their place (and whose followers are through)
     * end, stale turns and tickets of NPCs no longer seen are dropped, idle places forgotten, the width cache cleared
     * on its timer.
     */
    public void housekeeping(long tick) {
        if (tick - widthsClearedAt >= WIDTH_CACHE_TICKS) {
            widths.clear();
            widthsClearedAt = tick;
        }
        for (Iterator<Ticket> it = tickets.values().iterator(); it.hasNext(); ) {
            Ticket t = it.next();
            boolean drop;
            if (t.entered) {
                NavPoint at = occupancy.positionOf(t.npc);
                drop = at == null || tick - t.enteredTick > STALE_TICKS
                    || (passed(t.place, t.dir, at) && membersClear(t));
            } else {
                drop = tick - t.seen > TICKET_UNSEEN_TICKS;
            }
            if (drop) {
                it.remove();
                detach(t);
            }
        }
        for (Map<Long, Place> world : placeByCell.values()) {
            world.values().removeIf(p -> p.entered.isEmpty() && p.waiting.isEmpty()
                && tick - p.lastUsed > PLACE_IDLE_TICKS);
        }
    }

    /**
     * Where a walking NPC stands and the feet points of its way ahead (from the NPC on): what it does now. A held NPC
     * (one that waits or yields) asks with {@link #decideHeld} instead.
     */
    public Decision decide(NavWorld world, int npc, NavPoint at, List<NavPoint> way, long tick) {
        Ticket t = tickets.get(npc);
        if (t != null) {
            t.seen = tick;
            if (t.entered) {
                if (!passed(t.place, t.dir, at) || !membersClear(t)) {
                    return Decision.GO; // in it, before it with its turn, or its followers not through yet
                }
                release(t); // its turn ends
                t = null;
            }
        }
        Run run = findRun(world, at, way);
        if (run == null) {
            if (t != null) {
                release(t);
            }
            return Decision.GO;
        }
        Place place = placeFor(world, at.world(), run, tick);
        int dir = run.ax * place.ax + run.az * place.az >= 0 ? 1 : -1;
        if (t != null && (t.place != place || t.dir != dir)) {
            release(t);
            t = null;
        }
        boolean inside = run.entryDist() < SAMPLE * 0.6;
        if (t == null) {
            if (run.entryDist() > APPROACH) {
                return Decision.GO;
            }
            t = new Ticket(npc, place, dir, tick, ++seq);
            t.seen = tick;
            t.passedAhead = place.entered.size(); // those in it now go first too
            tickets.put(npc, t);
            place.waiting.add(t);
            place.lastUsed = tick;
            noteQueue(place);
        }
        return judge(world, t, at, inside, tick);
    }

    /** A held NPC (waiting or yielding at a place) asks again: GO when its turn came. */
    public Decision decideHeld(NavWorld world, int npc, NavPoint at, long tick) {
        Ticket t = tickets.get(npc);
        if (t == null || t.entered) {
            return Decision.GO;
        }
        t.seen = tick;
        return judge(world, t, at, false, tick);
    }

    private Decision judge(NavWorld world, Ticket t, NavPoint at, boolean inside, long tick) {
        Place p = t.place;
        p.lastUsed = tick;
        if (inside) {
            Ticket oncoming = null;
            for (Ticket e : p.entered) {
                if (e.dir != t.dir && !elsewhere(e) && (oncoming == null || e.enteredSeq < oncoming.enteredSeq)) {
                    oncoming = e;
                }
            }
            if (oncoming == null) {
                enter(t, tick); // already in it: it goes first
                return Decision.GO;
            }
            // both in a narrow place: the second to enter steps aside (decision 3.3)
            if (!t.yielded) {
                t.yielded = true;
                yields++;
            }
            int k = queueIndex(t);
            return new Decision(Kind.YIELD, spot(world, p, t.dir, k, at, bodies.test(t.npc)), k, p, false);
        }
        if (mayEnter(t)) {
            enter(t, tick);
            return Decision.GO;
        }
        if (tick - t.queueMoved >= MAX_WAIT_TICKS) {
            overdue++;
            noteRelease(t, at, tick);
            enter(t, tick);
            return new Decision(Kind.GO, null, 0, p, true);
        }
        int k = queueIndex(t);
        return new Decision(Kind.WAIT, spot(world, p, t.dir, k, at, bodies.test(t.npc)), k, p, false);
    }

    /** The turn rules of the class comment: nobody of the other direction in it, first come, spacing. */
    private boolean mayEnter(Ticket t) {
        Place p = t.place;
        for (Ticket e : p.entered) {
            if (e.dir != t.dir && !elsewhere(e)) {
                noteBlock(t, "oncoming", e.npc, e.dir);
                return false;
            }
        }
        for (Ticket w : p.waiting) {
            if (w != t && earlier(w, t)) {
                noteBlock(t, "earlier", w.npc, w.dir);
                return false;
            }
        }
        for (Ticket e : p.entered) {
            NavPoint pos = occupancy.positionOf(e.npc);
            if (pos != null && !p.offLevel(pos) && p.progress(e.dir, pos) < -p.halfLength + QUEUE_SPACING) {
                noteBlock(t, "spacing", e.npc, e.dir);
                return false;
            }
        }
        return true;
    }

    private void noteBlock(Ticket t, String kind, int other, int dir) {
        NavPoint pos = occupancy.positionOf(other);
        if (pos != null) {
            t.noteBlock(kind, other, pos, t.place.progress(dir, pos), pos.y() - t.place.levelAt(pos));
        }
    }

    /**
     * Whether a turn's holder and every follower that keeps its turn stand on another level than the place (link B3):
     * nobody of that turn is in the place, so it stops nobody. A holder off the place's floor whose followers are still
     * in it keeps its turn for them, as on the place's own level.
     */
    private boolean elsewhere(Ticket e) {
        NavPoint pos = occupancy.positionOf(e.npc);
        return pos != null && e.place.offLevel(pos) && membersClear(e);
    }

    /**
     * Whether {@code a} comes before {@code b} at a place: first come (the earlier arrival, then the earlier ticket).
     * The hook for the future updates of plan 4c.7 (authority values, closed formations first).
     */
    static boolean earlier(Ticket a, Ticket b) {
        return a.arrived != b.arrived ? a.arrived < b.arrived : a.seq < b.seq;
    }

    private int queueIndex(Ticket t) {
        int k = 0;
        for (Ticket w : t.place.waiting) {
            if (w != t && w.dir == t.dir && earlier(w, t)) {
                k++;
            }
        }
        return k;
    }

    private void enter(Ticket t, long tick) {
        Place p = t.place;
        p.waiting.remove(t);
        t.entered = true;
        t.enteredTick = tick;
        t.enteredSeq = ++seq;
        p.entered.add(t);
        for (Ticket w : p.waiting) {
            w.passedAhead++;
            w.queueMoved = tick;
        }
        if (t.arrived < tick) {
            long waited = tick - t.arrived;
            if (waited >= LONG_WAIT_TICKS && tick - t.queueMoved < MAX_WAIT_TICKS && longWaits.size() < RELEASES_KEPT) {
                longWaits.add(new LongWait(t.npc, waited, p.centre(), why(t)));
            }
            longestWaitTicks = Math.max(longestWaitTicks, waited);
            worstWaitPerPass = Math.max(worstWaitPerPass, waited / (double) Math.max(1, t.passedAhead));
        }
    }

    private void noteQueue(Place p) {
        int n = p.waiting.size();
        p.longestQueue = Math.max(p.longestQueue, n);
        longestQueue = Math.max(longestQueue, n);
    }

    /** Counts one wait (the service counts a wait once per spell of waiting). */
    public void countWait() {
        waits++;
    }

    /**
     * Whether a turn's holder is through the place: another world, beyond its end, far to the side, or (link B3) on
     * another level than its floor: it climbed on to the flight above, or fell under it.
     */
    private boolean passed(Place p, int dir, NavPoint at) {
        return !at.world().equals(p.world) || p.offLevel(at) || p.progress(dir, at) > p.halfLength + PASSED
            || p.lateral(at) > LOOK_AHEAD + p.halfLength;
    }

    private boolean membersClear(Ticket t) {
        int[] members = groupMembers.apply(t.npc);
        if (members == null) {
            return true;
        }
        Place p = t.place;
        for (int m : members) {
            NavPoint pos = occupancy.positionOf(m);
            if (pos != null && memberKeepsTurn(p, t.dir, pos)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a follower standing at {@code pos} keeps its leader's turn (in the place or just before it). */
    private static boolean memberKeepsTurn(Place p, int dir, NavPoint pos) {
        if (!pos.world().equals(p.world) || p.offLevel(pos)) {
            return false; // a follower on the flight above or below is not in the place (link B3)
        }
        double s = p.progress(dir, pos);
        return s >= -p.halfLength - MEMBER_BEFORE && s <= p.halfLength + 0.3 && p.lateral(pos) <= 3;
    }

    /** Ends an NPC's claim on any place (its order ended, it was replanned, it left navigation). */
    public void forget(int npc) {
        Ticket t = tickets.remove(npc);
        if (t != null) {
            detach(t);
        }
    }

    private void release(Ticket t) {
        tickets.remove(t.npc, t);
        detach(t);
    }

    private void detach(Ticket t) {
        t.place.waiting.remove(t);
        t.place.entered.remove(t);
    }

    /** Clears the free-width cache (a gate changed). */
    public void forgetWidths() {
        widths.clear();
    }

    // ==================== places ====================

    private Place placeFor(NavWorld world, String w, Run run, long tick) {
        Map<Long, Place> byCell = placeByCell.computeIfAbsent(w, k -> new HashMap<>());
        Place place = null;
        for (long c : run.cells()) {
            Place p = byCell.get(c);
            if (p != null && (place == null || p.id < place.id)) {
                place = p;
            }
        }
        if (place == null) {
            place = new Place(nextPlaceId++, w, run.ax(), run.az());
        }
        if (!place.cells.containsAll(run.cells())) {
            place.add(run.cells(), run.y());
            for (long c : run.cells()) {
                byCell.putIfAbsent(c, place);
            }
        }
        place.lastUsed = tick;
        return place;
    }

    /**
     * The first narrow run on the way within {@link #LOOK_AHEAD} blocks: its cells, its distance from the NPC and its
     * direction; null when the way has none.
     */
    Run findRun(NavWorld world, NavPoint at, List<NavPoint> way) {
        List<NavPoint> line = new ArrayList<>(way.size() + 1);
        line.add(at);
        for (NavPoint p : way) {
            if (p.world().equals(at.world())) {
                line.add(p);
            }
        }
        Set<Long> cells = null;
        double entry = 0;
        double lastNarrow = 0;
        double firstX = 0;
        double firstZ = 0;
        double lastX = 0;
        double lastZ = 0;
        double hx = 0;
        double hz = 0;
        int y = at.blockY();
        double walked = 0;
        double nextAt = 0;
        for (int i = 0; i + 1 < line.size() && nextAt <= LOOK_AHEAD; i++) {
            NavPoint a = line.get(i);
            NavPoint b = line.get(i + 1);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double len = Math.hypot(dx, dz);
            if (len < 1e-6) {
                continue;
            }
            for (; nextAt <= walked + len && nextAt <= LOOK_AHEAD; nextAt += SAMPLE) {
                double t = (nextAt - walked) / len;
                double x = a.x() + dx * t;
                double z = a.z() + dz * t;
                double fy = a.y() + (b.y() - a.y()) * t;
                Boolean narrow = narrow(world, at.world(), x, fy, z, dx, dz);
                if (narrow == null) {
                    continue;
                }
                if (narrow) {
                    int cellY = (int) Math.floor(fy + 1e-6);
                    if (cells == null) {
                        cells = new HashSet<>();
                        entry = nextAt;
                        firstX = x;
                        firstZ = z;
                        y = cellY;
                        hx = dx / len;
                        hz = dz / len;
                    }
                    cells.add(Occupancy.pack((int) Math.floor(x), cellY, (int) Math.floor(z)));
                    lastNarrow = nextAt;
                    lastX = x;
                    lastZ = z;
                } else if (cells != null && nextAt - lastNarrow > MERGE_GAP) {
                    return run(cells, y, entry, firstX, firstZ, lastX, lastZ, hx, hz);
                }
            }
            walked += len;
        }
        return cells == null ? null : run(cells, y, entry, firstX, firstZ, lastX, lastZ, hx, hz);
    }

    private static Run run(Set<Long> cells, int y, double entry, double fx, double fz, double lx, double lz,
                           double hx, double hz) {
        double dx = lx - fx;
        double dz = lz - fz;
        double len = Math.hypot(dx, dz);
        if (len >= 0.75) {
            return new Run(cells, y, entry, dx / len, dz / len);
        }
        return new Run(cells, y, entry, hx, hz); // a short place: the way's heading at its first point
    }

    /**
     * Whether the free width across {@code (hx, hz)} at a feet position is under {@link #NARROW_WIDTH}; null when the
     * position's own cell stops a body at both its height and a step up (between two waypoints of a stair).
     */
    private Boolean narrow(NavWorld world, String w, double x, double fy, double z, double hx, double hz) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int by = (int) Math.floor(fy + 1e-6);
        int dir = heading8(hx, hz);
        Map<Long, Float> cache = widths.computeIfAbsent(w, k -> new HashMap<>());
        long key = Occupancy.pack(bx, by, bz) * 8 + dir;
        Float cached = cache.get(key);
        if (cached == null) {
            double[] h = HEADINGS[dir];
            NavPoint feet = new NavPoint(w, bx + 0.5, by, bz + 0.5);
            double total = FreeWidth.at(world, feet, h[0], h[1]).total();
            reads++;
            if (total <= 0) {
                total = FreeWidth.at(world, new NavPoint(w, bx + 0.5, by + 1, bz + 0.5), h[0], h[1]).total();
                reads++;
            }
            cached = total <= 0 ? -1f : (float) total;
            cache.put(key, cached);
        }
        if (cached < 0) {
            return null;
        }
        return cached < NARROW_WIDTH - 0.05;
    }

    private static final double[][] HEADINGS = {
        {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /** One of eight headings (0 east, counter-clockwise in x/z); opposite headings measure the same width. */
    static int heading8(double hx, double hz) {
        double angle = Math.atan2(hz, hx);
        int k = (int) Math.round(angle / (Math.PI / 4));
        return ((k % 8) + 8) % 8;
    }

    // ==================== queue spots ====================

    /**
     * The queue spot {@code k} of direction {@code dir} at a place (feet): {@link #FIRST_GAP} plus k ×
     * {@link #QUEUE_SPACING} before it on its axis (further back while the way there is still narrow), {@link #SIDE_OFFSET} to the right of the way where the floor and the
     * body's room allow, else to the left, else half as far, else on the axis; the NPC must reach it in a straight
     * walkable line. Null when none of them works (it then waits where it stands).
     */
    NavPoint spot(NavWorld world, Place p, int dir, int k, NavPoint at) {
        return spot(world, p, dir, k, at, false);
    }

    /** As {@link #spot(NavWorld, Place, int, int, NavPoint)}; {@code edgeGuard}: never a cell next to a drop (M7). */
    NavPoint spot(NavWorld world, Place p, int dir, int k, NavPoint at, boolean edgeGuard) {
        double hx = p.ax * dir;
        double hz = p.az * dir;
        double back = p.halfLength + FIRST_GAP + k * QUEUE_SPACING;
        // a place seen only in part (a long passage) may go on past its known cells: further back until it is wide
        for (int i = 0; i < 2 * LOOK_AHEAD && Boolean.TRUE.equals(narrow(world, p.world, p.cx - hx * back, at.y(),
            p.cz - hz * back, hx, hz)); i++) {
            back += SAMPLE;
        }
        double bx = p.cx - hx * back;
        double bz = p.cz - hz * back;
        double rx = -hz;
        double rz = hx;
        int floorY = at.blockY() - 1;
        for (double off : new double[] {SIDE_OFFSET, -SIDE_OFFSET, SIDE_OFFSET / 2, -SIDE_OFFSET / 2, 0}) {
            double x = bx + rx * off;
            double z = bz + rz * off;
            NavPoint floor = HopPlanner.floorNear(world, new NavPoint(p.world, x, floorY, z), floorY);
            if (floor == null || Math.abs(floor.blockY() - floorY) > 1 || !NavGeometry.hasHeadroom(world, floor)) {
                continue;
            }
            NavPoint feet = new NavPoint(p.world, x, floor.blockY() + 1, z);
            if (p.cells.contains(Occupancy.pack(feet.blockX(), feet.blockY(), feet.blockZ()))) {
                continue;
            }
            if (edgeGuard && net.knightsandkings.knk.core.npc.nav.push.EdgeGuard.nextToDrop(world, feet)) {
                continue;
            }
            if (NavGeometry.straightWalkable(world, feet, feet) && NavGeometry.straightWalkable(world, at, feet)) {
                return feet;
            }
        }
        return null;
    }

    // ==================== counts ====================

    /** Waits counted (queues, behind others, yields' waits). */
    public long waits() {
        return waits;
    }

    /** Yields: an NPC that met an oncoming one in a narrow place and stepped back out. */
    public long yields() {
        return yields;
    }

    /**
     * Why a queue let an NPC go after {@link #MAX_WAIT_TICKS} (link B3, PandiO/knk-workspace#672): where it stood, the
     * place, and what held it: an NPC in the place ({@code holders}, with their 3D positions) or an earlier waiter.
     *
     * @param npc      the NPC let go
     * @param at       where it stood (feet)
     * @param place    the place's middle (feet height of its first cell)
     * @param minY     the lowest feet level of the place's cells
     * @param maxY     the highest feet level of the place's cells
     * @param dir      its direction along the place's axis
     * @param holders  the NPCs with their turn there now
     * @param earlier  NPCs waiting there that came earlier (either direction)
     */
    public record Release(int npc, NavPoint at, NavPoint place, int minY, int maxY, double halfLength, int dir,
                          List<Holder> holders, int earlier, List<Block> why) {
    }

    /**
     * What kept a waiting NPC from entering (diagnostics, link B3), counted per kind and NPC over its wait.
     *
     * @param kind      {@code oncoming} (a holder of the other direction), {@code earlier} (a waiter that came first)
     *                  or {@code spacing} (a holder of its own direction not yet a block into the place)
     * @param npc       that NPC
     * @param looks     how many of the waiter's looks it was the reason
     * @param at        where that NPC stood at the last of them
     * @param progress  its progress along its own direction then
     * @param levelGap  its feet above (+) or below (-) the place's floor level then
     */
    public record Block(String kind, int npc, int looks, NavPoint at, double progress, double levelGap) {
    }

    /** A wait of {@value #LONG_WAIT_TICKS} ticks or more that ended in a normal turn (diagnostics, link B3). */
    public record LongWait(int npc, long waitedTicks, NavPoint place, List<Block> why) {
    }

    static final long LONG_WAIT_TICKS = 200;
    private final List<LongWait> longWaits = new ArrayList<>();

    /** The first {@value #RELEASES_KEPT} waits of {@value #LONG_WAIT_TICKS} ticks or more since the counts were reset. */
    public List<LongWait> longWaits() {
        return List.copyOf(longWaits);
    }

    private static List<Block> why(Ticket t) {
        List<Block> out = new ArrayList<>(t.blocks.values());
        out.sort((a, b) -> Integer.compare(b.looks(), a.looks()));
        return out.size() > 3 ? List.copyOf(out.subList(0, 3)) : List.copyOf(out);
    }

    /**
     * An NPC with its turn at a place when the guard let a waiter go.
     *
     * @param progress   along its own direction from the place's middle (horizontal)
     * @param lateral    across the axis (horizontal)
     * @param heldTicks  how long it has held the turn
     * @param blocks     whether it alone stops the waiter (other direction, or its own direction not far enough in)
     * @param passed     whether it counts as past the place now
     * @param membersIn  its group's followers that keep its turn now (in the place or just before it)
     * @param memberAt   where the first of those stands (null: none)
     */
    public record Holder(int npc, NavPoint at, int dir, double progress, double lateral, long heldTicks,
                         boolean blocks, boolean passed, int membersIn, NavPoint memberAt) {
    }

    /** The first {@value #RELEASES_KEPT} releases since the counts were reset. */
    public List<Release> releases() {
        return List.copyOf(releases);
    }

    static final int RELEASES_KEPT = 20;
    private final List<Release> releases = new ArrayList<>();

    private void noteRelease(Ticket t, NavPoint at, long tick) {
        if (releases.size() >= RELEASES_KEPT) {
            return;
        }
        Place p = t.place;
        List<Holder> holders = new ArrayList<>();
        for (Ticket e : p.entered) {
            NavPoint pos = occupancy.positionOf(e.npc);
            if (pos == null) {
                continue;
            }
            boolean blocks = e.dir != t.dir || p.progress(e.dir, pos) < -p.halfLength + QUEUE_SPACING;
            int membersIn = 0;
            NavPoint memberAt = null;
            int[] members = groupMembers.apply(e.npc);
            for (int m : members == null ? new int[0] : members) {
                NavPoint mp = occupancy.positionOf(m);
                if (mp != null && memberKeepsTurn(p, e.dir, mp)) {
                    membersIn++;
                    memberAt = memberAt == null ? mp : memberAt;
                }
            }
            holders.add(new Holder(e.npc, pos, e.dir, p.progress(e.dir, pos), p.lateral(pos), tick - e.enteredTick,
                blocks, passed(p, e.dir, pos), membersIn, memberAt));
        }
        int earlier = 0;
        for (Ticket w : p.waiting) {
            if (w != t && earlier(w, t)) {
                earlier++;
            }
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (long c : p.cells) {
            minY = Math.min(minY, unpackY(c));
            maxY = Math.max(maxY, unpackY(c));
        }
        releases.add(new Release(t.npc, at, p.centre(), minY, maxY, p.halfLength, t.dir, holders, earlier, why(t)));
    }

    /** NPCs a queue let go after it did not move for {@link #MAX_WAIT_TICKS}. */
    public long overdue() {
        return overdue;
    }

    /** The longest queue at one place since the counts were reset. */
    public int longestQueue() {
        return longestQueue;
    }

    /** The longest an NPC waited for its turn, in ticks. */
    public long longestWaitTicks() {
        return longestWaitTicks;
    }

    /**
     * The longest wait divided by the NPCs that entered the place ahead of the waiting one (at least 1), in ticks: how
     * long the queue made an NPC wait per NPC it really had to let through.
     */
    public double worstWaitPerPass() {
        return worstWaitPerPass;
    }

    /** Free-width looks that read the world (cache misses). */
    public long reads() {
        return reads;
    }

    /** Places known now. */
    public int places() {
        Set<Place> all = new HashSet<>();
        for (Map<Long, Place> w : placeByCell.values()) {
            all.addAll(w.values());
        }
        return all.size();
    }

    /** NPCs waiting at a place now. */
    public int waitingNow() {
        int n = 0;
        for (Ticket t : tickets.values()) {
            if (!t.entered) {
                n++;
            }
        }
        return n;
    }

    /** Whether an NPC has its turn at a place now (diagnostics, tests). */
    public boolean hasTurn(int npc) {
        Ticket t = tickets.get(npc);
        return t != null && t.entered;
    }

    /** Whether an NPC waits for its turn at a place now (diagnostics, tests). */
    public boolean isWaiting(int npc) {
        Ticket t = tickets.get(npc);
        return t != null && !t.entered;
    }

    /** Resets the counts. */
    public void resetCounts() {
        waits = 0;
        yields = 0;
        overdue = 0;
        releases.clear();
        longWaits.clear();
        longestQueue = 0;
        longestWaitTicks = 0;
        worstWaitPerPass = 0;
        reads = 0;
    }

    /** The stats line: {@code crowd: waits N, yields N, longest queue N}. */
    public static String statsLine(long waits, long yields, int longestQueue) {
        return String.format(java.util.Locale.ROOT, "crowd: waits %d, yields %d, longest queue %d", waits, yields,
            longestQueue);
    }

    static int unpackX(long c) {
        return (int) (c >> 38) << 6 >> 6;
    }

    static int unpackY(long c) {
        return (int) (c & 0xFFF) << 20 >> 20;
    }

    static int unpackZ(long c) {
        return (int) ((c >> 12) & 0x3FFFFFF) << 6 >> 6;
    }
}
