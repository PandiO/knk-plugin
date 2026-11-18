package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.navigation.PlazaLinePreviews;
import net.knightsandkings.knk.core.navigation.shape.CorridorShaper;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.NavWorld;
import net.knightsandkings.knk.core.navigation.shape.PlazaCrossing;
import net.knightsandkings.knk.core.navigation.shape.PlazaCurve;
import net.knightsandkings.knk.core.navigation.shape.PlazaLine;
import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshot;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.npc.nav.BukkitNavWorld;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;
import net.knightsandkings.knk.paper.scenario.SnapshotReplay;

/**
 * Off-road navigation phase 7 step 1 (PandiO/knk-workspace#712, DESIGN § 8.9, O16): the players' plaza line on a real
 * plaza of Cinix, from the developer's snapshots of 2026-11-17 (real blocks and WorldGuard regions, the stored road
 * line, the road graph round the plaza). For the stored route's own crossing and for every pair of the roads that meet at
 * the plaza node it works out the line with the design defaults ({@code plaza-pull} 0.35, {@code wall-clearance} 0.8,
 * {@code corridor-half-width} 4, {@code plaza-corridor-blocks} 16) and logs each one with its length against the chord
 * and the right angle at the node, and why a curve was rejected. Checks: the blocks were placed, every line was worked
 * out, and every line found stays over walkable floor and at least the hard minimum from a wall, post or drop on the
 * live blocks. The developer judges the lines themselves in game ({@code /knk road plazaline}). Registered once per
 * plaza: Brink (a junction, measured), the square on the quarry-to-keep route (designed, radius 8) and Northern Gate
 * Square (designed, radius 12).
 */
public final class PlazaLineRealPlazaScenario implements Scenario<ScenarioScene>, SizedScene {

    private final String name;
    private final String plaza;
    private final String snapshot;
    private final int nodeId;
    private final int[] frame;
    private final ScenarioScene.Box box;
    private SnapshotReplay replay;

    /**
     * @param name     the scenario's name
     * @param plaza    the plaza as the developer knows it
     * @param snapshot the committed snapshot in the jar's {@value SnapshotReplay#RESOURCE_FOLDER}
     * @param nodeId   the plaza's road node in the snapshot
     * @param frame    the real block at the scene's 0 0 0
     * @param box      the scene box (the plaza with a few blocks of each road)
     */
    PlazaLineRealPlazaScenario(String name, String plaza, String snapshot, int nodeId, int[] frame,
                               ScenarioScene.Box box) {
        this.name = name;
        this.plaza = plaza;
        this.snapshot = snapshot;
        this.nodeId = nodeId;
        this.frame = frame.clone();
        this.box = box;
    }

    /** Brink, the junction below the keep's stair house (node 6 at 1418 48 -520, no designed radius). */
    public static PlazaLineRealPlazaScenario brink() {
        return new PlazaLineRealPlazaScenario("plaza-line-brink", "Brink", NpcSnapshotReplayScenario.SNAPSHOT, 6,
            new int[] {1394, 36, -544}, new ScenarioScene.Box(49, 40, 49));
    }

    /** The square on the quarry-to-keep route in Cinix (node 39 at 1418 42 -462, designed radius 8). */
    public static PlazaLineRealPlazaScenario cinixSouthSquare() {
        return new PlazaLineRealPlazaScenario("plaza-line-cinix-south-square", "the square on the quarry-to-keep route",
            "quarry-to-keep-south-square.zip", 39, new int[] {1394, 36, -486}, new ScenarioScene.Box(49, 31, 49));
    }

    /** Northern Gate Square in Cinix (node 37 at 1390 42 -576, designed radius 12). */
    public static PlazaLineRealPlazaScenario cinixNorthernGateSquare() {
        return new PlazaLineRealPlazaScenario("plaza-line-cinix-northern-gate-square", "Northern Gate Square",
            "northern-wearway-gate-square.zip", 37, new int[] {1362, 36, -604}, new ScenarioScene.Box(57, 31, 57));
    }

    private SnapshotReplay replay() {
        if (replay == null) {
            replay = SnapshotReplay.fromResource(snapshot).at(frame[0], frame[1], frame[2]);
        }
        return replay;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return "The players' plaza line on " + plaza + " (real blocks from a snapshot): the route's crossing and every "
            + "pair of roads, logged against the chord and the right angle";
    }

