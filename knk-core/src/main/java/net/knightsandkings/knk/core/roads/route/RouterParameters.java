package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tunables of snapping and routing, with the DESIGN §4 {@code navigation} defaults. Phase 3's
 * {@code NavigationConfig} (reuse map R16) fills it from {@code config.yml}; tests use
 * {@link #defaults()}. Guidance tunables live in {@code core/navigation/SessionParameters}.
 *
 * @param maxSnapDistance    hard limit ({@code max-snap-distance}, 48): a player or destination
 *                           farther than this (weighted) from any road cannot be routed
 * @param snapVerticalWeight {@code snap-vertical-weight} (4): one block of height counts as this
 *                           many blocks when snapping, so a bridge beats the road below
 * @param classCost          {@code class-cost}: routing factor per {@link RoadClass}; a class
 *                           missing from the map costs 1.0
 */
public record RouterParameters(double maxSnapDistance, double snapVerticalWeight, Map<RoadClass, Double> classCost) {

    public static final double DEFAULT_MAX_SNAP_DISTANCE = 48;
    public static final double DEFAULT_SNAP_VERTICAL_WEIGHT = 4;

    public RouterParameters {
        if (!(maxSnapDistance > 0)) {
            throw new IllegalArgumentException("maxSnapDistance must be > 0");
        }
        if (!(snapVerticalWeight >= 0)) {
            throw new IllegalArgumentException("snapVerticalWeight must be >= 0");
        }
        Objects.requireNonNull(classCost, "classCost");
        for (Map.Entry<RoadClass, Double> e : classCost.entrySet()) {
            if (e.getValue() == null || !(e.getValue() > 0)) {
                throw new IllegalArgumentException("classCost." + e.getKey() + " must be > 0");
            }
        }
        classCost = Map.copyOf(classCost);
    }

    /** The DESIGN §4 defaults: 48 blocks, weight 4, {@code Main 0.9, Road 1.0, Path 1.15}. */
    public static RouterParameters defaults() {
        return new RouterParameters(DEFAULT_MAX_SNAP_DISTANCE, DEFAULT_SNAP_VERTICAL_WEIGHT, defaultClassCost());
    }

    /** {@code Main 0.9, Road 1.0, Path 1.15}. */
    public static Map<RoadClass, Double> defaultClassCost() {
        Map<RoadClass, Double> m = new EnumMap<>(RoadClass.class);
        m.put(RoadClass.MAIN, 0.9);
        m.put(RoadClass.ROAD, 1.0);
        m.put(RoadClass.PATH, 1.15);
        return m;
    }

    public RouterParameters withSnap(double maxDistance, double verticalWeight) {
        return new RouterParameters(maxDistance, verticalWeight, classCost);
    }

    public RouterParameters withClassCost(Map<RoadClass, Double> cost) {
        return new RouterParameters(maxSnapDistance, snapVerticalWeight, cost);
    }

    /** The factor for a class (1.0 when the map has none). */
    public double classCost(RoadClass roadClass) {
        Double c = classCost.get(roadClass);
        return c == null ? 1.0 : c;
    }
}
