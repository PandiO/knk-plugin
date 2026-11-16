package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 *   <li><b>Stretch handovers</b> of an NPC with an order of its own: how many, and how many handed it a stretch whose
 *   nearest waypoint is {@value #LEVEL_BLOCKS} blocks or more above or below its feet (a stretch on another flight of
 *   the stacked hairpins: decision 34's main cause).</li>
 * </ol>
 * Report only: nothing here fails a scenario; links B2 to B4 turn these numbers into checks.
 */
final class ClimbWatch {

    /** A drop of more than this many blocks is a fall (a step down a stair is 1). */
    static final double FALL_BLOCKS = 2.5;
    /** Ticks the highest feet position is remembered for the fall check. */
    static final int FALL_WINDOW_TICKS = 40;
    /** A new stretch whose nearest waypoint is this far above or below the feet starts on another level. */
    static final double LEVEL_BLOCKS = 2.5;

    private final NpcMovementScene house;
    private final NpcNavigationService service;
    private final Map<Integer, Body> bodies = new HashMap<>();
    private final List<String> falls = new ArrayList<>();
    private final List<String> skips = new ArrayList<>();
    private final List<String> crossLevel = new ArrayList<>();
    private final List<String> staleTurns = new ArrayList<>();
    private int handovers;
    private long tick;

    ClimbWatch(NpcMovementScene house, NpcNavigationService service) {
        this.house = house;
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
            falls.add(String.format(Locale.ROOT, "%s from %s to %s", mover.name(), house.realBlock(body.highest),
                house.realBlock(at)));
        }
        body.falling = fallen;
    }

    private void place(NpcMover mover, Body body, NavPoint at) {
        int p = house.climbPlace(at);
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
        NavPoint nearest = sent.get(0);
        for (NavPoint p : sent) {
            if (p.horizontalDistance(at) < nearest.horizontalDistance(at)) {
                nearest = p;
            }
        }
        // waypoints are floor or feet points: judge the nearer of the two readings
        double dy = Math.min(Math.abs(nearest.y() - at.y()), Math.abs(nearest.y() + 1 - at.y()));
        if (dy >= LEVEL_BLOCKS) {
            crossLevel.add(String.format(Locale.ROOT, "%s at %s got a stretch from %s (%.0f blocks %s)", mover.name(),
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
            staleTurns.add(String.format(Locale.ROOT, "%s held its turn from %s for %.1f s, now at %s", mover.name(),
                house.realBlock(body.turnAt), body.turnTicks / 20.0, house.realBlock(at)));
        }
        body.turnTicks = 0;
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
