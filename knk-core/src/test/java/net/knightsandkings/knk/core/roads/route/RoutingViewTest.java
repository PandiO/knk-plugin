package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.build.EdgeTagging;
import net.knightsandkings.knk.core.roads.route.RoutingView.Hit;
import net.knightsandkings.knk.core.roads.route.RoutingView.Span;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static net.knightsandkings.knk.core.roads.route.NetworkFixture.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rev. 7 Part A (REV7_PROPOSAL §2, §6): the routing view cuts stored edges where access changes, and the
 * router on it gets the gated and clipped cases right without the start/goal-side patches.
 */
class RoutingViewTest {

    private final RoadNetworkSnapshot town = NetworkFixture.town();
    private final RouterParameters params = RouterParameters.defaults();

    /** Samples of an edge every {@code step} blocks (as the live tags take them), tagged per floor block. */
    private static <T> List<Hit<T>> hits(RoadNetworkSnapshot snapshot, int edgeId, double step,
                                         Function<EdgeTagging.Sample, Set<T>> tags) {
        List<Hit<T>> out = new ArrayList<>();
        for (EdgeTagging.Sample s : EdgeTagging.alongSamples(snapshot.requireEdge(edgeId).geometry(), step)) {
            out.add(new Hit<>(s.along(), tags.apply(s)));
        }
        return out;
    }

    private static <T> Function<EdgeTagging.Sample, Set<T>> none() {
        return s -> Set.of();
    }

    /** E_BE (B x=100 → E x=200) with its gate door 7 found at x = 150 only. */
    private List<Span> gateSpans() {
        return RoutingView.spans(town.requireEdge(E_BE), 100, hits(town, E_BE, EdgeTagging.REGION_STEP, none()),
            hits(town, E_BE, EdgeTagging.DOOR_STEP, s -> s.x() == 150 ? Set.of(GATE_DOOR) : Set.of()));
    }

    /** E_AB (A x=0 → B x=100) clipped by the castle region between x = 40 and 60. */
    private List<Span> clipSpans() {
        return RoutingView.spans(town.requireEdge(E_AB), 100,
            hits(town, E_AB, EdgeTagging.REGION_STEP, s -> s.x() >= 40 && s.x() <= 60 ? Set.of(CASTLE_REGION) : Set.of()),
            hits(town, E_AB, EdgeTagging.DOOR_STEP, none()));
    }

    private static List<RoadEdge> pieces(RoadNetworkSnapshot view, int storedEdgeId) {
        return view.edges().stream().filter(e -> view.storedEdgeId(e.id()) == storedEdgeId && e.id() != storedEdgeId)
            .sorted(Comparator.comparingDouble(e -> view.piece(e.id()).orElseThrow().fromAlong())).toList();
    }

    // ---- spans -----------------------------------------------------------------------------------

    @Test
    void aDoorBecomesItsOwnShortStretchWidenedToTheSamplesEitherSide() {
        List<Span> spans = gateSpans();

        assertEquals(3, spans.size());
        assertEquals(List.of(), spans.get(0).gateDoorIds());
        assertEquals(List.of(GATE_DOOR), spans.get(1).gateDoorIds());
        assertEquals(List.of(), spans.get(2).gateDoorIds());
        assertEquals(48.5, spans.get(1).from(), 1e-9, "the last sample before the door (block 149 is first met at 48.5)");
        assertEquals(50.5, spans.get(1).to(), 1e-9, "the first sample after it");
        assertEquals(0, spans.get(0).from(), 1e-9);
        assertEquals(100, spans.get(2).to(), 1e-9);
    }

    @Test
    void aRegionBorderCutsTheRoadJustOutsideTheRegion() {
        List<Span> spans = clipSpans();

        assertEquals(3, spans.size());
        assertEquals(List.of(CASTLE_REGION), spans.get(1).regionIds());
        assertEquals(38, spans.get(1).from(), 1e-9);
        assertEquals(62, spans.get(1).to(), 1e-9);
        assertTrue(spans.get(0).regionIds().isEmpty());
        assertTrue(spans.get(2).regionIds().isEmpty());
    }

