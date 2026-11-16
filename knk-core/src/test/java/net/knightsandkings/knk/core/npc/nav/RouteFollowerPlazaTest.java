package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.RouteFollower.Kind;
import net.knightsandkings.knk.core.npc.nav.RouteFollower.Stretch;
import net.knightsandkings.knk.core.npc.nav.corridor.CorridorShaper;

/**
 * Plaza lines in the route follower (implementation plan 4c.3 decision 6, link M5): the junction is placed on the line,
 * a stretch that would end on the plaza holds the whole way across it, the plaza line replaces the V, and with plaza
 * lines only the rest of the stretch is today's.
 */
class RouteFollowerPlazaTest {

    private final FakeNavWorld world = new FakeNavWorld();
    private long tick;
    private final CorridorShaper shaper = new CorridorShaper(() -> tick, null);

    /** An open square: the V from (0, 30) east to the junction (20, 30), then north to (20, 0). */
    private RouteFollower vRoute() {
        return new RouteFollower(List.of(floor(0.5, 64, 30.5), floor(20.5, 64, 30.5), floor(20.5, 64, 0.5)),
            List.of(new RoadPlanner.PlazaCrossing(floor(20.6, 64, 30.4), 8)));
    }

    private static List<NavPoint> all(RouteFollower f, RouteFollower.Steering steering, FakeNavWorld world) {
        List<NavPoint> out = new ArrayList<>();
        while (f.hasMore()) {
            Stretch s = f.next(world, 24, 64, 0, steering);
            if (s.kind() != Kind.WAYPOINTS) {
                break;
            }
            out.addAll(s.waypoints());
        }
        return out;
    }

    private static double closestTo(List<NavPoint> p, double x, double z) {
        double best = Double.MAX_VALUE;
        for (int k = 1; k < p.size(); k++) {
            NavPoint a = p.get(k - 1);
            NavPoint b = p.get(k);
            for (int i = 0; i <= 50; i++) {
                double t = i / 50.0;
                best = Math.min(best, Math.hypot(a.x() + (b.x() - a.x()) * t - x, a.z() + (b.z() - a.z()) * t - z));
            }
        }
        return best;
    }

    @Test
    void theJunctionIsPlacedOnTheLine() {
        RouteFollower f = vRoute();
        assertEquals(1, f.plazas().size());
        NavPoint at = f.line().get(f.plazas().get(0).index());
        assertEquals(20.5, at.x(), 1e-9);
        assertEquals(30.5, at.z(), 1e-9);
        assertEquals(at, f.plazas().get(0).crossing().centre());
        // a junction off the line is left out
        assertEquals(0, new RouteFollower(List.of(floor(0.5, 64, 30.5), floor(20.5, 64, 30.5)),
            List.of(new RoadPlanner.PlazaCrossing(floor(10.5, 64, 20.5), 0))).plazas().size());
    }

    @Test
    void aStretchThatWouldEndOnThePlazaHoldsTheWayAcross() {
        RouteFollower f = vRoute();
        int junction = f.plazas().get(0).index();
        // a stretch from the start ending a little before the junction is carried past the plaza's far edge
        int end = f.endHoldingPlazas(0, junction - 2, 8);
        assertTrue(end > junction + 8 / RouteFollower.STEP, "held to past the exit: " + end + " vs " + junction);
        assertEquals(junction - 20, f.endHoldingPlazas(0, junction - 20, 8), "far before it: unchanged");
    }

    @Test
    void junctionsCloseTogetherDoNotChainIntoALongStretch() {
        // a street grid: a junction every 6 blocks along 200 blocks
        List<RoadPlanner.PlazaCrossing> junctions = new ArrayList<>();
        for (int x = 6; x < 200; x += 6) {
            junctions.add(new RoadPlanner.PlazaCrossing(floor(x + 0.5, 64, 0.5), 0));
        }
        RouteFollower f = new RouteFollower(List.of(floor(0.5, 64, 0.5), floor(200.5, 64, 0.5)), junctions);
        Stretch s = f.next(world, 24, 64, 0, new RouteFollower.Steering(shaper,
            CorridorShaper.Options.plazasOnly(4, 0.8, 16, 0.8)));
        assertEquals(Kind.WAYPOINTS, s.kind());
        assertTrue(f.nextIndex() <= 2 * 16, "at most two stretches' worth: " + f.nextIndex());
    }

    @Test
    void thePlazaLineReplacesTheV() {
        RouteFollower.Steering steering = new RouteFollower.Steering(shaper,
            new CorridorShaper.Options(4, 0.8).withPlazas(16, 0.8));
        List<NavPoint> walked = all(vRoute(), steering, world);

        assertTrue(closestTo(walked, 20.5, 30.5) > 4, "not through the junction: " + walked);
        assertEquals(1, shaper.plazaLines());
    }

    @Test
    void plazaLinesOnlyKeepTheRestOfTheStretchAtBlockCentres() {
        RouteFollower.Steering steering = new RouteFollower.Steering(shaper,
            CorridorShaper.Options.plazasOnly(4, 0.8, 16, 0.8));
        List<NavPoint> walked = all(vRoute(), steering, world);
        List<NavPoint> today = all(vRoute(), null, world);

        assertTrue(closestTo(walked, 20.5, 30.5) > 4, "not through the junction: " + walked);
        for (NavPoint p : today) {
            if (Math.hypot(p.x() - 20.5, p.z() - 30.5) > 9) {
                assertTrue(walked.contains(p), "today's waypoint off the plaza: " + p);
            }
        }
        // a route without a plaza is not shaped at all
        all(new RouteFollower(List.of(floor(0.5, 64, 30.5), floor(30.5, 64, 30.5))), steering, world);
        assertEquals(1, shaper.shaped());
    }
}
