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
 * waits there. A queue never holds an NPC longer than {@link #MAX_WAIT_TICKS} (a deadlock guard).
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
    /** A queue never holds an NPC longer than this (30 s): it then goes (logged). */
    public static final long MAX_WAIT_TICKS = 600;
    /** A turn held longer than this (30 s) is dropped (the NPC is stuck in the place; recovery has it). */
    public static final long STALE_TICKS = 600;
    /** The free-width cache is cleared this often. */
    public static final long WIDTH_CACHE_TICKS = 200;
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
     * @param overdue    a GO because the NPC waited {@link #MAX_WAIT_TICKS}
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
        boolean yielded;

        Ticket(int npc, Place place, int dir, long arrived, long seq) {
            this.npc = npc;
            this.place = place;
            this.dir = dir;
            this.arrived = arrived;
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
                if (e.dir != t.dir && (oncoming == null || e.enteredSeq < oncoming.enteredSeq)) {
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
            return new Decision(Kind.YIELD, spot(world, p, t.dir, k, at), k, p, false);
        }
        if (mayEnter(t)) {
            enter(t, tick);
            return Decision.GO;
        }
        if (tick - t.arrived >= MAX_WAIT_TICKS) {
            overdue++;
            enter(t, tick);
            return new Decision(Kind.GO, null, 0, p, true);
        }
        int k = queueIndex(t);
        return new Decision(Kind.WAIT, spot(world, p, t.dir, k, at), k, p, false);
    }

    /** The turn rules of the class comment: nobody of the other direction in it, first come, spacing. */
    private boolean mayEnter(Ticket t) {
        Place p = t.place;
        for (Ticket e : p.entered) {
            if (e.dir != t.dir) {
                return false;
            }
        }
        for (Ticket w : p.waiting) {
            if (w != t && earlier(w, t)) {
                return false;
            }
        }
        for (Ticket e : p.entered) {
            NavPoint pos = occupancy.positionOf(e.npc);
            if (pos != null && p.progress(e.dir, pos) < -p.halfLength + QUEUE_SPACING) {
                return false;
            }
        }
        return true;
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
        }
        if (t.arrived < tick) {
            long waited = tick - t.arrived;
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

    private boolean passed(Place p, int dir, NavPoint at) {
        return !at.world().equals(p.world) || p.progress(dir, at) > p.halfLength + PASSED
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
            if (pos == null || !pos.world().equals(p.world)) {
                continue;
            }
            double s = p.progress(t.dir, pos);
            if (s >= -p.halfLength - MEMBER_BEFORE && s <= p.halfLength + 0.3 && p.lateral(pos) <= 3) {
                return false;
            }
        }
        return true;
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

    /** NPCs a queue let go after {@link #MAX_WAIT_TICKS}. */
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

    static int unpackZ(long c) {
        return (int) ((c >> 12) & 0x3FFFFFF) << 6 >> 6;
    }
}
