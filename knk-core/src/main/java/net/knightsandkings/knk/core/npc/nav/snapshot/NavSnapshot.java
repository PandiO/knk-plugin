package net.knightsandkings.knk.core.npc.nav.snapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What {@code /knk npc nav snapshot <route>} writes (link B1, PandiO/knk-workspace#670; chain report decisions 33 and
 * 35.2): a real route of the server with the world and the rules around it, so a scenario can replay it in CI. One zip
 * holds this as JSON files ({@link NavSnapshotZip}) beside the region files ({@code .mca}) the route's box touches.
 * Engine- and Bukkit-free: the Paper side fills it from the server's caches.
 *
 * @param manifest  the box, the world, the time, the plugin version and the counts
 * @param route     the stored route line and its stretches
 * @param roads     the road graph's nodes and edges inside the box
 * @param domains   every Town, District, Structure and gate whose WorldGuard region touches the box (whole shapes)
 * @param settings  the NPC navigation settings in effect at the route's start, with the layer each came from
 */
public record NavSnapshot(Manifest manifest, RouteLine route, Roads roads, List<Domain> domains,
                          List<Setting> settings) {

    /** The format of the JSON files; raised when a field changes meaning. */
    public static final int FORMAT = 1;

    public NavSnapshot {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(route, "route");
        roads = roads == null ? new Roads(List.of(), List.of()) : roads;
        domains = domains == null ? List.of() : List.copyOf(domains);
        settings = settings == null ? List.of() : List.copyOf(settings);
    }

    /**
     * @param format        {@link #FORMAT}
     * @param route         the route's name ({@code npc-routes.yml})
     * @param world         the world's name
     * @param boxMin        the box's lowest block (x, y, z), inclusive
     * @param boxMax        the box's highest block (x, y, z), inclusive
     * @param takenAt       ISO-8601 time the snapshot was taken (UTC)
     * @param pluginVersion the plugin's version stamp ({@code 0.1.0+<sha>})
     * @param counts        what is in the zip: route points, stretches, nodes, edges, domains, settings, region files
     * @param regionFiles   the region files in the zip, by their path inside it ({@code world/...})
     * @param notes         anything the reader should know (a region file that could not be read, the save's result)
     */
    public record Manifest(int format, String route, String world, int[] boxMin, int[] boxMax, String takenAt,
                           String pluginVersion, Map<String, Integer> counts, List<String> regionFiles,
                           List<String> notes) {
        public Manifest {
            counts = counts == null ? Map.of() : Map.copyOf(counts);
            regionFiles = regionFiles == null ? List.of() : List.copyOf(regionFiles);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /**
     * The route as the NPC navigation gets it: the road plan from {@code from} to {@code to} for an NPC with the
     * default access profile.
     *
     * @param name      the route's name
     * @param from      the stored start (floor point x, y, z)
     * @param to        the stored end
     * @param status    the plan's status (FOUND, BLOCKED, ...)
     * @param message   the plan's message, if any
     * @param line      every waypoint of the road line (floor points x, y, z)
     * @param stretches the stretches as the route follower hands them out from the start with the settings'
     *                  {@code stretch-blocks}, each a list of waypoints (see {@link #stretchesNote})
     * @param stretchesNote how the stretches were made
     * @param gates     the gate crossings on the line: door id and the point where its road stretch ends
     * @param plazas    the junctions on the line that may be plazas: centre (x, y, z) and radius (0: measured)
     */
    public record RouteLine(String name, double[] from, double[] to, String status, String message,
                            List<double[]> line, List<List<double[]>> stretches, String stretchesNote,
                            List<GateCrossing> gates, List<Plaza> plazas) {
        public RouteLine {
            line = line == null ? List.of() : List.copyOf(line);
            stretches = stretches == null ? List.of() : List.copyOf(stretches);
            gates = gates == null ? List.of() : List.copyOf(gates);
            plazas = plazas == null ? List.of() : List.copyOf(plazas);
        }
    }

    public record GateCrossing(int doorId, double[] at) {
    }

    public record Plaza(double[] centre, int radius) {
    }

    /** The road graph inside the box: nodes in it, and edges with any geometry point in it. */
    public record Roads(List<Node> nodes, List<Edge> edges) {
        public Roads {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    /**
     * A road node.
     *
     * @param kind        ENDPOINT, JUNCTION, ...
     * @param plazaRadius a designed plaza's radius, 0 for none (plaza junctions are the JUNCTION nodes)
     */
    public record Node(int id, int x, int y, int z, String kind, String name, int plazaRadius) {
    }

    /**
     * A road edge, whole (not clipped to the box).
     *
     * @param geometry    the floor blocks of the edge's centre line (x, y, z)
     * @param flags       the edge's flags by name
     * @param gateDoorIds the gate doors on it
     * @param regionIds   the WorldGuard regions on its centre line
     * @param domainIds   the domains on it
     */
    public record Edge(int id, int fromNode, int toNode, List<int[]> geometry, double length, double width,
                       String roadClass, List<String> flags, List<Integer> gateDoorIds, List<String> regionIds,
                       List<Integer> domainIds) {
        public Edge {
            geometry = geometry == null ? List.of() : List.copyOf(geometry);
            flags = flags == null ? List.of() : List.copyOf(flags);
            gateDoorIds = gateDoorIds == null ? List.of() : List.copyOf(gateDoorIds);
            regionIds = regionIds == null ? List.of() : List.copyOf(regionIds);
            domainIds = domainIds == null ? List.of() : List.copyOf(domainIds);
        }
    }

    /**
     * A domain along the route: its WorldGuard region, whole, and its rules (chain report decision 35.2).
     *
     * @param regionId   the WorldGuard region's id
     * @param domainId   the domain's id (null when the region has no domain in the plugin's cache)
     * @param name       the domain's name
     * @param type       the domain's type (town, district, structure, gate); null without a domain
     * @param shape      {@code cuboid} or {@code polygon}
     * @param points     cuboid: its lowest and highest corner (x, y, z); polygon: its points (x, z) in order
     * @param minY       the region's lowest y
     * @param maxY       the region's highest y
     * @param parent     the parent region's id, or null
     * @param priority   the region's priority
     * @param entryFlag  the region's {@code knk-allow-entry} flag (allow, deny, or null when unset)
     * @param exitFlag   the region's {@code knk-allow-exit} flag
     * @param allowEntry the domain's AllowEntry in the plugin's cache (null: unknown)
     * @param allowExit  the domain's AllowExit in the plugin's cache
     * @param gateDoors  for a gate: its doors and whether each is open or closed
     */
    public record Domain(String regionId, Integer domainId, String name, String type, String shape,
                         List<int[]> points, int minY, int maxY, String parent, int priority, String entryFlag,
                         String exitFlag, Boolean allowEntry, Boolean allowExit, List<GateDoor> gateDoors) {
        public Domain {
            Objects.requireNonNull(regionId, "regionId");
            points = points == null ? List.of() : List.copyOf(points);
            gateDoors = gateDoors == null ? List.of() : List.copyOf(gateDoors);
        }

        public boolean isGate() {
            return "gate".equalsIgnoreCase(type);
        }
    }

    /**
     * A gate door and its state.
     *
     * @param state the door's animation state as the gate manager has it (OPEN, CLOSED, OPENING, CLOSING, ...)
     * @param open  whether the door is open now (an opening door is not yet open)
     */
    public record GateDoor(int doorId, String name, String state, boolean open) {
    }

    /**
     * One NPC navigation setting as it is in effect at the route's start.
     *
     * @param layer the layer it came from (default, global, world, region, template, assignment) and its scope
     */
    public record Setting(String key, String value, String layer) {
    }
}
