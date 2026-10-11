package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A small in-memory road network in the Phase 1 download shape, shared by every Phase 2d test.
 * Every coordinate is a floor block (feet at y + 1). All roads at y = 64 unless noted.
 *
 * <pre>
 *  z=-30      7 ●            bridge (y 72, component 2, edge 35 from 7 to 8, crosses over A–B at x=50)
 *             |
 *  z=0    A ●━━━━━━━● B ━━━━━● E ━━━━━━━━━━━━━━━━━━━● 22 ┅ 23 ━━━● 26 "Kardenna Mill"
 *          1  e10   2  e16   5   e25               511 e24 512  e27 600
 *          ┃ e13    ┃ e11 (Merchantstreet)          (22/23 = Boundary nodes, e24 = stitch)
 *          ┃ (path) ┃           e16 carries gate door 7 (Cinix Keep gate)
 *  z=100  D ●━━━━━━━● C ━━━━━━━● 33   e34 dips to y 58 in the middle (tunnel)
 *          4  e12   3  200
 *          ┃ e18    ┃ e17 (Castle Way, region kardenna_castle / domain 42)
 *  z=200  20 ●━━━━━━━● 9 "Kardenna Castle"   e19 is ONEWAY 9 → 20, also in kardenna_castle
 *          e15 = diagonal path A → C, length 150 (chord 141.4)
 * </pre>
 *
 * Profiles: 1 "Keepstreet main" MAIN, 2 "town road" ROAD, 3 "path" PATH. Streets: 1 Keepstreet,
 * 2 Merchantstreet, 3 Castle Way.
 */
final class NetworkFixture {

    static final int A = 1, B = 2, C = 3, D = 4, E = 5, BRIDGE_N = 7, BRIDGE_S = 8, CASTLE = 9, TWENTY = 20,
        BOUNDARY_W = 22, BOUNDARY_E = 23, MILL = 26, TUNNEL_END = 33;

    static final int E_AB = 10, E_BC = 11, E_CD = 12, E_AD = 13, E_AC = 15, E_BE = 16, E_C_CASTLE = 17, E_D20 = 18,
        E_CASTLE20 = 19, E_STITCH = 24, E_E_BOUNDARY = 25, E_BOUNDARY_MILL = 27, E_TUNNEL = 34, E_BRIDGE = 35;

    static final int GATE_DOOR = 7;
    static final String CASTLE_REGION = "kardenna_castle";
    static final int CASTLE_DOMAIN = 42;
    static final int PROFILE_MAIN = 1, PROFILE_ROAD = 2, PROFILE_PATH = 3;
    static final int STREET_KEEP = 1, STREET_MERCHANT = 2, STREET_CASTLE = 3;

    private NetworkFixture() {
    }

    /** The whole town network above (bridge included). */
    static RoadNetworkSnapshot town() {
        return townBuilder().build();
    }