    @Test
    void nothingChangingAlongTheEdgeIsOneSpanAndAStoredTagFoundNowhereStaysOnTheWholeEdge() {
        RoadEdge castleRoad = town.requireEdge(E_C_CASTLE); // stored: region kardenna_castle
        List<Span> spans = RoutingView.spans(castleRoad, 100, hits(town, E_C_CASTLE, 2.0, none()),
            hits(town, E_C_CASTLE, 0.5, none()));

        assertEquals(1, spans.size());
        assertEquals(List.of(CASTLE_REGION), spans.get(0).regionIds());

        List<Span> everywhere = RoutingView.spans(castleRoad, 100,
            hits(town, E_C_CASTLE, 2.0, s -> Set.of(CASTLE_REGION)), hits(town, E_C_CASTLE, 0.5, none()));
        assertEquals(1, everywhere.size(), "a region over the whole edge cuts nothing");
    }

    // ---- the view --------------------------------------------------------------------------------

    @Test
    void theViewCutsTheGateEdgeIntoPiecesThatMapBackToIt() {
        RoadNetworkSnapshot view = RoutingView.build(town, Map.of(E_BE, gateSpans()));

        assertTrue(view.edge(E_BE).isEmpty(), "the stored edge is replaced by its pieces");
        List<RoadEdge> pieces = pieces(view, E_BE);
        assertEquals(3, pieces.size());
        pieces.forEach(p -> assertTrue(p.id() >= RoutingView.FIRST_SYNTHETIC_ID));
        assertEquals(B, pieces.get(0).fromNodeId());
        assertEquals(E, pieces.get(2).toNodeId());
        assertEquals(pieces.get(0).toNodeId(), pieces.get(1).fromNodeId());
        assertEquals(pieces.get(1).toNodeId(), pieces.get(2).fromNodeId());
        assertEquals(List.of(GATE_DOOR), pieces.get(1).gateDoorIds());
        assertTrue(pieces.get(0).gateDoorIds().isEmpty() && pieces.get(2).gateDoorIds().isEmpty());
        assertEquals(100, pieces.stream().mapToDouble(RoadEdge::length).sum(), 1e-9, "lengths add up to the stored one");
        pieces.forEach(p -> {
            assertEquals(STREET_KEEP, p.streetId().getAsInt());
            assertEquals(PROFILE_MAIN, p.profileId().getAsInt());
        });

        RoadNode split = view.requireNode(pieces.get(1).fromNodeId());
        assertEquals(RoadNodeKind.SPLIT, split.kind());
        assertTrue(split.id() >= RoutingView.FIRST_SYNTHETIC_ID);
        assertArrayEquals(new double[] {149, 64, 0}, split.position());
        assertEquals(town.requireNode(B).componentId(), split.componentId());
        assertFalse(split.isDestination());

        assertEquals(E_BE, view.storedEdgeId(pieces.get(1).id()));
        assertEquals(new RoadNetworkSnapshot.EdgePiece(E_BE, 48.5, 50.5), view.piece(pieces.get(1).id()).orElseThrow());
        assertEquals(E_AB, view.storedEdgeId(E_AB), "an uncut edge is its own");
        assertTrue(view.piece(E_AB).isEmpty());
        assertEquals(town.edgeCount() + 2, view.edgeCount());
        assertEquals(town.nodeCount() + 2, view.nodeCount());
    }

    @Test
    void storedIdsThatReachTheViewsRangeAreRefused() {
        RoadNetworkSnapshot clash = NetworkFixture.townBuilder()
            .addNode(node(RoutingView.FIRST_SYNTHETIC_ID, 300, 64, 300, RoadNodeKind.ENDPOINT, null, 3)).build();

        assertThrows(IllegalStateException.class, () -> RoutingView.build(clash, Map.of()));
    }

    // ---- routing on the view ---------------------------------------------------------------------

    @Test
    void guidanceToAClosedGateEndsAtTheDoorNotAtTheNodeBeforeItsEdge() {
        AccessPolicy closed = AStarRouterTest.gate(AnimationState.CLOSED, false, false);

        RouteResult stored = new AStarRouter(town).routeOrExplain(
            RouteRequest.of(SnapPoint.atNode(town, A), SnapPoint.atNode(town, E), closed, params));
        assertEquals(RouteResult.Status.BLOCKED, stored.status());
        assertEquals(100, stored.explanation().partialRoute().end().x(), 1e-9, "stored: the gate's edge starts at B");

        RoadNetworkSnapshot view = RoutingView.build(town, Map.of(E_BE, gateSpans()));
        RouteResult cut = new AStarRouter(view).routeOrExplain(
            RouteRequest.of(SnapPoint.atNode(view, A), SnapPoint.atNode(view, E), closed, params));
        assertEquals(RouteResult.Status.BLOCKED, cut.status());
        assertTrue(cut.explanation().isGateBlock());
        assertEquals(149, cut.explanation().partialRoute().end().x(), 1e-9, "the view: right before the door");
    }

