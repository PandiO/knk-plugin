package net.knightsandkings.knk.paper.navigation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.landmarks.LandmarkWeights;
import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.navigation.model.NavigationModelHolder;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.paper.navigation.RegionShapes;

/** Off-road phase 5.2 (PandiO/knk-workspace#709): the navigation model's landmarks for the walk planner. */
class ModelLandmarksTest {

    private static final RegionShape HALL = RegionShape.cuboid(0, 60, 0, 20, 80, 20);

    private final NavigationModelHolder holder = new NavigationModelHolder();
    private final RegionShapes regions = (world, id) -> "world".equals(world) && "hall".equals(id)
        ? Optional.of(HALL) : Optional.empty();

    private static Landmark landmark(int id, double x, Landmark.Extent extent, String regionId, Double radius,
                                     double preference) {
        return new Landmark(id, "L" + id, "world", x, 65, 0.5, extent, regionId, radius, null, null, true, preference);
    }

    /** Two road nodes: 1 at (0, 64, 0), 2 at (200, 64, 0). */
    private static RoadNetworkSnapshot road() {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder("world");
        b.addNode(new RoadNode(1, 0, 64, 0, RoadNodeKind.ENDPOINT, null, 1));
        b.addNode(new RoadNode(2, 200, 64, 0, RoadNodeKind.ENDPOINT, null, 1));
        return b.build();
    }

    private void model(Landmark... landmarks) {
        holder.replace(new NavigationModel(Instant.parse("2026-11-18T12:00:00Z"), List.of(), List.of(), null,
            List.of(landmarks)));
    }

    @Test
    void theWeightedAreasOfAWorldWithRegionsResolved() {
        model(landmark(1, 0.5, Landmark.Extent.REGION, "hall", null, 2.0),
            landmark(2, 0.5, Landmark.Extent.RADIUS, null, 5.0, 0.5),
            landmark(3, 0.5, Landmark.Extent.REGION, "cellar", null, 2.0));
        ModelLandmarks landmarks = new ModelLandmarks(() -> holder, regions, 3.0);

        List<LandmarkWeights.Area> areas = landmarks.preferenceAreas("world");

        assertEquals(List.of(1, 2), areas.stream().map(LandmarkWeights.Area::landmarkId).toList(), "an unknown region drops");
        assertSame(HALL, ((LandmarkWeights.RegionArea) areas.get(0).shape()).region());
        assertEquals(3.0, landmarks.preferenceMax());
        assertTrue(landmarks.preferenceAreas("nether").isEmpty());
    }

    @Test
    void noModelYetMeansNoLandmarks() {
        ModelLandmarks landmarks = new ModelLandmarks(() -> null, regions, 0.0);
        assertTrue(landmarks.preferenceAreas("world").isEmpty());
        assertTrue(landmarks.linkNodes(road()).isEmpty());
        assertEquals(4.0, landmarks.preferenceMax(), "a bad cap is the default");
        assertTrue(new ModelLandmarks(() -> holder, regions, 4.0).preferenceAreas("world").isEmpty(), "an empty holder");
    }

    @Test
    void theLinkNodesAreKeptPerSnapshotAndLandmarkList() {
        RoadNetworkSnapshot road = road();
        model(landmark(7, 0.5, Landmark.Extent.NONE, null, null, 1.0)); // on node 1
        ModelLandmarks landmarks = new ModelLandmarks(() -> holder, regions, 4.0);

        Map<Integer, Integer> first = landmarks.linkNodes(road);
        assertEquals(Map.of(1, 7), first);
        assertSame(first, landmarks.linkNodes(road), "the same snapshot and list: kept");

        model(landmark(8, 200.5, Landmark.Extent.NONE, null, null, 1.0)); // on node 2
        assertEquals(Map.of(2, 8), landmarks.linkNodes(road), "a new model: computed again");
    }
}
