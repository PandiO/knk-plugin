package net.knightsandkings.knk.core.roads.links;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.core.roads.route.WalkLink;
import net.knightsandkings.knk.core.roads.route.WalkLinkId;

/**
 * The courtyard of DESIGN § 5.2 (phase 2 tests, #705), floor y 64 everywhere:
 *
 * <pre>
 *            P (0,-30) ------------ Q (20,-30)
 *              |                       |
 *   S (-60,0) - A (0,0)   courtyard   B (20,0) - T (80,0)
 *              |                          \
 *              G (0,10) - gate split    H (60, 30) (50 from B: beyond the range)
 * </pre>
 *
 * The road from A to B goes round the courtyard: 30 + 20 + 30 = 80 blocks for a straight 20 (ratio 4, a link
 * candidate). A to G is a 10-block spur ending in a {@link RoadNodeKind#SPLIT split} node (never a link end). S → T on
 * the roads is 60 + 80 + 60 = 200 blocks; with the link A → B, 60 + 22 + 60 = 142. Node X (5, 64, 0) lies in another
 * network component.
 */
final class CourtyardNetwork {

    static final String WORLD = "world";
    static final int S = 1, A = 2, P = 3, Q = 4, B = 5, T = 6, G = 7, H = 8, X = 9;
    static final int E_SA = 11, E_AP = 12, E_PQ = 13, E_QB = 14, E_BT = 15, E_AG = 16, E_BH = 17;
    static final String COURTYARD = "courtyard";
    static final int COURTYARD_DOOR = 70;

    final RoadNetworkSnapshot snapshot;

    CourtyardNetwork() {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder(WORLD);
        b.addProfile(new RoadNetworkSnapshot.Profile(1, "Main", RoadClass.MAIN, 1.0));
        b.addStreet(new RoadNetworkSnapshot.Street(1, "Keepstreet"));
        b.addNode(new RoadNode(S, -60, 64, 0, RoadNodeKind.ENDPOINT, "Market", 1));
        b.addNode(new RoadNode(A, 0, 64, 0, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(new RoadNode(P, 0, 64, -30, RoadNodeKind.ANCHOR, null, 1));
        b.addNode(new RoadNode(Q, 20, 64, -30, RoadNodeKind.ANCHOR, null, 1));
        b.addNode(new RoadNode(B, 20, 64, 0, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(new RoadNode(T, 80, 64, 0, RoadNodeKind.ENDPOINT, "Keep", 1));
        b.addNode(new RoadNode(G, 0, 64, 10, RoadNodeKind.SPLIT, null, 1));
        b.addNode(new RoadNode(H, 60, 64, 30, RoadNodeKind.ENDPOINT, null, 1));
        b.addNode(new RoadNode(X, 5, 64, 0, RoadNodeKind.ENDPOINT, null, 2));
        b.addEdge(edge(E_SA, S, A, -60, 0, 0, 0, 1));
        b.addEdge(edge(E_AP, A, P, 0, 0, 0, -30, 1));
        b.addEdge(edge(E_PQ, P, Q, 0, -30, 20, -30, 1));
        b.addEdge(edge(E_QB, Q, B, 20, -30, 20, 0, 1));
        b.addEdge(edge(E_BT, B, T, 20, 0, 80, 0, 1));
        b.addEdge(edge(E_AG, A, G, 0, 0, 0, 10, -1));
        b.addEdge(edge(E_BH, B, H, 20, 0, 60, 30, -1));
        this.snapshot = b.build();
    }

    private static RoadEdge edge(int id, int from, int to, int x0, int z0, int x1, int z1, int street) {
        List<int[]> geometry = List.of(new int[] {x0, 64, z0}, new int[] {x1, 64, z1});
        return new RoadEdge(id, from, to, geometry, Math.hypot(x1 - x0, z1 - z0), 3, OptionalInt.of(1),
            street < 0 ? OptionalInt.empty() : OptionalInt.of(street), 1.0, EnumSet.noneOf(RoadEdgeFlag.class),
            List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false);
    }

    /** S → T with this policy. */
    RouteRequest marketToKeep(AccessPolicy policy) {
        return RouteRequest.of(SnapPoint.atNode(snapshot, S), SnapPoint.atNode(snapshot, T), policy,
            RouterParameters.defaults());
    }

    /** The link A → B across the courtyard: 22 blocks walked (a step round a well), with these tags. */
    static WalkLink courtyardLink(List<String> regions, List<Integer> doors) {
        List<double[]> walk = new ArrayList<>();
        for (int x = 0; x <= 20; x++) {
            walk.add(new double[] {x + 0.5, 64, (x >= 8 && x <= 12 ? 1 : 0) + 0.5}); // block centres, as WalkPath gives
        }
        return WalkLink.ofWalkPath(WalkLinkId.of(A, B), WORLD, new double[] {0, 64, 0}, new double[] {20, 64, 0}, walk, 22,
            23, regions, doors);
    }

    static Map<RoadClass, Double> classCost() {
        return RouterParameters.defaults().classCost();
    }
}
