package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.navigation.shape.NavGeometry;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;

/**
 * Link G1 (PandiO/knk-workspace#814): a destination whose nearest road end is a dead end (#780's case), with
 * {@code npc.navigation.road-goal-reachable} on. Scene blocks, the ground's floor at {@link #GROUND}:
 * <ol>
 *   <li>Road A climbs a ramp (x 8..13) onto a causeway 6 blocks up (x 14..54, z 31..33, solid down to the ground) and
 *       ends at its east end, 4 blocks from the destination (x 58, z 32) and 6 above it: the road point nearest the
 *       destination, today's goal. No walk gets down: a drop of 6 is more than {@code max-drop}, and the ramp is
 *       outside the walk's box (the capture margin is 16 blocks round the road's end, also 24 blocks back).</li>
 *   <li>Road B runs south from the junction (x 5, z 32) and east along z 60 to x 40: 33 blocks from the destination
 *       across open ground.</li>
 * </ol>
 * One NPC, then a group of 5 in a column, go from the west end (x 2, z 32) to the destination, on the plugin's router
 * over that road graph ({@link ScenarioNpcs#startOnRoads}). Each fails when the leader does not arrive in time, when
 * an NPC goes up onto the causeway (past x {@link #CAUSEWAY_CHECK_X}), when no road goal choice moved the goal, or on
 * a stuck spell; the group also when a follower is lost or not at the destination. With
 * {@code -Dknk.scenario.roadGoalOff=true} the key is off: the route of today's {@code next} (the before run).
 */
public final class NpcRoadGoalDeadEndScenario implements Scenario<ScenarioScene>, SizedScene {

    /** The before run of PandiO/knk-workspace#814: {@code road-goal-reachable} off. */
    public static final String ROAD_GOAL_OFF = "knk.scenario.roadGoalOff";

    static final ScenarioScene.Box BOX = new ScenarioScene.Box(64, 24, 64);
    static final int GROUND = 2;
    static final int UP = 6;
    static final int WALK_TICKS = 90 * 20;
    static final int GROUP_TICKS = 120 * 20;
    static final int SETTLE_TICKS = 15 * 20;
    static final int FOLLOWERS = 4;
    /** An NPC on the causeway east of this has walked the dead end. */
    static final int CAUSEWAY_CHECK_X = 24;
    /**
     * A follower within this many blocks of the destination has arrived with its group: a column of 4 at 1.5 blocks
     * ends with its last follower about 7.5 blocks back along the path.
     */
    static final double NEAR = 10;

    @Override
    public String name() {
        return "npc-road-goal-dead-end";
    }

    @Override
    public String description() {
        return "A destination whose nearest road end is a dead end on a causeway: an NPC and a group of 5 arrive by "
            + "another road (road-goal-reachable)";
    }

    @Override
    public ScenarioScene.Box box() {
        return BOX;
    }

    static NavigationSettings settings() {
        return NavigationSettings.builder().global(NavigationSettings.Overrides
            .of(NavigationSettings.ROAD_GOAL_REACHABLE, !Boolean.getBoolean(ROAD_GOAL_OFF))).build();
    }

    private static NavPoint feet(ScenarioScene scene, int x, int floorY, int z) {
        return new NavPoint(scene.world().getName(), scene.worldX(x) + 0.5, scene.worldY(floorY + 1),
            scene.worldZ(z) + 0.5);
    }

    private static NavPoint goal(ScenarioScene scene) {
        return feet(scene, 58, GROUND, 32);
    }

