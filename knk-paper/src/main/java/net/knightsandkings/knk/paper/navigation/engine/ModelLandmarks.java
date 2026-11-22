package net.knightsandkings.knk.paper.navigation.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import net.knightsandkings.knk.core.landmarks.LandmarkLinkNodes;
import net.knightsandkings.knk.core.landmarks.LandmarkWeights;
import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.navigation.model.NavigationModelHolder;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.paper.navigation.NavigationService;
import net.knightsandkings.knk.paper.navigation.RegionShapes;

/**
 * The landmarks of the navigation model for the walk planner (off-road navigation phase 5.2, PandiO/knk-workspace#709,
 * DESIGN § 5.4.3 and § 6.2.2; only with {@code navigation.landmarks} on): the weighted areas of a world, each the
 * union of the landmark's regions (since PandiO/knk-workspace#985, LD22) resolved through {@link RegionShapes}
 * (WorldGuard, main thread), and the road nodes landmarks stand on. The
 * link nodes are kept per road snapshot and landmark list, so a route asks for them at no cost until either changes.
 */
public final class ModelLandmarks implements NavigationService.Landmarks {

    private final Supplier<NavigationModelHolder> model;
    private final RegionShapes regionShapes;
    private final double preferenceMax;
    private volatile Cached cached;

    private record Cached(RoadNetworkSnapshot snapshot, List<Landmark> landmarks, Map<Integer, Integer> nodes) {
    }

    public ModelLandmarks(Supplier<NavigationModelHolder> model, RegionShapes regionShapes, double preferenceMax) {
        this.model = Objects.requireNonNull(model, "model");
        this.regionShapes = Objects.requireNonNull(regionShapes, "regionShapes");
        this.preferenceMax = LandmarkWeights.validMax(preferenceMax);
    }

    @Override
    public List<LandmarkWeights.Area> preferenceAreas(String world) {
        return LandmarkWeights.areas(withDomainRegions(landmarks(), world, regionShapes), world,
            regionId -> regionShapes.shape(world, regionId));
    }

    @Override
    public double preferenceMax() {
        return preferenceMax;
    }

    @Override
    public Map<Integer, Integer> linkNodes(RoadNetworkSnapshot snapshot) {
        List<Landmark> landmarks = landmarks();
        Cached known = cached;
        if (known != null && known.snapshot() == snapshot && known.landmarks() == landmarks) {
            return known.nodes();
        }
        Map<Integer, Integer> nodes = LandmarkLinkNodes.of(snapshot, landmarks);
        cached = new Cached(snapshot, landmarks, nodes);
        return nodes;
    }

    /**
     * PandiO/knk-workspace#985 (LD22): each weighted landmark of {@code world} with the other regions of its main
     * region's domain added ({@link RegionShapes#domainRegionIds}: the sub-regions of a {@code LinkedDomain} parent,
     * and any own sub-region {@code api/landmarks} did not list yet), so the weight covers the whole area once.
     */
    static List<Landmark> withDomainRegions(List<Landmark> landmarks, String world, RegionShapes shapes) {
        List<Landmark> out = new ArrayList<>(landmarks.size());
        for (Landmark landmark : landmarks) {
            if (landmark == null || !landmark.weighted() || world == null || !world.equalsIgnoreCase(landmark.world())) {
                out.add(landmark);
                continue;
            }
            List<String> ids = new ArrayList<>(landmark.regionIds());
            ids.addAll(shapes.domainRegionIds(world, landmark.mainRegionId()));
            out.add(landmark.withRegionIds(ids));
        }
        return out;
    }

    private List<Landmark> landmarks() {
        NavigationModelHolder holder = model.get();
        NavigationModel current = holder == null ? null : holder.current();
        return current == null ? List.of() : current.landmarks();
    }
}