    @Override
    public ScenarioScene.Box box() {
        return box;
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        SnapshotReplay r = replay();
        String w = scene.world().getName();
        AtomicReference<NavWorld> world = new AtomicReference<>();
        AtomicReference<String> regions = new AtomicReference<>("");
        List<PlazaLinePreviews.Line> lines = new ArrayList<>();
        PlazaCurve.Options options = PlazaCurve.Options.defaults(4, 0.8);
        plan.check("the snapshot's blocks are placed", () -> {
            SnapshotReplay.Placed placed = r.placeBlocks(scene);
            return placed.placed() > 0 ? null : "nothing placed: " + placed.describe();
        })
            .act("build the domains' regions; read them as the NPC rule checks do", () -> {
                regions.set(r.buildRegions(scene).size() + " regions");
                try {
                    world.set(scene.services().npcRules());
                } catch (RuntimeException e) {
                    world.set(new BukkitNavWorld(null));
                    regions.set(regions.get() + ", not read (" + e.getMessage() + ")");
                }
            })
            .check("the plaza node is in the snapshot's road graph", () -> node(r).isPresent() ? null
                : "no node " + nodeId + " in " + snapshot)
            .check("the route's crossing and every pair of roads get their plaza line worked out", () -> {
                RoadNode node = node(r).orElseThrow();
                List<PlazaLinePreviews.Pass> passes = new ArrayList<>();
                List<double[]> route = new ArrayList<>();
                for (NavPoint p : r.line(scene)) {
                    route.add(new double[] {p.x(), p.y(), p.z()});
                }
                RoadNode inScene = sceneNode(scene, r, node);
                PlazaCrossing crossing = PlazaLinePreviews.crossingOf(w, inScene);
                passes.add(new PlazaLinePreviews.Pass(plaza + " on the route " + r.snapshot().manifest().route(),
                    PlazaLinePreviews.feetOf(w, route), crossing));
                passes.addAll(PlazaLinePreviews.throughNode(roads(scene, r), inScene, 40));
                lines.addAll(PlazaLinePreviews.compute(world.get(), passes, options));
                for (PlazaLinePreviews.Line line : lines) {
                    log(scene, PlazaLinePreviews.describe(line));
                }
                double[] origin = r.real(scene, new NavPoint(w, 0, 0, 0));
                log(scene, String.format(Locale.ROOT, "%s: %d lines (%s), regions: %s; scene positions in the lines "
                    + "above are real + (%.0f, %.0f) in x and z", plaza, lines.size(), summary(lines), regions.get(),
                    -origin[0], -origin[2]));
                return lines.size() == passes.size() ? null : "not every pass was worked out";
            })
            .check("every line found stays over walkable floor and clear of walls by the hard minimum (live blocks)",
                () -> {
                    BukkitNavWorld live = new BukkitNavWorld(null);
                    for (PlazaLinePreviews.Line line : lines) {
                        if (!line.result().found()) {
                            continue;
                        }
                        // the road's own cells stand on the road graph's floor (the corridor's rule, shared with the
                        // NPC side): on a stair the graph may name a block the walk rule does not call a floor
                        java.util.Map<Long, Integer> roadFloors = new java.util.HashMap<>();
                        for (NavPoint f : line.pass().feet()) {
                            roadFloors.put(key(f.blockX(), f.blockZ()), f.blockY() - 1);
                        }
                        List<PlazaLine.Point> pts = line.result().line();
                        for (int k = 1; k < pts.size(); k++) {
                            PlazaLine.Point a = pts.get(k - 1);
                            PlazaLine.Point b = pts.get(k);
                            int lo = Math.min(a.floor(), b.floor());
                            int hi = Math.max(a.floor(), b.floor());
                            int n = Math.max(1, (int) Math.ceil(Math.hypot(b.x() - a.x(), b.z() - a.z()) / 0.25));
                            for (int i = 0; i <= n; i++) {
                                double t = (double) i / n;
                                double x = a.x() + (b.x() - a.x()) * t;
                                double z = a.z() + (b.z() - a.z()) * t;
                                Integer floor = roadFloors.getOrDefault(key((int) Math.floor(x), (int) Math.floor(z)),
                                    floorBetween(live, w, x, z, lo - 1, hi + 1));
                                if (floor == null) {
                                    return line.pass().label() + ": no floor between " + (lo - 1) + " and " + (hi + 1)
                                        + " at " + WalkTrailStairhouseScenario.describe(real(scene, r,
                                        new double[] {x, lo, z}));
                                }
                                double[] p = {x, floor, z};
                                double d = clearance(live, w, roadFloors, x, z, floor);
                                if (d < CorridorShaper.HARD_MINIMUM - 0.05) {
                                    return String.format(Locale.ROOT, "%s: %.2f from an obstacle at %s (%s)",
                                        line.pass().label(), d, WalkTrailStairhouseScenario.describe(real(scene, r, p)),
                                        around(live, w, scene, r, p));
                                }
                            }
                        }
                    }
                    return null;
                })
            .cleanup("remove the snapshot's regions", () -> r.removeRegions(scene));
    }

    private java.util.Optional<RoadNode> node(SnapshotReplay r) {
        for (NavSnapshot.Node n : r.snapshot().roads().nodes()) {
            if (n.id() == nodeId) {
                return java.util.Optional.of(roadNode(n, n.x(), n.y(), n.z()));
            }
        }
        return java.util.Optional.empty();
    }

    private static RoadNode roadNode(NavSnapshot.Node n, int x, int y, int z) {
        RoadNodeKind kind;
        try {
            kind = RoadNodeKind.valueOf(n.kind());
        } catch (IllegalArgumentException | NullPointerException e) {
            kind = RoadNodeKind.JUNCTION;
        }
        return new RoadNode(n.id(), x, y, z, kind, n.name(), 0, false, n.plazaRadius());
    }

