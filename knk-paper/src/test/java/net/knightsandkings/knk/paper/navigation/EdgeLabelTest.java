package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RoutingView;
import net.knightsandkings.knk.core.roads.route.RoutingView.Span;

/** {@code /knk road why} names edges as admins know them, also on the routing view (rev. 7 Part A, §2.5). */
class EdgeLabelTest {

    @Test
    void aPieceIsNamedByItsStoredEdgeAndStretch() {
        RoadNetworkSnapshot stored = new NavigationTestNetwork().snapshot;
        RoadNetworkSnapshot view = RoutingView.build(stored, Map.of(NavigationTestNetwork.E_BC, List.of(
            new Span(0, 48.5, List.of(), List.of()),
            new Span(48.5, 50.5, List.of(), List.of(NavigationTestNetwork.GATE_DOOR)),
            new Span(50.5, 100, List.of(), List.of()))));
        RoadEdge door = view.edges().stream().filter(e -> !e.gateDoorIds().isEmpty()).findFirst().orElseThrow();

        assertEquals("#11 blocks 49-51", NavigationService.edgeLabel(view, door));
        assertEquals("#10", NavigationService.edgeLabel(view, view.requireEdge(NavigationTestNetwork.E_AB)));
    }
}