    static RoadNetworkSnapshot.Builder townBuilder() {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder("world")
            .addProfile(new RoadNetworkSnapshot.Profile(PROFILE_MAIN, "Keepstreet main", RoadClass.MAIN, 1.0))
            .addProfile(new RoadNetworkSnapshot.Profile(PROFILE_ROAD, "town road", RoadClass.ROAD, 1.0))
            .addProfile(new RoadNetworkSnapshot.Profile(PROFILE_PATH, "path", RoadClass.PATH, 1.0))
            .addStreet(new RoadNetworkSnapshot.Street(STREET_KEEP, "Keepstreet"))
            .addStreet(new RoadNetworkSnapshot.Street(STREET_MERCHANT, "Merchantstreet"))
            .addStreet(new RoadNetworkSnapshot.Street(STREET_CASTLE, "Castle Way"));

        b.addNode(node(A, 0, 64, 0, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(node(B, 100, 64, 0, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(node(C, 100, 64, 100, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(node(D, 0, 64, 100, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(node(E, 200, 64, 0, RoadNodeKind.JUNCTION, "Cinix Keep gate", 1));
        b.addNode(node(BRIDGE_N, 50, 72, -30, RoadNodeKind.ENDPOINT, null, 2));
        b.addNode(node(BRIDGE_S, 50, 72, 30, RoadNodeKind.ENDPOINT, null, 2));
        b.addNode(node(CASTLE, 100, 64, 200, RoadNodeKind.JUNCTION, "Kardenna Castle", 1));
        b.addNode(node(TWENTY, 0, 64, 200, RoadNodeKind.JUNCTION, null, 1));
        b.addNode(node(BOUNDARY_W, 511, 64, 0, RoadNodeKind.BOUNDARY, null, 1));
        b.addNode(node(BOUNDARY_E, 512, 64, 0, RoadNodeKind.BOUNDARY, null, 1));
        b.addNode(node(MILL, 600, 64, 0, RoadNodeKind.ENDPOINT, "Kardenna Mill", 1));
        b.addNode(node(TUNNEL_END, 200, 64, 100, RoadNodeKind.ENDPOINT, null, 1));

        b.addEdge(edge(E_AB, A, B, line(0, 64, 0, 100, 64, 0)).profile(PROFILE_MAIN).street(STREET_KEEP).build());
        b.addEdge(edge(E_BC, B, C, line(100, 64, 0, 100, 64, 100)).profile(PROFILE_ROAD).street(STREET_MERCHANT).build());
        b.addEdge(edge(E_CD, C, D, line(100, 64, 100, 0, 64, 100)).profile(PROFILE_ROAD).street(STREET_MERCHANT).build());
        b.addEdge(edge(E_AD, A, D, line(0, 64, 0, 0, 64, 100)).profile(PROFILE_PATH).build());
        b.addEdge(edge(E_AC, A, C, List.of(p(0, 64, 0), p(50, 64, 50), p(100, 64, 100))).length(150)
            .profile(PROFILE_PATH).build());
        b.addEdge(edge(E_BE, B, E, line(100, 64, 0, 200, 64, 0)).profile(PROFILE_MAIN).street(STREET_KEEP)
            .gates(GATE_DOOR).build());
        b.addEdge(edge(E_C_CASTLE, C, CASTLE, line(100, 64, 100, 100, 64, 200)).profile(PROFILE_ROAD)
            .street(STREET_CASTLE).region(CASTLE_REGION, CASTLE_DOMAIN).build());
        b.addEdge(edge(E_D20, D, TWENTY, line(0, 64, 100, 0, 64, 200)).profile(PROFILE_MAIN).build());
        b.addEdge(edge(E_CASTLE20, CASTLE, TWENTY, line(100, 64, 200, 0, 64, 200)).profile(PROFILE_MAIN)
            .flags(RoadEdgeFlag.ONEWAY).region(CASTLE_REGION, CASTLE_DOMAIN).build());
        b.addEdge(edge(E_STITCH, BOUNDARY_W, BOUNDARY_E, line(511, 64, 0, 512, 64, 0)).source(RoadEdgeSource.STITCH)
            .build());
        b.addEdge(edge(E_E_BOUNDARY, E, BOUNDARY_W, line(200, 64, 0, 511, 64, 0)).profile(PROFILE_MAIN)
            .street(STREET_KEEP).build());
        b.addEdge(edge(E_BOUNDARY_MILL, BOUNDARY_E, MILL, line(512, 64, 0, 600, 64, 0)).profile(PROFILE_MAIN)
            .street(STREET_KEEP).build());
        b.addEdge(edge(E_TUNNEL, C, TUNNEL_END,
            List.of(p(100, 64, 100), p(120, 58, 100), p(180, 58, 100), p(200, 64, 100))).length(102)
            .profile(PROFILE_ROAD).build());
        b.addEdge(edge(E_BRIDGE, BRIDGE_N, BRIDGE_S, line(50, 72, -30, 50, 72, 30)).profile(PROFILE_ROAD).build());
        return b;
    }

    static RoadNode node(int id, int x, int y, int z, RoadNodeKind kind, String name, int component) {
        return new RoadNode(id, x, y, z, kind, name, component);
    }

    static int[] p(int x, int y, int z) {
        return new int[] {x, y, z};
    }

    static List<int[]> line(int x0, int y0, int z0, int x1, int y1, int z1) {
        return List.of(p(x0, y0, z0), p(x1, y1, z1));
    }

    static double polylineLength(List<int[]> pts) {
        double sum = 0;
        for (int i = 1; i < pts.size(); i++) {
            sum += EdgePolyline.distance(pts.get(i - 1), pts.get(i));
        }
        return sum;
    }

    static EdgeBuilder edge(int id, int from, int to, List<int[]> geometry) {
        return new EdgeBuilder(id, from, to, geometry);
    }

    /** Fluent edge with the download's defaults (Detected, Ok, width 3, multiplier 1). */
    static final class EdgeBuilder {
        private final int id, from, to;
        private final List<int[]> geometry;
        private double length;
        private OptionalInt profile = OptionalInt.empty();
        private OptionalInt street = OptionalInt.empty();
        private double costMultiplier = 1.0;
        private final Set<RoadEdgeFlag> flags = EnumSet.noneOf(RoadEdgeFlag.class);
        private final List<Integer> gates = new ArrayList<>();
        private final List<Integer> domains = new ArrayList<>();
        private final List<String> regions = new ArrayList<>();
        private RoadEdgeSource source = RoadEdgeSource.DETECTED;
        private boolean stale;

        EdgeBuilder(int id, int from, int to, List<int[]> geometry) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.geometry = geometry;
            this.length = polylineLength(geometry);
        }

        EdgeBuilder length(double length) {
            this.length = length;
            return this;
        }

        EdgeBuilder profile(int profileId) {
            this.profile = OptionalInt.of(profileId);
            return this;
        }

        EdgeBuilder street(int streetId) {
            this.street = OptionalInt.of(streetId);
            return this;
        }

        EdgeBuilder cost(double multiplier) {
            this.costMultiplier = multiplier;
            return this;
        }

        EdgeBuilder flags(RoadEdgeFlag... more) {
            flags.addAll(List.of(more));
            return this;
        }

        EdgeBuilder gates(int... doorIds) {
            for (int d : doorIds) {
                gates.add(d);
            }
            return this;
        }

        EdgeBuilder region(String regionId, int domainId) {
            regions.add(regionId);
            domains.add(domainId);
            return this;
        }

        EdgeBuilder source(RoadEdgeSource source) {
            this.source = source;
            return this;
        }

        EdgeBuilder stale() {
            this.stale = true;
            return this;
        }

        RoadEdge build() {
            return new RoadEdge(id, from, to, geometry, length, 3.0, profile, street, costMultiplier, flags, gates,
                domains, regions, source, stale);
        }
    }
}
