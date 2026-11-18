package net.knightsandkings.knk.core.navigation;

import net.knightsandkings.knk.core.navigation.NavigationEffect.ComputeRouteEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.GuidanceEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RerouteStartedEffect;
import net.knightsandkings.knk.core.roads.route.AStarRouter;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.ManeuverBuilder;
import net.knightsandkings.knk.core.roads.route.NetworkFixtureAccess;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.RouteResult;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Winding back (off-road phase 6.2, #711, DESIGN § 7.5, O15): progress and cursor in {@link NavigationSession}. With
 * the flag off (no {@link CursorParameters}) the session is today's: the transcript test pins it (A16).
 */
class NavigationSessionCursorTest {

    private final RoadNetworkSnapshot town = NetworkFixtureAccess.town();
    private final AStarRouter router = new AStarRouter(town);
    private final ManeuverBuilder maneuvers = new ManeuverBuilder(town);
    private final SessionParameters params = SessionParameters.defaults();

    /** A → E along Keepstreet (z = 0, floor 64): 200 blocks, no maneuver. */
    private RouteResult routeAtoE() {
        return router.routeOrExplain(RouteRequest.of(SnapPoint.atNode(town, NetworkFixtureAccess.A),
            SnapPoint.atNode(town, NetworkFixtureAccess.E), AccessPolicy.ALL_OPEN, RouterParameters.defaults()));
    }

    /** A → C via B: east 100 along Keepstreet, "Turn right onto Merchantstreet" at B, then 100 along z. */
    private RouteResult routeAtoC() {
        return router.route(new RouteRequest(SnapPoint.atNode(town, NetworkFixtureAccess.A),
            List.of(SnapPoint.atNode(town, NetworkFixtureAccess.C)), AccessPolicy.ALL_OPEN,
            Map.of(net.knightsandkings.knk.core.domain.roads.RoadClass.PATH, 1.5)));
    }

    private NavigationSession guided(CursorParameters cursor, RouteResult route) {
        NavigationSession s = new NavigationSession(params, maneuvers::build, 0, cursor);
        s.start();
        s.onRouteResult(route, 0);
        return s;
    }

    private static GuidanceEffect guidance(List<NavigationEffect> effects) {
        return effects.stream().filter(GuidanceEffect.class::isInstance).map(GuidanceEffect.class::cast)
            .findFirst().orElseThrow();
    }

    /** One line per tick: along, off-route, remaining, ETA, percentage, next maneuver, metres to it, other effects. */
    private static String line(List<NavigationEffect> effects) {
        StringBuilder sb = new StringBuilder();
        for (NavigationEffect e : effects) {
            if (e instanceof GuidanceEffect g) {
                sb.append(String.format(java.util.Locale.ROOT, "G %.2f %.2f %.2f %.2f %.3f %s %.2f; ", g.along(),
                    g.offRoute(), g.remainingBlocks(), g.etaSeconds(), g.progress(),
                    g.nextManeuver().map(m -> m.text()).orElse("-"), g.metersToNext()));
            } else {
                sb.append(e.getClass().getSimpleName()).append("; ");
            }
        }
        return sb.toString();
    }

    /** Forwards to x = 80, back to x = 50, off the road for 50 ticks, back on and on to B and round the turn. */
    private static List<double[]> script() {
        List<double[]> feet = new ArrayList<>();
        for (int x = 0; x <= 80; x += 2) feet.add(new double[] {x, 65, 0.5});
        for (int x = 80; x >= 50; x -= 2) feet.add(new double[] {x, 65, -0.5});
        for (int i = 0; i < 50; i++) feet.add(new double[] {50, 65, 20});
        for (int x = 50; x <= 100; x += 2) feet.add(new double[] {x, 65, 0});
        for (int z = 0; z <= 30; z += 2) feet.add(new double[] {100, 65, z});
        return feet;
    }

    private static List<String> transcript(NavigationSession s, List<double[]> feet) {
        List<String> out = new ArrayList<>();
        long tick = 1;
        for (double[] f : feet) {
            out.add(line(s.tick(f[0], f[1], f[2], tick++)));
        }
        return out;
    }

    @Test
    void withTheFlagOffTheSessionIsTodays() {
        // A16: the three-argument constructor and a null CursorParameters give one transcript, and it is today's:
        // walking back leaves "along" at the farthest point (minus nothing: max), the look-back keeps the projection
        NavigationSession today = new NavigationSession(params, maneuvers::build, 0);
        today.start();
        today.onRouteResult(routeAtoC(), 0);
        List<String> plain = transcript(today, script());
        NavigationSession off = guided(null, routeAtoC());
        assertFalse(off.windsBack());
        List<String> nulled = transcript(off, script());
        assertEquals(plain, nulled);
        // today's numbers at a few ticks: the far point (x = 80) is held while the player walks back to x = 50
        assertTrue(plain.get(40).startsWith("G 80.00 0.50 120.00 21.43 0.400 Turn right onto Merchantstreet 20.00"),
            plain.get(40));
        assertTrue(plain.get(56).startsWith("G 80.00 0.50 120.00 21.43 0.400 Turn right onto Merchantstreet 20.00"),
            "walked back to x = 50: still guided from 80 - " + plain.get(56));
        // off the road from index 72: the re-route after reroute-after-ticks (40), the trail still from 80
        assertEquals("RerouteStartedEffect; ComputeRouteEffect; G 80.00 20.00 120.00 21.43 0.400 Turn right onto "
            + "Merchantstreet 20.00; ", plain.get(96));
        assertEquals(1, plain.stream().filter(l -> l.contains("RerouteStartedEffect")).count());
        // back on the road at x = 50: nothing moves until the player passes 80 again
        assertTrue(plain.get(122).startsWith("G 80.00 0.00 120.00"), plain.get(122));
        assertTrue(plain.get(125).startsWith("G 86.00 0.00 114.00 20.36 0.430"), plain.get(125));
        assertEquals("G 130.00 0.00 70.00 12.50 0.650 - 0.00; ", plain.get(plain.size() - 1));
                assertEquals(off.along(), off.cursor(), 1e-12, "cursor() is along() with the flag off");
    }

    @Test
    void walkingBackAlongTheRoadWindsTheCursorBackAndKeepsTheProgress() {
        // A18: 20 blocks back along the road - the trail and the HUD distance from the player's place, no off-route
        NavigationSession s = guided(CursorParameters.defaults(), routeAtoE());
        assertTrue(s.windsBack());
        long tick = 1;
        for (int x = 0; x <= 100; x += 2) {
            s.tick(x, 65, 0.5, tick++);
        }
        assertEquals(100, s.cursor(), 1e-9);
        List<NavigationEffect> last = List.of();
        for (int x = 100; x >= 80; x -= 1) {
            last = s.tick(x, 65, 0.5, tick++);
            assertTrue(last.stream().noneMatch(ComputeRouteEffect.class::isInstance), "no re-route at x = " + x);
        }
        GuidanceEffect g = guidance(last);
        assertEquals(80, g.along(), 1e-9, "the trail is drawn from the cursor");
        assertEquals(0.5, g.offRoute(), 1e-9);
        assertEquals(120, g.remainingBlocks(), 1e-9, "the HUD distance from the cursor");
        assertEquals(0.5, g.progress(), 1e-9, "the percentage from the progress, which never decreases");
        assertEquals(100 / params.sprintSpeed(), g.etaSeconds(), 1e-9, "the ETA from the progress");
        assertEquals(86, g.aheadPoint()[0], 1e-9, "the arrow 6 blocks beyond the cursor");
        assertEquals(100, s.along(), 1e-9);
        assertEquals(80, s.cursor(), 1e-9);
        assertEquals(120, s.remainingBlocks(), 1e-9);
    }

    @Test
    void theManeuverAheadComesFromTheCursor() {
        // past the turn at B (along 100), then back before it: the turn is ahead again (the runtime does not announce
        // it twice: its announced index only grows)
        NavigationSession s = guided(CursorParameters.defaults(), routeAtoC());
        long tick = 1;
        for (int x = 0; x <= 100; x += 2) s.tick(x, 65, 0, tick++);
        for (int z = 1; z <= 10; z++) s.tick(100, 65, z, tick++);
        GuidanceEffect past = guidance(s.tick(100, 65, 10, tick++));
        assertTrue(past.nextManeuver().isEmpty());
        for (int z = 10; z >= 0; z--) s.tick(100, 65, z, tick++);
        for (int x = 99; x >= 90; x--) s.tick(x, 65, 0, tick++);
        GuidanceEffect back = guidance(s.tick(90, 65, 0, tick++));
        assertEquals(90, back.along(), 1e-9);
        assertEquals("Turn right onto Merchantstreet", back.nextManeuver().orElseThrow().text());
        assertEquals(10, back.metersToNext(), 1e-9);
        assertEquals(110, s.along(), 1e-9, "the progress stays at the farthest point");
    }

    @Test
    void offTheRoadTheCursorStaysAndTheRerouteIsAsToday() {
        SessionParameters p = params.withReroute(8, 3, 10);
        NavigationSession s = new NavigationSession(p, maneuvers::build, 0, CursorParameters.defaults());
        s.start();
        s.onRouteResult(routeAtoE(), 0);
        long tick = 1;
        for (int x = 0; x <= 40; x += 2) s.tick(x, 65, 0, tick++);
        s.tick(40, 65, 20, tick++);
        s.tick(40, 65, 20, tick++);
        List<NavigationEffect> third = s.tick(40, 65, 20, tick++);
        assertTrue(third.stream().anyMatch(RerouteStartedEffect.class::isInstance), "off route for three ticks");
        assertEquals(20, guidance(third).offRoute(), 1e-9);
        assertEquals(40, s.cursor(), 1e-9, "the cursor stays where the player left the road");
    }

    @Test
    void anAdoptedRouteStartsTheCursorAgain() {
        NavigationSession s = guided(CursorParameters.defaults(), routeAtoE());
        long tick = 1;
        for (int x = 0; x <= 60; x += 2) s.tick(x, 65, 0, tick++);
        assertEquals(60, s.cursor(), 1e-9);
        s.onRouteResult(routeAtoE(), tick);
        assertEquals(0, s.cursor(), 1e-9);
        assertEquals(0, s.along(), 1e-9);
        // the player is at x = 60, beyond cursor-jump of the new cursor but on the route: the far candidate is taken
        s.tick(60, 65, 0, tick++);
        assertEquals(60, s.cursor(), 1e-9);
    }

    @Test
    void cursorParametersAreValidated() {
        assertEquals(12, CursorParameters.defaults().cursorJump(), 1e-9);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new CursorParameters(0));
    }
}
