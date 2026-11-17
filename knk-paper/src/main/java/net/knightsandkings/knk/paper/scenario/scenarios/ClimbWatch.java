package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.knightsandkings.knk.core.npc.nav.JumpDetector;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService;
import net.knightsandkings.knk.core.npc.nav.RouteFollower;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;

/**
 * What the climb through the whole stair house shows (link B1, PandiO/knk-workspace#670; the failures of the smoke test
 * of 2026-11-16, chain report decisions 32 and 34), per NPC, looked at every tick:
 * <ol>
 *   <li><b>Falls:</b> feet more than {@value #FALL_BLOCKS} blocks under the highest they were in the last
 *   {@value #FALL_WINDOW_TICKS} ticks, with where the fall started and where the body landed.</li>
 *   <li><b>Skipped places:</b> the body reaches a flight or landing of the climb ({@link NpcMovementScene#climbPlace})
 *   more than one place after the last one it stood on, or goes back to an earlier one (after a fall).</li>
 *   <li><b>Stretch handovers</b> of an NPC with an order of its own: how many, and how many sent it onto another
 *   level: after the handover, the waypoint it walks to next is {@value #LEVEL_BLOCKS} blocks or more above or below
 *   its feet (a stretch on another flight of the stacked hairpins: decision 34's main cause). Link B2 appends the
 *   stretch to the waypoints not yet walked, so the next waypoint is that list's first
 *   ({@link NpcNavigationService#trailOf}). B1's own measure, the new stretch's waypoint nearest the feet
 *   horizontally, is still counted ({@link #stretchOffLevel()}, report only): with stretches handed out by 3D progress
 *   a stretch going up the next flight is a flight above the NPC before it gets there.</li>
 *   <li><b>Turns dropped by the 30 s guard</b>, and of those the ones whose holder fell while it held the turn.</li>
 *   <li><b>Jumps</b> (link B4, PandiO/knk-workspace#673): a body leaving the ground at a jump's upward speed
 *   ({@link JumpDetector}), not a step up a half-block stair; and of those the jumps that took off on a place of the
 *   climb, the flights and their entries ({@link #jumpsOnClimb()}).</li>
 * </ol>
 * Since link B2 (PandiO/knk-workspace#671) the climb and the replay fail on falls from a flight or landing, on
 * handovers onto another level and on turns dropped after a fall ({@link #failures}).
 */
final class ClimbWatch {

    /** A drop of more than this many blocks is a fall (a step down a stair is 1). */
    static final double FALL_BLOCKS = 2.5;
    /** Ticks the highest feet position is remembered for the fall check. */
    static final int FALL_WINDOW_TICKS = 40;
    /** A new stretch whose nearest waypoint is this far above or below the feet starts on another level. */
    static final double LEVEL_BLOCKS = 2.5;

    /** Where a body is in the dev world's coordinates: the scene's frame or a snapshot replay's. */
    interface Frame {
        /** "x y z" of a world position's block in real coordinates. */
        String realBlock(NavPoint at);

        /** The place of the climb a body's feet are on, or -1. */
        int place(NavPoint feet);
    }

    private final Frame house;
    private final NpcNavigationService service;
    private final Map<Integer, Body> bodies = new HashMap<>();
    private final List<String> falls = new ArrayList<>();
    private final List<String> skips = new ArrayList<>();
    private final List<String> crossLevel = new ArrayList<>();
    private final List<String> stretchOffLevel = new ArrayList<>();
    private final List<String> fallsFromClimb = new ArrayList<>();
    private final List<String> fallenTurns = new ArrayList<>();
    private final List<String> staleTurns = new ArrayList<>();
    private final List<String> jumps = new ArrayList<>();
    private final List<String> jumpsOnClimb = new ArrayList<>();
    private final List<String> stairHops = new ArrayList<>();
    /** Whether a take-off position stands on a stair's step under its upper half (null: never). */
    private java.util.function.Predicate<NavPoint> onStairStep;
    private int handovers;
    private long tick;

    ClimbWatch(NpcMovementScene house, NpcNavigationService service) {
        this(new Frame() {
            @Override
            public String realBlock(NavPoint at) {
                return house.realBlock(at);
            }

            @Override
            public int place(NavPoint feet) {
                return house.climbPlace(feet);
            }
        }, service);
    }