    /** Whether a feet position is up on the causeway east of {@link #CAUSEWAY_CHECK_X}. */
    private static boolean onTheCauseway(ScenarioScene scene, NavPoint at) {
        return at.x() - scene.worldX(0) > CAUSEWAY_CHECK_X && at.y() >= scene.worldY(GROUND + UP - 1);
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        List<String> upThere = new ArrayList<>();
        plan.act("build the scene", () -> build(scene))
            .act("start NPC navigation on the scene's road graph", () -> {
                npcs.set(ScenarioNpcs.startOnRoads(scene, network(scene), settings(), AccessRules.OPEN));
                npcs.get().everyTick(() -> {
                    for (NpcMover mover : npcs.get().movers()) {
                        NavPoint at = mover.position().orElse(null);
                        if (at != null && onTheCauseway(scene, at) && upThere.size() < 10
                            && upThere.stream().noneMatch(f -> f.startsWith(mover.name() + " "))) {
                            upThere.add(mover.name() + " at " + NavGeometry.block(at));
                        }
                    }
                });
            })
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            });

        AtomicReference<NavigationResult> result = new AtomicReference<>();
        long[] t = {0, -1};
        plan.act("one NPC walks from the west end to the destination", () -> {
                NpcMover npc = npcs.get().spawn(feet(scene, 2, GROUND, 32));
                t[0] = npcs.get().ticks();
                if (!npcs.get().service().navigate(npc.id(), goal(scene), r -> {
                    result.set(r);
                    t[1] = npcs.get().ticks() - t[0];
                })) {
                    throw new ScenarioAssertionError("the navigation service refused the order");
                }
            })
            .await("the trip ends or the time is up", WALK_TICKS + 20,
                () -> result.get() != null || npcs.get().ticks() - t[0] >= WALK_TICKS)
            .check("the NPC arrived by the other road, never on the dead end", () -> {
                List<String> failures = new ArrayList<>();
                report(npcs.get(), "one NPC", result.get(), t[1], upThere);
                if (!(result.get() instanceof NavigationResult.Arrived)) {
                    failures.add("the NPC did not arrive: " + (result.get() == null ? "still walking after "
                        + WALK_TICKS / 20 + " s at " + npcs.get().movers().get(0).position().map(NavGeometry::block)
                        .orElse("-") : result.get()));
                }
                if (!upThere.isEmpty()) {
                    failures.add("went up onto the dead end: " + upThere);
                }
                if (npcs.get().service().roadGoalsMoved() == 0) {
                    failures.add("no road goal choice moved the goal (" + npcs.get().service().roadGoalChoices()
                        + " choices)");
                }
                long stuck = stuckSpells(npcs.get());
                if (stuck > 0) {
                    failures.add(stuck + " stuck spells on the way");
                }
                npcs.get().remove(new ArrayList<>(npcs.get().movers()));
                return failures.isEmpty() ? null : String.join("; ", failures) + npcs.get().tail(20);
            });
        group(plan, scene, npcs, upThere);
    }

    /** A group of 5 in a column: the leader's road goal is the group's; every follower ends at the destination. */
    private void group(ScenarioPlan plan, ScenarioScene scene, AtomicReference<ScenarioNpcs> npcs,
                       List<String> upThere) {
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        List<NpcMover> members = new ArrayList<>();
        long[] t = {0, -1};
        long[] movedBefore = {0};
        plan.act("a group of 5 walks the same way", () -> {
                upThere.clear();
                movedBefore[0] = npcs.get().service().roadGoalsMoved();
                members.add(npcs.get().spawn(feet(scene, 2, GROUND, 32)));
                for (int i = 0; i < FOLLOWERS; i++) {
                    members.add(npcs.get().spawn(feet(scene, 2 + (i % 2), GROUND, 28 - 2 * (i / 2))));
                }
                t[0] = npcs.get().ticks();
                group.set(npcs.get().group(members, Formation.Type.COLUMN));
                group.get().navigate(goal(scene), r -> {
                    result.set(r);
                    t[1] = npcs.get().ticks() - t[0];
                });
            })
            .await("the leader arrived " + SETTLE_TICKS / 20 + " s ago, or the time is up", GROUP_TICKS + 20, () -> {
                long now = npcs.get().ticks();
                return (result.get() != null && now - t[0] - t[1] >= SETTLE_TICKS) || now - t[0] >= GROUP_TICKS;
            })
            .check("the group took its leader's road goal", () -> {
                List<String> failures = new ArrayList<>();
                report(npcs.get(), "a group of 5", result.get(), t[1], upThere);
                if (!(result.get() instanceof NavigationResult.Arrived)) {
                    failures.add("the leader did not arrive: " + result.get());
                }
                NavPoint goal = goal(scene);
                for (NpcMover m : members.subList(1, members.size())) {
                    NavPoint at = m.position().orElse(null);
                    if (at == null || at.horizontalDistance(goal) > NEAR || Math.abs(at.y() - goal.y()) > 2) {
                        failures.add(m.name() + " is not at the destination: "
                            + (at == null ? "-" : NavGeometry.block(at)));
                    }
                }
                if (!upThere.isEmpty()) {
                    failures.add("went up onto the dead end: " + upThere);
                }
                if (npcs.get().service().roadGoalsMoved() <= movedBefore[0]) {
                    failures.add("the leader's road goal was not moved");
                }
                if (group.get().lost() > 0) {
                    failures.add(group.get().lost() + " followers lost");
                }
                long stuck = stuckSpells(npcs.get());
                if (stuck > 0) {
                    failures.add(stuck + " stuck spells in both walks");
                }
                return failures.isEmpty() ? null : String.join("; ", failures) + npcs.get().tail(20);
            });
    }

    // ---- the scene ----

    private static void build(ScenarioScene scene) {
        fill(scene, 0, GROUND, 0, 63, GROUND, 63, Material.GRASS_BLOCK);
        // the causeway, solid down to the ground
        fill(scene, 14, GROUND + 1, 31, 54, GROUND + UP, 33, Material.STONE);
        // the ramp: one stair per block, x 8..13, solid below
        for (int k = 0; k < UP; k++) {
            int x = 8 + k;
            if (k > 0) {
                fill(scene, x, GROUND + 1, 31, x, GROUND + k, 33, Material.STONE);
            }
            for (int z = 31; z <= 33; z++) {
                scene.block(x, GROUND + 1 + k, z).setBlockData(Bukkit.createBlockData(Material.STONE_BRICK_STAIRS,
                    data -> ((Stairs) data).setFacing(BlockFace.EAST)), false);
            }
        }
        // the roads on the ground, drawn for a person watching
        fill(scene, 1, GROUND, 32, 7, GROUND, 32, Material.DIRT_PATH);
        fill(scene, 5, GROUND, 32, 5, GROUND, 60, Material.DIRT_PATH);
        fill(scene, 5, GROUND, 60, 40, GROUND, 60, Material.DIRT_PATH);
        scene.block(58, GROUND, 32).setType(Material.GOLD_BLOCK, false);
    }

    private static void fill(ScenarioScene scene, int x0, int y0, int z0, int x1, int y1, int z1, Material m) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    scene.block(x, y, z).setType(m, false);
                }
            }
        }
    }

    /** The road graph (floor points): a stub west of the junction, road A onto the causeway, road B round the south. */
    static RoadNetworkSnapshot network(ScenarioScene scene) {
        String world = scene.world().getName();
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder(world);
        b.addProfile(new RoadNetworkSnapshot.Profile(1, "Scenario road", RoadClass.MAIN, 1.0));
        b.addNode(node(scene, 1, 1, GROUND, 32, RoadNodeKind.ENDPOINT));
        b.addNode(node(scene, 2, 5, GROUND, 32, RoadNodeKind.JUNCTION));
        b.addNode(node(scene, 3, 54, GROUND + UP, 32, RoadNodeKind.ENDPOINT));
        b.addNode(node(scene, 4, 40, GROUND, 60, RoadNodeKind.ENDPOINT));
        b.addEdge(edge(scene, 1, 1, 2, new int[][] {{1, GROUND, 32}, {5, GROUND, 32}}));
        b.addEdge(edge(scene, 2, 2, 3, new int[][] {{5, GROUND, 32}, {7, GROUND, 32}, {8, GROUND + 1, 32},
            {13, GROUND + UP, 32}, {54, GROUND + UP, 32}}));
        b.addEdge(edge(scene, 3, 2, 4, new int[][] {{5, GROUND, 32}, {5, GROUND, 60}, {40, GROUND, 60}}));
        return b.build();
    }

    private static RoadNode node(ScenarioScene scene, int id, int x, int y, int z, RoadNodeKind kind) {
        return new RoadNode(id, scene.worldX(x), scene.worldY(y), scene.worldZ(z), kind, null, 1);
    }

    private static RoadEdge edge(ScenarioScene scene, int id, int from, int to, int[][] points) {
        List<int[]> geometry = new ArrayList<>();
        double length = 0;
        for (int i = 0; i < points.length; i++) {
            geometry.add(new int[] {scene.worldX(points[i][0]), scene.worldY(points[i][1]), scene.worldZ(points[i][2])});
            if (i > 0) {
                length += Math.sqrt(Math.pow(points[i][0] - points[i - 1][0], 2)
                    + Math.pow(points[i][1] - points[i - 1][1], 2) + Math.pow(points[i][2] - points[i - 1][2], 2));
            }
        }
        return new RoadEdge(id, from, to, geometry, length, 3, OptionalInt.of(1), OptionalInt.empty(), 1.0,
            EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false);
    }

    private static long stuckSpells(ScenarioNpcs npcs) {
        return npcs.metrics().total(Event.STUCK_ENGINE) + npcs.metrics().total(Event.STUCK_NO_PROGRESS);
    }

    private void report(ScenarioNpcs npcs, String phase, NavigationResult result, long took, List<String> upThere) {
        String line = String.format(Locale.ROOT, "KNK-NPC-REPORT %s %s: %s; road goal choices %d, moved %d; off-road "
                + "walks %d, in hops %d; stuck spells %d; on the dead end %s", name(), phase,
            result == null ? "still walking" : result.getClass().getSimpleName() + String.format(Locale.ROOT,
                " after %.1f s", took / 20.0), npcs.service().roadGoalChoices(), npcs.service().roadGoalsMoved(),
            npcs.service().offRoadWalks(), npcs.service().offRoadHops(), stuckSpells(npcs),
            upThere.isEmpty() ? "never" : upThere.toString());
        Bukkit.getLogger().info(line);
    }
}
