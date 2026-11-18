package net.knightsandkings.knk.core.landmarks;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * Landmarks as walk-planner nodes (off-road navigation phase 5.2, PandiO/knk-workspace#709, DESIGN § 5.2.1 and
 * § 6.2.2): a landmark whose pin lies on the road ("landmark nodes ... when they lie on the road") makes the road node
 * nearest its pin a walk-link end, even when that node is a tile {@link RoadNodeKind#BOUNDARY boundary} that would
 * otherwise be plumbing. Connections between landmark nodes are inferred by the walk search like every other link
 * (decision O05: no stored links; {@link LandmarkConnections#INFERRED}).
 *
 * <p>"On the road" means within {@link #REACH_HORIZONTAL} blocks across and {@link #REACH_VERTICAL} up or down of a
 * road node (feet against the node's floor + 1). Pruned nodes (tombstones) and the routing view's
 * {@link RoadNodeKind#SPLIT split} nodes (a gate door's ends, a region border; not stable across views) never take
 * part. Landmark nodes in the cluster graph of the hierarchical search (§ 5.3.3) are phase 3's (#706, not built).
 *
 * <p>Bukkit-free; pure.
 */
public final class LandmarkLinkNodes {

    /** Blocks across within which a landmark's pin counts as on a road node. */
    public static final double REACH_HORIZONTAL = 4.0;
    /** Blocks up or down within which a landmark's pin counts as on a road node. */
    public static final double REACH_VERTICAL = 3.0;

    private LandmarkLinkNodes() {
    }

    /**
     * The road nodes of {@code snapshot} that landmarks of its world stand on, mapped to the landmark's id (the nearest
     * node per landmark; when two landmarks share a node, the lower id). Empty without landmarks.
     */
    public static Map<Integer, Integer> of(RoadNetworkSnapshot snapshot, List<Landmark> landmarks) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (landmarks == null || landmarks.isEmpty()) {
            return Map.of();
        }
        String world = snapshot.world().toLowerCase(Locale.ROOT);
        Map<Integer, Integer> out = new HashMap<>();
        for (Landmark landmark : landmarks) {
            if (landmark == null || !landmark.world().toLowerCase(Locale.ROOT).equals(world)) {
                continue;
            }
            RoadNode best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (RoadNode node : snapshot.nodes()) {
                if (!eligible(node)) {
                    continue;
                }
                double dx = node.x() + 0.5 - landmark.x();
                double dz = node.z() + 0.5 - landmark.z();
                double dy = node.y() + 1 - landmark.y();
                double across = Math.sqrt(dx * dx + dz * dz);
                if (across > REACH_HORIZONTAL || Math.abs(dy) > REACH_VERTICAL) {
                    continue;
                }
                double d = across * across + dy * dy;
                if (d < bestDistance || d == bestDistance && best != null && node.id() < best.id()) {
                    best = node;
                    bestDistance = d;
                }
            }
            if (best != null) {
                out.merge(best.id(), landmark.id(), Math::min);
            }
        }
        return Map.copyOf(out);
    }

    /** Whether a node can stand for a landmark: not a tombstone and not a routing-time split. */
    static boolean eligible(RoadNode node) {
        return node.kind() != RoadNodeKind.SPLIT && !node.kind().isTombstone();
    }
}
