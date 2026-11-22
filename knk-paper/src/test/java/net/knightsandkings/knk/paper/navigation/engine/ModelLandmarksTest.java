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

    private static Landmark landmark(int id, double x, List<String> regions, double preference) {
        return new Landmark(id, "L" + id, "world", x, 65, 0.5, regions, null, null, true, preference);
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
        model(landmark(1, 0.5, List.of("hall"), 2.0),
            landmark(2, 0.5, List.of(), 0.5),
            landmark(3, 0.5, List.of("cellar"), 2.0));
        ModelLandmarks landmarks = new ModelLandmarks(() -> holder, regions, 3.0);

        List<LandmarkWeights.Area> areas = landmarks.preferenceAreas("world");

        assertEquals(List.of(1), areas.stream().map(LandmarkWeights.Area::landmarkId).toList(),
            "a point (the old radius landmark, LD08) and an unknown region drop");
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
        model(landmark(7, 0.5, List.of(), 1.0)); // on node 1
        ModelLandmarks landmarks = new ModelLandmarks(() -> holder, regions, 4.0);

        Map<Integer, Integer> first = landmarks.linkNodes(road);
        assertEquals(Map.of(1, 7), first);
        assertSame(first, landmarks.linkNodes(road), "the same snapshot and list: kept");

        model(landmark(8, 200.5, List.of(), 1.0)); // on node 2
        assertEquals(Map.of(2, 8), landmarks.linkNodes(road), "a new model: computed again");
    }

    /**
     * A14 weights side (landmark-domain LD22, #985): a LinkedDomain landmark's area is the parent's main region plus
     * the parent's sub-regions the server finds by knk-domain-id; the weight covers the union once.
     */
    @Test
    void aLinkedDomainArea_GetsTheParentsSubRegions_FromTheServer() {
        RegionShape town = RegionShape.cuboid(0, 60, 0, 20, 80, 20);
        RegionShape annex = RegionShape.cuboid(40, 60, 0, 50, 80, 10);
        RegionShapes server = new RegionShapes() {
            @Override
            public Optional<RegionShape> shape(String world, String regionId) {
                return switch (regionId) {
                    case "town_1" -> Optional.of(town);
                    case "domain_1_sub_2" -> Optional.of(annex);
                    default -> Optional.empty();
                };
            }

            @Override
            public List<String> domainRegionIds(String world, String regionId) {
                return "town_1".equals(regionId) ? List.of("domain_1_sub_2") : List.of();
            }
        };
        model(new Landmark(5, "Market cross", "world", 5, 65, 5, List.of("town_1"), 1, "Rivia", true, 2.0));

        List<LandmarkWeights.Area> areas = new ModelLandmarks(() -> holder, server, 4.0).preferenceAreas("world");

        assertEquals(1, areas.size());
        LandmarkWeights.UnionArea union = (LandmarkWeights.UnionArea) areas.get(0).shape();
        assertEquals(List.of(town, annex), union.regions());
        assertEquals(2.0, LandmarkWeights.of(areas, 4.0).within(new int[] {-100, 0, -100, 100, 100, 100})
            .multiplier(45, 65, 5), "a cell in the parent's sub-region is weighted");
    }
}
