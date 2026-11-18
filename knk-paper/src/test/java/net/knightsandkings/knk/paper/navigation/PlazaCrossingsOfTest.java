package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.PlazaCrossing;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.RouteResult;

/**
 * The junctions of a route that may be plazas (NPC plaza lines, link M5; the players' preview since off-road phase 7,
 * PandiO/knk-workspace#712): the same list for the NPC road plan and the preview, in route order, each at its node's
 * floor block. Pins the split of {@code plazaCrossingsOf} into {@code plazaNodesOf}.
 */
class PlazaCrossingsOfTest {

    private final NavigationTestNetwork network = new NavigationTestNetwork();

    @Test
    void theRoutesJunctionsAreItsPlazaCrossingsInOrder() {
        // A (0 64 0) → B (100 64 0) → C (200 64 0): the route's ends are its snap points, B the junction it passes
        Route route = network.routeAlongMainStreet();

        List<PlazaCrossing> crossings = NavigationService.plazaCrossingsOf("world", RouteResult.found(route),
            network.snapshot);
        List<RoadNode> nodes = NavigationService.plazaNodesOf(route, network.snapshot);

        assertEquals(List.of(new PlazaCrossing(new NavPoint("world", 100, 64, 0), 0)), crossings);
        assertEquals(List.of(NavigationTestNetwork.B), nodes.stream().map(RoadNode::id).toList());
    }

    @Test
    void noRouteNoCrossings() {
        assertEquals(List.of(), NavigationService.plazaCrossingsOf("world", RouteResult.noRoute(), network.snapshot));
    }
}