    /** A watch in a snapshot replay's frame: real = world - the scene's origin + the replay's frame. */
    ClimbWatch(net.knightsandkings.knk.paper.scenario.ScenarioScene scene,
               net.knightsandkings.knk.paper.scenario.SnapshotReplay replay, NpcNavigationService service) {
        this(new Frame() {
            private int[] real(NavPoint at) {
                int[] f = replay.frame();
                return new int[] {at.blockX() - scene.worldX(0) + f[0],
                    (int) Math.floor(at.y() - scene.worldY(0) + 0.01) + f[1], at.blockZ() - scene.worldZ(0) + f[2]};
            }

            @Override
            public String realBlock(NavPoint at) {
                int[] r = real(at);
                return r[0] + " " + r[1] + " " + r[2];
            }

            @Override
            public int place(NavPoint feet) {
                int[] r = real(feet);
                return NpcMovementScene.climbPlaceAt(r[0], r[1], r[2]);
            }
        }, service);
    }

    ClimbWatch(Frame frame, NpcNavigationService service) {
        this.house = frame;
        this.service = service;
    }

    private static final class Body {
        final Deque<double[]> recent = new ArrayDeque<>(); // tick, y
        NavPoint highest;
        int place = -1;
        int furthest = -1;
        boolean falling;
        List<NavPoint> sent;
        long turnTicks;
        NavPoint turnAt;
        boolean fellInTurn;
        final JumpDetector jumps = new JumpDetector();
        NavPoint prevAt;
        final Map<List<NavPoint>, Boolean> seen = new IdentityHashMap<>();
    }

    /** Looks at every mover once (call every tick). */
    void look(List<NpcMover> movers) {
        tick++;
        for (NpcMover mover : movers) {
            NavPoint at = mover.position().orElse(null);
            if (at == null) {
                continue;
            }
            Body body = bodies.computeIfAbsent(mover.id(), id -> new Body());
            fall(mover, body, at);
            jump(mover, body, at);
            place(mover, body, at);
            handover(mover, body, at);
            turn(mover, body, at);
        }
    }

    private void fall(NpcMover mover, Body body, NavPoint at) {
        body.recent.addLast(new double[] {tick, at.y()});
        while (!body.recent.isEmpty() && body.recent.peekFirst()[0] < tick - FALL_WINDOW_TICKS) {
            body.recent.removeFirst();
        }
        double top = Double.NEGATIVE_INFINITY;
        for (double[] r : body.recent) {
            top = Math.max(top, r[1]);
        }
        if (body.highest == null || at.y() >= body.highest.y() || body.highest.y() > top + 1e-6) {
            body.highest = at;
        }
        boolean fallen = top - at.y() > FALL_BLOCKS;
        if (fallen && !body.falling && mover.isSpawned()) {
            String line = String.format(Locale.ROOT, "%s from %s to %s", mover.name(), house.realBlock(body.highest),
                house.realBlock(at));
            falls.add(line);
            if (house.place(body.highest) >= 0) {
                fallsFromClimb.add(line); // from a flight, a landing, the hall or the street of the climb
            }
            body.fellInTurn |= body.turnTicks > 0;
        }
        body.falling = fallen;
    }

    private void jump(NpcMover mover, Body body, NavPoint at) {
        NavPoint from = body.prevAt;
        body.prevAt = at;
        if (!body.jumps.sample(at.y(), mover.onGround(), mover.verticalSpeed())) {
            return;
        }
        int p = house.place(at);
        String line = String.format(Locale.ROOT, "%s at %s (%s)", mover.name(), house.realBlock(at),
            NpcMovementScene.climbPlaceName(p));
        jumps.add(line);
        if (from != null && onStairStep != null && onStairStep.test(from)) {
            // the engine's own move control hops whenever the block at the feet has a collision top above them: on
            // a stair's step, its upper half; no waypoint height takes that away (link B4)
            stairHops.add(line);
            return;
        }
        if (p >= 0) { // from a flight, a landing, the corridor or the hall: the flights and their entries
            jumpsOnClimb.add(line);
        }
    }

