package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavGeometry;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.RouteFollower;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.npc.nav.BukkitNavWorld;
import net.knightsandkings.knk.paper.scenario.NpcLedgeScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * The #491 stair corner in its real geometry (PandiO/knk-workspace#592): a 2-wide flight turning 90° onto a 1-wide one
 * at a ledge, a slab before the corner, a slab beside it, a 7-block drop on the inside of the turn. First K&K's half on
 * real blocks: for a road line that cuts the corner across the drop and for one through the cells' centres, with and
 * without corner clearance, no waypoint lies on the ground below and every step between waypoints is a straight walk on
 * the stair. Then 4 NPCs walk up over the corner and back down for 60 s on the cutting line: nobody falls or ends up
 * below the corner. Needs Citizens for the second half.
 */
public final class NpcLedgeCornerScenario implements Scenario<ScenarioScene> {

    static final int WALK_TICKS = 60 * 20;

    @Override
    public String name() {
        return "npc-ledge-corner";
    }

    @Override
    public String description() {
        return "The #491 stair corner as built: waypoints stay on the stair, 4 NPCs walk it for 60 s, nobody falls";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcLedgeScene ledge = NpcLedgeScene.in(scene);
        BukkitNavWorld world = new BukkitNavWorld(null);
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        List<String> below = new ArrayList<>();
        plan.act("build the stair corner", ledge::buildStairCorner)
            .check("the waypoints stay on the stair round the corner, every step a straight walk", () -> {
                for (List<NavPoint> line : List.of(ledge.stairCuttingLine(), ledge.stairCentreLine())) {
                    for (double clearance : new double[] {0, 1.0}) {
                        String wrong = walk(world, ledge, line, clearance);
                        if (wrong != null) {
                            return wrong + " (" + (line.equals(ledge.stairCuttingLine()) ? "cutting" : "centre")
                                + " line, clearance " + clearance + ")";
                        }
                    }
                }
                return null;
            })
            .act("start NPC navigation on Citizens", () -> npcs.set(ScenarioNpcs.start(scene, ledge.stairCuttingLine(),
                NavigationSettings.defaults())))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("4 NPCs walk up over the corner and back", () -> {
                for (int i = 0; i < 4; i++) {
                    NpcMover mover = npcs.get().spawn(ledge.feet(6.5 + 2 * i, NpcLedgeScene.GROUND, 2.5));
                    npcs.get().patrol(mover, ledge.stairEnd(), ledge.stairBottom());
                }
                npcs.get().everyTick(() -> {
                    for (NpcMover mover : npcs.get().movers()) {
                        NavPoint at = mover.position().orElse(null);
                        if (at != null && ledge.belowTheCorner(at) && below.size() < 10) {
                            below.add(mover.name() + " at " + NavGeometry.block(at));
                        }
                    }
                });
            })
            .waitTicks(WALK_TICKS)
            .check("they walked: trips were completed", () -> npcs.get().metrics().total(Event.ARRIVED) >= 4 ? null
                : "only " + npcs.get().metrics().total(Event.ARRIVED) + " arrivals: " + npcs.get().stats()
                    + npcs.get().tail(30))
            .check("nobody ended up below the corner", () -> below.isEmpty() ? null
                : "below the corner: " + below + npcs.get().tail(30))
            .check("nobody fell", () -> npcs.get().metrics().total(Event.FELL) == 0 ? null
                : npcs.get().stats() + npcs.get().tail(30))
            .check("the counters add up", () -> npcs.get().countersAddUp());
    }

    /** Walks every stretch of a line up the stair; the first wrong waypoint or step, or null. */
    private static String walk(BukkitNavWorld world, NpcLedgeScene ledge, List<NavPoint> line, double clearance) {
        RouteFollower follower = new RouteFollower(line.subList(0, line.size() / 2 + 1)); // up only
        List<NavPoint> all = new ArrayList<>();
        int floorY = ledge.stairBottom().blockY() - 1;
        while (follower.hasMore()) {
            RouteFollower.Stretch s = follower.next(world, 6, floorY, clearance);
            if (s.kind() != RouteFollower.Kind.WAYPOINTS) {
                break;
            }
            all.addAll(s.waypoints());
            floorY = all.get(all.size() - 1).blockY() - 1;
        }
        for (int i = 0; i < all.size(); i++) {
            NavPoint p = all.get(i);
            if (ledge.belowTheCorner(p)) {
                return "a waypoint on the ground below the corner: " + NavGeometry.block(p);
            }
            if (i > 0 && !NavGeometry.straightWalkable(world, all.get(i - 1), p)) {
                return "a step off the stair: " + NavGeometry.block(all.get(i - 1)) + " -> " + NavGeometry.block(p);
            }
        }
        return null;
    }
}
