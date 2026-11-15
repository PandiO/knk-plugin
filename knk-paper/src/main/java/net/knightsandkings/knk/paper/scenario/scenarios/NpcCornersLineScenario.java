package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.List;

import net.knightsandkings.knk.core.npc.nav.HopPlanner;
import net.knightsandkings.knk.core.npc.nav.NavGeometry;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.PathTargets;
import net.knightsandkings.knk.core.npc.nav.RouteFollower;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.npc.nav.BukkitNavWorld;
import net.knightsandkings.knk.paper.scenario.NpcCornerScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * K&K's half of the wall-corner work on real blocks, without Citizens (implementation plan 4b.2 item 18.1,
 * PandiO/knk-workspace#521): on the corner test scene, the route's stretches keep their distance from the walls on the
 * inside of the turns and leave the straight along the wall alone; the shortcut check refuses the diagonal past the
 * stair corner; targets ahead stay on the NPC's own floor; the landing under the overhang has 2.5 blocks of headroom,
 * too little to jump a full block from.
 */
public final class NpcCornersLineScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "npc-corners-line";
    }

    @Override
    public String description() {
        return "NPC route stretches keep clear of wall corners, refuse the stair-corner shortcut and aim on their own floor";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcCornerScene corners = NpcCornerScene.in(scene);
        BukkitNavWorld world = new BukkitNavWorld(null);
        String w = scene.world().getName();
        plan.act("build the corner scene", corners::build)
            .check("turn waypoints keep a block from the building on the inside of the turns, the straight along the wall "
                + "is left alone",
                () -> {
                    RouteFollower follower = new RouteFollower(corners.routeLine());
                    List<NavPoint> waypoints = follower.next(world, 400, NpcCornerScene.GROUND + scene.worldY(0), 1.0)
                        .waypoints();
                    for (NavPoint p : waypoints) {
                        int index = follower.indexNearest(HopPlanner.floor(p));
                        int x = p.blockX() - scene.worldX(0);
                        int z = p.blockZ() - scene.worldZ(0);
                        // the ring's turns all have the building (x and z 7..24) on their inside
                        if (follower.corners().nearTurn(index) && p.blockY() == scene.worldY(NpcCornerScene.GROUND + 1)
                            && x >= 6 && x <= 25 && z >= 6 && z <= 25) {
                            return "turn waypoint " + NavGeometry.block(p) + " touches the building on the inside of the "
                                + "turn (route index " + index + ")";
                        }
                        if (z == 4 && x >= 9 && x <= 22 && p.blockY() != scene.worldY(NpcCornerScene.GROUND + 1)) {
                            return "the straight along the north wall moved: " + NavGeometry.block(p);
                        }
                    }
                    for (int i = 1; i < waypoints.size(); i++) {
                        if (Math.abs(waypoints.get(i).blockY() - waypoints.get(i - 1).blockY()) > HopPlanner.CONTINUITY_RANGE) {
                            return "no floor continuity between " + NavGeometry.block(waypoints.get(i - 1)) + " and "
                                + NavGeometry.block(waypoints.get(i));
                        }
                    }
                    return null;
                })
            .check("the diagonal past the stair corner crosses air and is refused; along the ledge is walkable", () -> {
                NavPoint before = corners.feet(6.5, NpcCornerScene.LEDGE, 26.5);
                NavPoint after = corners.feet(4.5, NpcCornerScene.LEDGE, 24.5);
                if (NavGeometry.straightWalkable(world, before, after)) {
                    return "the shortcut over the drop was allowed";
                }
                return NavGeometry.straightWalkable(world, corners.feet(9.5, NpcCornerScene.LEDGE, 26.5),
                    corners.feet(5.5, NpcCornerScene.LEDGE, 26.5)) ? null : "the ledge itself was refused";
            })
            .check("a target ahead from the ground beside the ledge is never up on the ledge; on the ledge it stays there",
                () -> {
                    List<NavPoint> line = new RouteFollower(corners.routeLine()).line();
                    NavPoint ground = corners.feet(6.5, NpcCornerScene.GROUND, 20.5);
                    int place = nearest(line, corners.feet(4.5, NpcCornerScene.LEDGE, 20.5));
                    PathTargets.Target fromGround = PathTargets.ahead(world, line, place, line.size() - 1,
                        ground.blockY() - 1, 16, null);
                    if (fromGround != null) {
                        return "a target on another floor: " + NavGeometry.block(fromGround.floor());
                    }
                    PathTargets.Target onLedge = PathTargets.ahead(world, line, place, line.size() - 1,
                        scene.worldY(NpcCornerScene.LEDGE), 16, null);
                    if (onLedge == null) {
                        return "no target ahead on the ledge";
                    }
                    return Math.abs(onLedge.floor().blockY() - scene.worldY(NpcCornerScene.LEDGE)) <= HopPlanner.CONTINUITY_RANGE
                        ? null : "the ledge's target left the ledge: " + NavGeometry.block(onLedge.floor());
                })
            .check("the landing has 2.5 blocks of headroom, too little to jump a full block from", () -> {
                NavPoint landing = HopPlanner.floor(corners.landing());
                double free = world.freeHeight(w, landing.blockX(), landing.blockY(), landing.blockZ());
                if (Math.abs(free - 2.5) > 1e-9) {
                    return "free height at the landing " + free;
                }
                NavPoint up = new NavPoint(w, landing.x() + 1, landing.y() + 1, landing.z());
                return NavGeometry.jumpFits(world, landing, up) ? "a full-block jump from the landing was allowed" : null;
            });
    }

    private static int nearest(List<NavPoint> line, NavPoint feet) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < line.size(); i++) {
            NavPoint p = line.get(i);
            double d = Math.pow(p.x() - feet.x(), 2) + Math.pow(p.y() + 1 - feet.y(), 2) + Math.pow(p.z() - feet.z(), 2);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