    /**
     * Tells the engine's stair hops apart (link B4): a jump that takes off with the feet on a stair's step, inside the
     * stair block below its upper half. Minecraft's move control (Citizens' too) jumps there by itself: the block at
     * the feet has a collision top above them.
     */
    ClimbWatch stairHopsIn(org.bukkit.World world) {
        onStairStep = feet -> {
            org.bukkit.block.Block block = world.getBlockAt(feet.blockX(), feet.blockY(), feet.blockZ());
            if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Stairs)) {
                return false;
            }
            double top = 0;
            for (org.bukkit.util.BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                top = Math.max(top, box.getMaxY());
            }
            return feet.y() < block.getY() + top - 1e-3;
        };
        return this;
    }

    private void place(NpcMover mover, Body body, NavPoint at) {
        int p = house.place(at);
        if (p < 0 || p == body.place) {
            return;
        }
        if (body.place >= 0 && p > body.place + 1) {
            skips.add(String.format(Locale.ROOT, "%s from %s to %s at %s", mover.name(),
                NpcMovementScene.climbPlaceName(body.place), NpcMovementScene.climbPlaceName(p), house.realBlock(at)));
        } else if (body.place >= 0 && (p < body.place - 1 || (p < body.place && body.falling))) {
            skips.add(String.format(Locale.ROOT, "%s back from %s to %s at %s", mover.name(),
                NpcMovementScene.climbPlaceName(body.place), NpcMovementScene.climbPlaceName(p), house.realBlock(at)));
        }
        body.place = p;
        body.furthest = Math.max(body.furthest, p);
    }

    private void handover(NpcMover mover, Body body, NavPoint at) {
        RouteFollower follower = service.followerOf(mover.id());
        List<NavPoint> sent = follower == null ? null : follower.lastSent();
        if (sent == null || sent.isEmpty() || sent == body.sent || body.seen.containsKey(sent)) {
            return;
        }
        boolean first = body.sent == null;
        body.sent = sent;
        body.seen.put(sent, Boolean.TRUE);
        if (first) {
            return; // the first stretch of an order is no handover
        }
        handovers++;
        List<NavPoint> trail = service.trailOf(mover.id());
        if (trail != null && !trail.isEmpty()) {
            // the waypoint it walks to next: the first of the list it was given (the rest of the old stretch, then
            // the new one; or the bridge from its feet)
            NavPoint next = trail.get(0);
            double up = Math.min(Math.abs(next.y() - at.y()), Math.abs(next.y() + 1 - at.y()));
            if (up >= LEVEL_BLOCKS) {
                crossLevel.add(String.format(Locale.ROOT, "%s at %s was sent to %s (%.0f blocks %s)", mover.name(),
                    house.realBlock(at), house.realBlock(next), up, next.y() > at.y() ? "up" : "down"));
            }
        }
        NavPoint nearest = sent.get(0);
        for (NavPoint p : sent) {
            if (p.horizontalDistance(at) < nearest.horizontalDistance(at)) {
                nearest = p;
            }
        }
        // waypoints are floor or feet points: judge the nearer of the two readings
        double dy = Math.min(Math.abs(nearest.y() - at.y()), Math.abs(nearest.y() + 1 - at.y()));
        if (dy >= LEVEL_BLOCKS) {
            stretchOffLevel.add(String.format(Locale.ROOT, "%s at %s got a stretch from %s (%.0f blocks %s)", mover.name(),
                house.realBlock(at), house.realBlock(sent.get(0)), dy, nearest.y() > at.y() ? "up" : "down"));
        }
    }

    /**
     * A turn at a narrow place held for the crowd's whole 30 s guard ({@code CrowdControl.STALE_TICKS}) and then
     * dropped: the NPC never passed the place (it fell, or stands stuck in it), and the queue behind it was released
     * by the guard (decision 34.2.1).
     */
    private void turn(NpcMover mover, Body body, NavPoint at) {
        if (service.crowd().hasTurn(mover.id())) {
            if (body.turnTicks == 0) {
                body.turnAt = at;
            }
            body.turnTicks++;
            return;
        }
        if (body.turnTicks >= net.knightsandkings.knk.core.npc.nav.crowd.CrowdControl.STALE_TICKS - 20) {
            String line = String.format(Locale.ROOT, "%s held its turn from %s for %.1f s, now at %s%s", mover.name(),
                house.realBlock(body.turnAt), body.turnTicks / 20.0, house.realBlock(at),
                body.fellInTurn ? " (it fell)" : "");
            staleTurns.add(line);
            if (body.fellInTurn) {
                fallenTurns.add(line);
            }
        }
        body.turnTicks = 0;
        body.fellInTurn = false;
    }

    /**
     * The queue releases by the crowd's 30 s guard (link B3, PandiO/knk-workspace#672) in real coordinates: the NPC let
     * go and where it stood, the place (its middle and its cells' feet levels), and each holder of a turn there with
     * its feet, its height above the place's first cell, its progress and its distance across the place's axis.
     */
    List<String> releases(java.util.function.IntFunction<String> names) {
        List<String> out = new ArrayList<>();
        for (net.knightsandkings.knk.core.npc.nav.crowd.CrowdControl.Release r : service.crowd().releases()) {
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.ROOT, "%s at %s (y %+.2f) let go at the place %s (feet levels %+d..%+d, half "
                    + "length %.1f, dir %+d), %d earlier waiting", names.apply(r.npc()), house.realBlock(r.at()),
                r.at().y() - r.place().y(), house.realBlock(r.place()), r.minY() - (int) r.place().y(),
                r.maxY() - (int) r.place().y(), r.halfLength(), r.dir(), r.earlier()));
            if (r.holders().isEmpty()) {
                b.append(", nobody holds a turn");
            }
            for (var h : r.holders()) {
                b.append(String.format(Locale.ROOT, "; holder %s at %s (y %+.2f, progress %+.1f, lateral %.1f, dir %+d, "
                        + "held %.1f s%s%s%s)", names.apply(h.npc()), house.realBlock(h.at()), h.at().y() - r.place().y(),
                    h.progress(), h.lateral(), h.dir(), h.heldTicks() / 20.0, h.blocks() ? ", blocks" : "",
                    h.passed() ? ", passed" : "",
                    h.membersIn() == 0 ? "" : String.format(Locale.ROOT, ", %d followers keep it, first at %s (y %+.2f)",
                        h.membersIn(), house.realBlock(h.memberAt()), h.memberAt().y() - r.place().y())));
            }
            out.add(b.toString());
        }
        return out;
    }

    /** "x y z" of a world position's block in real coordinates. */
    String realBlock(NavPoint at) {
        return house.realBlock(at);
    }

    /** Turns dropped by the crowd's 30 s guard ({@link #turn}). */
    List<String> staleTurns() {
        return staleTurns;
    }

    List<String> falls() {
        return falls;
    }

    List<String> skips() {
        return skips;
    }

    int handovers() {
        return handovers;
    }

    List<String> crossLevel() {
        return crossLevel;
    }

    /** B1's measure: new stretches whose waypoint nearest the feet horizontally is on another level (report only). */
    List<String> stretchOffLevel() {
        return stretchOffLevel;
    }

    /** Falls that started on a place of the climb (a flight, a landing, the hall or the street). */
    List<String> fallsFromClimb() {
        return fallsFromClimb;
    }

    /** Real jumps (by upward speed, {@link JumpDetector}), anywhere. */
    List<String> jumps() {
        return jumps;
    }

    /** The engine's own hops off a stair's step ({@link #stairHopsIn}); not counted in {@link #jumpsOnClimb()}. */
    List<String> stairHops() {
        return stairHops;
    }

    /**
     * Real jumps that took off on a place of the climb (a flight or the landing, hall or street at its entry), other
     * than the engine's stair hops: jumps toward a waypoint above the feet.
     */
    List<String> jumpsOnClimb() {
        return jumpsOnClimb;
    }

    /** Turns dropped by the 30 s guard whose holder fell while it held the turn. */
    List<String> fallenTurns() {
        return fallenTurns;
    }

    /**
     * Link B2's checks (PandiO/knk-workspace#671): no fall from a flight or landing, no handover onto another level,
     * no turn released by the 30 s guard because its holder fell. Empty when all hold. A guard release whose holder
     * did not fall is B3's (crowd turns in 3D) and stays report only.
     */
    List<String> failures() {
        List<String> out = new ArrayList<>();
        if (!fallsFromClimb.isEmpty()) {
            out.add("falls from the climb " + first(fallsFromClimb, 3));
        }
        if (!crossLevel.isEmpty()) {
            out.add("handovers onto another level " + first(crossLevel, 3));
        }
        if (!fallenTurns.isEmpty()) {
            out.add("turns dropped by the 30 s guard after a fall " + first(fallenTurns, 3));
        }
        return out;
    }

    /** The furthest place each mover reached, counted: "top hall 1, flight 3 (x 1409, north) 19". */
    String furthest(List<NpcMover> movers) {
        Map<Integer, Integer> counts = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        for (NpcMover mover : movers) {
            Body body = bodies.get(mover.id());
            counts.merge(body == null ? -1 : body.furthest, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((place, n) -> parts.add(NpcMovementScene.climbPlaceName(place) + " " + n));
        return String.join(", ", parts);
    }

    /** How many of the movers have reached {@code place} or a later one. */
    int reached(List<NpcMover> movers, int place) {
        int n = 0;
        for (NpcMover mover : movers) {
            Body body = bodies.get(mover.id());
            n += body != null && body.furthest >= place ? 1 : 0;
        }
        return n;
    }

    /** At most {@code n} entries, then how many more. */
    static String first(List<String> lines, int n) {
        if (lines.isEmpty()) {
            return "none";
        }
        String shown = String.join("; ", lines.subList(0, Math.min(n, lines.size())));
        return lines.size() + ": " + shown + (lines.size() > n ? "; +" + (lines.size() - n) + " more" : "");
    }
}
