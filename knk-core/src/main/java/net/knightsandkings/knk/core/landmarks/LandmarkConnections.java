package net.knightsandkings.knk.core.landmarks;

import java.util.List;

/**
 * How landmarks connect to each other (off-road DESIGN § 6.3, decision O05): the one interface the walk planner reads
 * landmark-to-landmark links through. In v1 they are only inferred by the walk search (phase 5.2 builds that), so
 * {@link #INFERRED} lists no stored link; explicit links ({@code landmark_links}, § 10.2) would change this
 * implementation only, not the planner. A seam, nothing reads it yet (PandiO/knk-workspace#708).
 */
public interface LandmarkConnections {

    /** A stored link: a preference ({@code weight}), one way only, or a forbidden pair ({@code blocked}). */
    record Link(int fromLandmarkId, int toLandmarkId, double weight, boolean oneWay, boolean blocked) {
    }

    /** The stored links that start at (or, two-way, end at) {@code landmarkId}. */
    List<Link> linksOf(int landmarkId);

    /** v1: no stored links; the walk search infers the connections. */
    LandmarkConnections INFERRED = landmarkId -> List.of();
}
