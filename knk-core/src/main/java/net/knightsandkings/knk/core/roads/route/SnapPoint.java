package net.knightsandkings.knk.core.roads.route;

/**
 * A position on the network: a point on one segment of one edge's polyline (DESIGN §6.2 step 2).
 * The router splits the edge here with a virtual node.
 *
 * @param edgeId       the edge
 * @param segmentIndex the polyline segment (0-based) the point lies on
 * @param t            parameter inside that segment, 0 = segment start, 1 = segment end
 * @param point        the floor-block position {@code {x, y, z}} on the polyline
 * @param distance     weighted distance from the query point (height × the vertical weight);
 *                     0 for goals generated on the network itself
 * @param along        polyline distance from the edge's From node to {@code point}
 */
public record SnapPoint(int edgeId, int segmentIndex, double t, double[] point, double distance, double along) {

    public SnapPoint {
        if (point == null || point.length != 3) {
            throw new IllegalArgumentException("point must be {x, y, z}");
        }
        point = point.clone();
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be >= 0");
        }
    }

    public double x() {
        return point[0];
    }

    public double y() {
        return point[1];
    }

    public double z() {
        return point[2];
    }

    /** A point on an edge with no query distance (region goals, tests). */
    public static SnapPoint onEdge(RoadNetworkSnapshot snapshot, int edgeId, double along) {
        EdgePolyline p = snapshot.polyline(edgeId);
        double clamped = Math.max(0, Math.min(p.length(), along));
        int seg = p.segmentAt(clamped);
        double segLen = p.segmentLength(seg);
        double t = segLen <= 0 ? 0 : (clamped - p.cumulativeAt(seg)) / segLen;
        return new SnapPoint(edgeId, seg, t, p.pointAt(clamped), 0, clamped);
    }

    /** A network node as a snap point (on its first incident edge, at that edge's end). */
    public static SnapPoint atNode(RoadNetworkSnapshot snapshot, int nodeId) {
        int[] incident = snapshot.incidentEdgeIndexes(nodeId);
        if (incident.length == 0) {
            throw new IllegalArgumentException("node " + nodeId + " has no edges");
        }
        var edge = snapshot.edgeAt(incident[0]);
        double along = edge.fromNodeId() == nodeId ? 0 : snapshot.polyline(edge).length();
        return onEdge(snapshot, edge.id(), along);
    }

    /** Euclidean distance between two snap points' positions. */
    public double distanceTo(SnapPoint other) {
        return EdgePolyline.distance(point, other.point);
    }

    @Override
    public String toString() {
        return "SnapPoint{edge=" + edgeId + ", seg=" + segmentIndex + ", t=" + t + ", at=(" + point[0] + ", " + point[1]
            + ", " + point[2] + "), dist=" + distance + ", along=" + along + "}";
    }
}
