package net.knightsandkings.knk.core.navigation.shape;

import java.util.Objects;

/**
 * A junction a route passes that may be a plaza (implementation plan 4c.3 decision 6, link M5): the road-graph builder
 * collapses a plaza into one junction, so the route runs to its middle and out again. Formerly
 * {@code PlazaCrossing}; a top-level record in the shared shaping package since the off-road design's
 * phase 1 (O11, PandiO/knk-workspace#704).
 *
 * @param centre the junction's floor point (on the route line)
 * @param radius a designed plaza's radius in blocks, or 0 for a junction whose open floor the corridor measures
 *               (an automatic plaza, or no plaza at all)
 */
public record PlazaCrossing(NavPoint centre, int radius) {
    public PlazaCrossing {
        Objects.requireNonNull(centre, "centre");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be >= 0 (0 = measure)");
        }
    }
}