    private static RoadNode sceneNode(ScenarioScene scene, SnapshotReplay r, RoadNode real) {
        NavPoint p = r.inScene(scene, new double[] {real.x(), real.y(), real.z()});
        return new RoadNode(real.id(), (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()),
            real.kind(), real.name(), 0, false, real.plazaRadius());
    }

    /** The snapshot's road graph in the scene (the nodes and the edges through the box). */
    private static RoadNetworkSnapshot roads(ScenarioScene scene, SnapshotReplay r) {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder(scene.world().getName());
        for (NavSnapshot.Node n : r.snapshot().roads().nodes()) {
            b.addNode(sceneNode(scene, r, roadNode(n, n.x(), n.y(), n.z())));
        }
        java.util.Set<Integer> known = new java.util.HashSet<>();
        r.snapshot().roads().nodes().forEach(n -> known.add(n.id()));
        for (NavSnapshot.Edge e : r.snapshot().roads().edges()) {
            if (!known.contains(e.fromNode()) || !known.contains(e.toNode()) || e.geometry().size() < 2) {
                continue;
            }
            List<int[]> geometry = new ArrayList<>();
            for (int[] p : e.geometry()) {
                NavPoint q = r.inScene(scene, new double[] {p[0], p[1], p[2]});
                geometry.add(new int[] {(int) Math.floor(q.x()), (int) Math.floor(q.y()), (int) Math.floor(q.z())});
            }
            b.addEdge(new RoadEdge(e.id(), e.fromNode(), e.toNode(), geometry, Math.max(e.length(), 1e-6),
                Math.max(e.width(), 1), OptionalInt.empty(), OptionalInt.empty(), 1.0, Set.of(), List.of(), List.of(),
                List.of(), RoadEdgeSource.DETECTED, false));
        }
        return b.build();
    }

    /**
     * The distance from {@code (x, z)} on {@code floor} to the nearest column with no floor within a step of it (a wall,
     * a post, a drop), at most 2, on the live blocks; a cell of the road counts with the road graph's floor.
     */
    private static double clearance(BukkitNavWorld live, String w, java.util.Map<Long, Integer> roadFloors, double x,
                                    double z, int floor) {
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        double best = 2;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int ox = cx + dx;
                int oz = cz + dz;
                double d = Math.hypot(Math.max(Math.max(ox - x, 0), x - (ox + 1)),
                    Math.max(Math.max(oz - z, 0), z - (oz + 1)));
                if (d >= best) {
                    continue;
                }
                Integer road = roadFloors.get(key(ox, oz));
                boolean open = (road != null && Math.abs(road - floor) <= 1)
                    || live.isStandableFloor(w, ox, floor, oz) || live.isStandableFloor(w, ox, floor - 1, oz)
                    || live.isStandableFloor(w, ox, floor + 1, oz);
                if (!open) {
                    best = d;
                }
            }
        }
        return best;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /** The standable floor of the column under {@code (x, z)} from {@code lo} to {@code hi}, nearest {@code lo}. */
    private static Integer floorBetween(BukkitNavWorld live, String w, double x, double z, int lo, int hi) {
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        for (int f = lo; f <= hi; f++) {
            if (live.isStandableFloor(w, cx, f, cz)) {
                return f;
            }
        }
        return null;
    }

    /** The standable floors (38 to 50 above the point's floor less 4) of the columns round a point, for a failure. */
    private static String around(BukkitNavWorld live, String w, ScenarioScene scene, SnapshotReplay r, double[] p) {
        StringBuilder out = new StringBuilder("floors round it:");
        int cx = (int) Math.floor(p[0]);
        int cz = (int) Math.floor(p[2]);
        int floor = (int) Math.floor(p[1]);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                List<Integer> floors = new ArrayList<>();
                for (int f = floor - 3; f <= floor + 3; f++) {
                    if (live.isStandableFloor(w, cx + dx, f, cz + dz)) {
                        floors.add(f - (int) Math.round(scene.worldY(0)) + r.frame()[1]);
                    }
                }
                double[] real = real(scene, r, new double[] {cx + dx, floor, cz + dz});
                out.append(String.format(Locale.ROOT, " %.0f %.0f %s", real[0], real[2], floors));
            }
        }
        return out.toString();
    }

    private static String summary(List<PlazaLinePreviews.Line> lines) {
        int[] counts = new int[PlazaCurve.Kind.values().length];
        for (PlazaLinePreviews.Line l : lines) {
            counts[l.result().kind().ordinal()]++;
        }
        List<String> out = new ArrayList<>();
        for (PlazaCurve.Kind k : PlazaCurve.Kind.values()) {
            if (counts[k.ordinal()] > 0) {
                out.add(counts[k.ordinal()] + " " + k.name().toLowerCase(Locale.ROOT).replace('_', ' '));
            }
        }
        return String.join(", ", out);
    }

    private static double[] real(ScenarioScene scene, SnapshotReplay r, double[] p) {
        double[] q = r.real(scene, new NavPoint(scene.world().getName(), p[0], p[1], p[2]));
        return q;
    }

    private void log(ScenarioScene scene, String line) {
        scene.services().plugin().getLogger().info("[" + name + "] " + line);
    }
}