    @Test
    void theOpenSideOfAClosedGateIsRoutableWithoutStartSides() {
        AccessPolicy closed = AStarRouterTest.gate(AnimationState.CLOSED, false, false);

        RouteResult stored = new AStarRouter(town).route(
            RouteRequest.of(SnapPoint.onEdge(town, E_BE, 70), SnapPoint.atNode(town, E), closed, params));
        assertFalse(stored.isFound(), "stored: the whole gate edge is closed (N6 needed StartSides)");

        RoadNetworkSnapshot view = RoutingView.build(town, Map.of(E_BE, gateSpans()));
        RoadEdge beyond = pieces(view, E_BE).get(2);
        RouteResult cut = new AStarRouter(view).route(
            RouteRequest.of(SnapPoint.onEdge(view, beyond.id(), 20), SnapPoint.atNode(view, E), closed, params));
        assertTrue(cut.isFound());
        assertEquals(E, view.requireNode(beyond.toNodeId()).id());
    }

    @Test
    void aRoadClippingADeniedDistrictIsBlockedOnlyInsideIt() {
        AccessPolicy denied = AStarRouterTest.castle(false, null, Set.of());
        RoadNetworkSnapshot stored = NetworkFixture.townBuilder()
            .addEdge(edge(E_AB, A, B, line(0, 64, 0, 100, 64, 0)).profile(PROFILE_MAIN).street(STREET_KEEP)
                .region(CASTLE_REGION, CASTLE_DOMAIN).build())
            .build();
        RouteResult whole = new AStarRouter(stored).route(
            RouteRequest.of(SnapPoint.atNode(stored, A), SnapPoint.onEdge(stored, E_AB, 20), denied, params));
        assertFalse(whole.isFound(), "stored: the clipped edge is closed along its whole length");

        RoadNetworkSnapshot view = RoutingView.build(town, Map.of(E_AB, clipSpans()));
        RoadEdge before = pieces(view, E_AB).get(0);
        RouteResult cut = new AStarRouter(view).route(
            RouteRequest.of(SnapPoint.atNode(view, A), SnapPoint.onEdge(view, before.id(), 20), denied, params));
        assertTrue(cut.isFound());
        assertEquals(List.of(before.id()), cut.route().steps().stream().map(s -> s.edge().id()).toList());

        RouteResult into = new AStarRouter(view).routeOrExplain(
            RouteRequest.of(SnapPoint.atNode(view, A), SnapPoint.onEdge(view, pieces(view, E_AB).get(1).id(), 10),
                denied, params));
        assertEquals(RouteResult.Status.BLOCKED, into.status());
        assertEquals(38, into.explanation().partialRoute().end().x(), 1e-9, "guided to the district's edge");
    }

    // ---- KNG-110: a region over part of the road's width -------------------------------------------

    /** E_AB with the castle region on its centre line between x = 40 and 60, and the given lanes across the road. */
    private List<Span> partSpans(Function<EdgeTagging.Sample, Set<List<String>>> lanes) {
        return RoutingView.spans(town.requireEdge(E_AB), 100,
            hits(town, E_AB, EdgeTagging.REGION_STEP, s -> s.x() >= 40 && s.x() <= 60 ? Set.of(CASTLE_REGION) : Set.of()),
            hits(town, E_AB, EdgeTagging.REGION_STEP, lanes),
            hits(town, E_AB, EdgeTagging.DOOR_STEP, none()));
    }

    /** A free row beside the castle (no region there) between x = 40 and {@code to}. */
    private static Function<EdgeTagging.Sample, Set<List<String>>> freeRow(int to) {
        return s -> s.x() >= 40 && s.x() <= to ? Set.of(List.of()) : Set.of();
    }

    @Test
    void minimalLanesDropCellsThatHoldAnotherCellsRegions() {
        assertEquals(List.of(List.of("town"), List.of("a", "b")), RoutingView.minimalLanes(
            List.of(Set.of("town", CASTLE_REGION), Set.of("town"), Set.of("b", "a"), Set.of("town"))));
        assertEquals(List.of(List.of()), RoutingView.minimalLanes(List.of(Set.of(CASTLE_REGION), Set.of())));
    }

