package net.knightsandkings.knk.core.npc.nav.group;

import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.feet;
import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.Results;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.SimMover;

/**
 * Plaza lines for groups (implementation plan 4c.3 decision 6, 4c.4 item 5, link M5): the group path takes the plaza
 * line, its leader walks it too, the formation keeps its width on the open plaza, and the line keeps the formation's
 * half width plus {@code wall-clearance} from obstacles; with the key off the group walks the V as before.
 */
class NavGroupPlazaTest {

    private static final double JX = 20.5;
    private static final double JZ = 0.5;

    private final GroupSim sim = new GroupSim();
    private final Results results = new Results();

    private void plazaRoute(boolean plazaLines) {
        NpcNavigationService.Settings d = NpcNavigationService.Settings.defaults();
        sim.moverSettings = new NpcNavigationService.Settings(d.stretchBlocks(), d.hopBlocks(), d.noProgressTicks(),
            d.maxRetries(), d.noRouteWaitTicks(), d.walkOnlyBlocks(), d.recheckTicks(), d.stopOnTrespass(),
            d.cornerClearance(), d.waypointMargin(), d.recoveryWaitTicks(), d.corridorSteering(), d.corridorHalfWidth(),
            d.wallClearance(), d.localLegsClearance(), plazaLines, 16);
        // the road graph's V through a plaza junction on an open square: east to (20.5, 0.5), then north
        sim.road = (subject, from, to) -> RoadPlanner.RoadPlan.found(List.of(floor(0.5, 0.5), floor(JX, JZ),
            floor(JX, -24.5))).withPlazas(List.of(new RoadPlanner.PlazaCrossing(floor(JX, JZ), 10)));
    }

    private static GroupSettings ranks(boolean elastic) {
        GroupSettings s = GroupSettings.defaults().withFormation(new Formation(Formation.Type.RANKS, 1.5, 1.0, 3));
        return elastic ? s.withKeeping(GroupSettings.Keeping.elasticDefaults()) : s;
    }

    /** {closest the leader came to the junction, closest any follower came}. */
    private double[] walk(NavGroup group) {
        group.navigate(feet(JX, -24.5), results);
        double leader = Double.MAX_VALUE;
        double followers = Double.MAX_VALUE;
        while (!results.ended() && sim.tick < 6000) {
            sim.tick();
            for (int i = 0; i < sim.movers.size(); i++) {
                NavPoint p = sim.movers.get(i).position;
                double d = Math.hypot(p.x() - JX, p.z() - JZ);
                if (i == 0) {
                    leader = Math.min(leader, d);
                } else {
                    followers = Math.min(followers, d);
                }
            }
        }
        assertInstanceOf(NavigationResult.Arrived.class, results.last());
        return new double[] {leader, followers};
    }

    @Test
    void theGroupPathAndItsLeaderTakeThePlazaLine() {
        plazaRoute(true);
        NavGroup group = sim.group(10, ranks(true));

        double[] closest = walk(group);

        assertTrue(closest[0] > 5, "the leader crosses on the chord, not through the junction: " + closest[0]);
        assertTrue(closest[1] > 3, "and the followers with it: " + closest[1]);
        assertEquals(0, sim.metrics.total(Event.GROUP_FOLD), "3 abreast fit on the open plaza");
        assertEquals(0, sim.metrics.total(Event.GROUP_NARROW));
        assertTrue(sim.service.corridors().plazaLines() >= 2, "the leader's stretch and the group path");
    }

    @Test
    void levelsGroupsTakeThePlazaLineToo() {
        plazaRoute(true);
        NavGroup group = sim.group(7, ranks(false));

        double[] closest = walk(group);

        assertTrue(closest[0] > 5, "the leader: " + closest[0]);
        assertTrue(closest[1] > 3, "the followers: " + closest[1]);
    }

    @Test
    void offTheGroupWalksTheVAsBefore() {
        plazaRoute(false);
        NavGroup group = sim.group(7, ranks(true));

        double[] closest = walk(group);

        assertTrue(closest[0] < 1.5, "through the junction: " + closest[0]);
        assertEquals(0, sim.service.corridors().plazaLines());
        assertEquals(NavigationSettings.PLAZA_LINES.defaultValue(), false);
    }

    @Test
    void theGroupsPlazaClearanceIsItsHalfWidthPlusTheWallClearance() {
        NavGroup three = sim.group(4, ranks(true));
        assertEquals(1.8, three.plazaClearance(), 1e-9, "3 abreast at 1.0: 1.0 + 0.8");
        NavGroup column = sim.group(3, GroupSettings.defaults());
        assertEquals(0.8, column.plazaClearance(), 1e-9);
    }
}
