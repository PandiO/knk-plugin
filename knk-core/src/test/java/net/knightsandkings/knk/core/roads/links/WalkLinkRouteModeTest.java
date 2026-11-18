package net.knightsandkings.knk.core.roads.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.route.AStarRouter;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.RouteMode;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.WalkLink;
import net.knightsandkings.knk.core.roads.route.WalkLinkId;

/**
 * Off-road phase 4 (PandiO/knk-workspace#707, DESIGN § 5.4): walk links priced per route mode - their walked length in
 * {@code shortest}, their walk cost (today's) in {@code efficient} and {@code preferred}.
 */
class WalkLinkRouteModeTest {

    private static final String MOVER = "player-1";

    private final CourtyardNetwork net = new CourtyardNetwork();
    private final AtomicLong clock = new AtomicLong(0);
    private final WalkLinkCache cache = new WalkLinkCache(clock::get, 600, 600, 100);
    private final WalkLinkCandidates candidates = new WalkLinkCandidates(net.snapshot, 40, 2.0);
    private final AStarRouter router = new AStarRouter(net.snapshot);

    private WalkLinkPlanner planner(RouteMode mode) {
        return new WalkLinkPlanner(candidates, cache, CourtyardNetwork.WORLD, MOVER, AccessPolicy.ALL_OPEN,
            net.snapshot.minCostFactor(CourtyardNetwork.classCost(), mode), mode);
    }

    /** 22 blocks across the courtyard, but through three doors and a pond: a walk cost of 90 (the road is 80). */
    private static WalkLink slowShortcut() {
        WalkLink plain = CourtyardNetwork.courtyardLink(List.of(), List.of());
        return WalkLink.ofWalkPath(WalkLinkId.of(CourtyardNetwork.A, CourtyardNetwork.B), CourtyardNetwork.WORLD,
            new double[] {0, 64, 0}, new double[] {20, 64, 0}, plain.path().subList(1, plain.path().size() - 1), 22, 90,
            List.of(), List.of());
    }

    private Route route(RouteMode mode) {
        RouteRequest request = net.marketToKeep(AccessPolicy.ALL_OPEN).withMode(mode);
        return router.route(request, planner(mode)).route();
    }

    @Test
    void shortestTakesASlowShortcutEfficientAndPreferredKeepTheRoad() {
        cache.putFound(new WalkLinkCache.Key(CourtyardNetwork.WORLD, MOVER,
            WalkLinkId.of(CourtyardNetwork.A, CourtyardNetwork.B)), slowShortcut());

        Route shortest = route(RouteMode.SHORTEST);
        assertTrue(shortest.hasWalkLinks(), "length only: 22 blocks beat the road's 80");
        assertEquals(RouteMode.SHORTEST, shortest.mode());

        Route efficient = route(RouteMode.EFFICIENT);
        assertFalse(efficient.hasWalkLinks(), "a walk cost of 90 loses to the road's 80");
        assertEquals(200, efficient.length(), 1e-9);

        Route preferred = route(RouteMode.PREFERRED);
        assertFalse(preferred.hasWalkLinks());
        assertEquals(efficient.cost(), preferred.cost(), 1e-9, "A11: no multipliers on this network");
    }

    @Test
    void aPlannerWithoutAModeIsThePreferredOne() {
        cache.putFound(new WalkLinkCache.Key(CourtyardNetwork.WORLD, MOVER,
            WalkLinkId.of(CourtyardNetwork.A, CourtyardNetwork.B)), CourtyardNetwork.courtyardLink(List.of(), List.of()));
        WalkLinkPlanner old = new WalkLinkPlanner(candidates, cache, CourtyardNetwork.WORLD, MOVER, AccessPolicy.ALL_OPEN,
            net.snapshot.minCostFactor(CourtyardNetwork.classCost()));
        assertEquals(RouteMode.DEFAULT, old.mode());
        Route today = router.route(net.marketToKeep(AccessPolicy.ALL_OPEN), old).route();
        Route preferred = route(RouteMode.PREFERRED);
        assertEquals(today.toString(), preferred.toString());
        assertEquals(today.cost(), preferred.cost(), 0.0);
        assertEquals(today.length(), preferred.length(), 0.0);
    }
}