    @Test
    void aRegionOverPartOfTheWidthKeepsItsTagAndTheFreeLane() {
        List<Span> spans = partSpans(freeRow(60));

        assertEquals(3, spans.size());
        assertEquals(List.of(CASTLE_REGION), spans.get(1).regionIds(), "the centre line is still in the region (exit)");
        assertEquals(List.of(List.of()), spans.get(1).lanes(), "a free row beside it");
        assertEquals(38, spans.get(1).from(), 1e-9);
        assertEquals(62, spans.get(1).to(), 1e-9);
        assertTrue(spans.get(0).lanes().isEmpty() && spans.get(2).lanes().isEmpty());
    }

    @Test
    void whereTheRegionCoversTheWholeWidthThePieceHasNoLanes() {
        List<Span> spans = partSpans(freeRow(50)); // the whole width from x = 52

        assertEquals(4, spans.size());
        assertEquals(List.of(List.of()), spans.get(1).lanes());
        assertEquals(52, spans.get(1).to(), 1e-9, "widened to the first sample without the gap");
        assertEquals(List.of(CASTLE_REGION), spans.get(2).regionIds());
        assertTrue(spans.get(2).lanes().isEmpty(), "the centre line decides: blocked");
        assertEquals(62, spans.get(2).to(), 1e-9);
    }

    @Test
    void aStoredRegionFoundNowhereJoinsEveryLane() {
        RoadEdge castleRoad = town.requireEdge(E_C_CASTLE); // stored: region kardenna_castle
        List<Span> spans = RoutingView.spans(castleRoad, 100, hits(town, E_C_CASTLE, 2.0, s -> Set.of("town")),
            hits(town, E_C_CASTLE, 2.0, s -> Set.of(List.of("town"), List.of("market"))), hits(town, E_C_CASTLE, 0.5, none()));

        assertEquals(1, spans.size());
        assertEquals(List.of(List.of(CASTLE_REGION, "market"), List.of(CASTLE_REGION, "town")), spans.get(0).lanes());
    }

    @Test
    void aDeniedRegionOverPartOfTheWidthLeavesTheRoadOpen() {
        AccessPolicy denied = AStarRouterTest.castle(false, null, Set.of());

        RoadNetworkSnapshot gap = RoutingView.build(town, Map.of(E_AB, partSpans(freeRow(60))));
        RouteResult through = new AStarRouter(gap).route(
            RouteRequest.of(SnapPoint.atNode(gap, A), SnapPoint.atNode(gap, B), denied, params));
        assertTrue(through.isFound());
        assertEquals(100, through.route().length(), 1e-9, "straight along the road, past the region");
        assertEquals(List.of(List.of()), pieces(gap, E_AB).get(1).lanes(), "the piece carries its lanes");

        RoadNetworkSnapshot whole = RoutingView.build(town, Map.of(E_AB, partSpans(none())));
        RouteResult around = new AStarRouter(whole).route(
            RouteRequest.of(SnapPoint.atNode(whole, A), SnapPoint.atNode(whole, B), denied, params));
        assertTrue(around.isFound());
        assertTrue(around.route().steps().stream().noneMatch(s -> whole.storedEdgeId(s.edge().id()) == E_AB),
            "the whole width covered: the way round");
    }

    // ---- instructions ----------------------------------------------------------------------------

    @Test
    void splitNodesGiveNoInstructionsAndTheLevelCheckLooksThroughThem() {
        double tunnel = town.polyline(E_TUNNEL).length();
        List<Span> cut = List.of(new Span(0, 5, List.of(), List.of()), new Span(5, 7, List.of(), List.of(99)),
            new Span(7, tunnel, List.of(), List.of()));
        RoadNetworkSnapshot view = RoutingView.build(town, Map.of(E_TUNNEL, cut));

        assertEquals(texts(town, B, TUNNEL_END), texts(view, B, TUNNEL_END));
        assertTrue(texts(view, B, TUNNEL_END).contains("Go down into the tunnel at 100,100"), "at C, as stored");
    }

    private List<String> texts(RoadNetworkSnapshot snapshot, int from, int to) {
        Route route = new AStarRouter(snapshot).route(RouteRequest.of(SnapPoint.atNode(snapshot, from),
            SnapPoint.atNode(snapshot, to), AccessPolicy.ALL_OPEN, params)).route();
        return new ManeuverBuilder(snapshot).build(route).stream()
            .map(m -> m.text() + " at " + (int) m.position()[0] + "," + (int) m.position()[2]).toList();
    }
}
