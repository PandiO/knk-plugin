package net.knightsandkings.knk.core.npc.nav.group;

import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.feet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.Results;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.SimMover;

/**
 * Narrowed formations (implementation plan 4b.3 items 3 to 7, NAV-22; PandiO/knk-workspace#493): a group steps
 * down 3 → 2 abreast before it folds into a column, only where 2 abreast does not fit; short bumps and short
 * widenings change nothing (hysteresis); a gate folds at once.
 */
class NavGroupWidthsTest {

    private final GroupSim sim = new GroupSim();
    private final Results results = new Results();

    /** Ranks 3 wide at the decided spacings: rows 1.5 apart, shoulder to shoulder. */
    private static GroupSettings ranks3() {
        return GroupSettings.defaults().withFormation(new Formation(Formation.Type.RANKS, 1.5, 1.0, 3));
    }

    /**
     * The road along x (its line at z 0.5) has a floor only in the columns {@code wide} gives for an x: 3 means
     * z -1..1, 2 means z 0..1 (the line runs down one of the two columns), 1 means z 0.
     */
    private void road(java.util.function.IntUnaryOperator wide) {
        sim.world.noFloor = (x, z) -> {
            int w = wide.applyAsInt(x);
            return w == 3 ? z < -1 || z > 1 : w == 2 ? z < 0 || z > 1 : z != 0;
        };
    }

    private List<String> widthLines() {
        return sim.log.stream().filter(line -> line.startsWith("group ") && (line.contains(" narrows ")
            || line.contains(" widens ") || line.contains(" folds ") || line.contains(" takes up "))).toList();
    }

    private void walk(NavGroup group, double toX, IntPredicate everyTick) {
        group.navigate(feet(toX, 0.5), results);
        while (!results.ended() && sim.tick < 6000) {
            sim.tick();
            for (SimMover mover : sim.movers) {
                assertTrue(sim.world.isStandableFloor(GroupSim.WORLD, mover.position.blockX(), 64, mover.position.blockZ()),
                    mover.name() + " left the floor at " + mover.position);
            }
            everyTick.test((int) sim.tick);
        }
        assertInstanceOf(NavigationResult.Arrived.class, results.last());
    }

    @Test
    void aRoadThreeTwoOneTwoThreeWideNarrowsFoldsReformsAndWidensInThatOrder() {
        road(x -> x < 40 ? 3 : x < 60 ? 2 : x < 70 ? 1 : x < 90 ? 2 : 3);
        NavGroup group = sim.group(7, ranks3());
        SimMover leader = sim.movers.get(0);
        boolean[] seen = new boolean[4];

        walk(group, 150.5, t -> {
            double x = leader.position.x();
            if (x > 25 && x < 37) {
                seen[0] = true;
                assertEquals(3, group.width().abreast(), "3 abreast on the 3-wide road at x " + x);
            }
            if (x > 52 && x < 58) {
                seen[1] = true;
                assertEquals(2, group.width().abreast(), "2 abreast in the 2-wide passage at x " + x);
                for (int i = 1; i <= 6; i++) {
                    SimMover f = sim.movers.get(i);
                    if (f.position.x() > 46 && f.position.x() < 58 && !f.isMoving()) {
                        double z = f.position.z();
                        assertTrue(Math.abs(z - 0.5) < 0.3 || Math.abs(z - 1.5) < 0.3,
                            f.name() + " walks a block centre of the passage, z " + z);
                    }
                }
            }
            if (x > 64 && x < 69) {
                seen[2] = true;
                assertTrue(group.folded(), "a column in the 1-wide passage");
            }
            if (x > 125) {
                seen[3] = true;
            }
            return true;
        });

        for (boolean s : seen) {
            assertTrue(s);
        }
        List<String> lines = widthLines();
        assertEquals(4, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("narrows to 2 abreast: the path is too narrow for 3 abreast (narrow) at "), lines.get(0));
        assertTrue(lines.get(1).contains("folds into a column: the path is too narrow for 2 abreast (narrow) at "), lines.get(1));
        assertTrue(lines.get(2).contains("takes up 2 abreast again at "), lines.get(2));
        assertTrue(lines.get(3).contains("widens to 3 abreast at "), lines.get(3));
        assertEquals(1, sim.metrics.total(Event.GROUP_NARROW));
        assertEquals(1, sim.metrics.total(Event.GROUP_FOLD));
        assertEquals(1, sim.metrics.total(Event.GROUP_REFORM));
        assertEquals(1, sim.metrics.total(Event.GROUP_WIDEN));
        assertEquals(3, group.width().abreast());
        assertEquals(1, sim.roadPlans);
    }

    @Test
    void aOneBlockBumpChangesNothing() {
        road(x -> 3);
        sim.world.noFloor = sim.world.noFloor.or((x, z) -> x == 50 && z == 1); // a lamp post on the right
        NavGroup group = sim.group(7, ranks3());
        walk(group, 120.5, t -> true);

        assertEquals(List.of(), widthLines());
        assertEquals(0, sim.metrics.total(Event.GROUP_NARROW));
        assertEquals(0, sim.metrics.total(Event.GROUP_FOLD));
    }

    @Test
    void aTwoBlockWideningChangesNothing() {
        road(x -> x < 20 ? 3 : x == 60 || x == 61 ? 3 : 2);
        NavGroup group = sim.group(7, ranks3());
        walk(group, 120.5, t -> true);

        assertEquals(1, sim.metrics.total(Event.GROUP_NARROW), "once, where the road narrows at x 20: " + widthLines());
        assertEquals(0, sim.metrics.total(Event.GROUP_WIDEN), widthLines().toString());
        assertEquals(2, group.width().abreast());
    }

    @Test
    void aGateOneBlockLongFoldsAtOnce() {
        road(x -> x == 50 ? 1 : 3);
        NavGroup group = sim.group(7, ranks3());
        walk(group, 120.5, t -> true);

        assertEquals(1, sim.metrics.total(Event.GROUP_FOLD), widthLines().toString());
        assertEquals(1, sim.metrics.total(Event.GROUP_REFORM));
        assertEquals(0, sim.metrics.total(Event.GROUP_NARROW));
        assertEquals(0, sim.metrics.total(Event.GROUP_WIDEN));
        assertTrue(widthLines().get(1).contains("takes up 3 abreast again"), widthLines().toString());
    }

    @Test
    void aClusterAndAnEscortRingWalkThreeAbreastOnAThreeWideRoad() {
        road(x -> 3);
        GroupSim ringSim = new GroupSim();
        ringSim.world.noFloor = sim.world.noFloor;
        NavGroup cluster = sim.group(9, GroupSettings.defaults().withFormation(
            new Formation(Formation.Type.CLUSTER, 1.5, 1.0, 2)));
        walk(cluster, 90.5, t -> true);
        assertEquals(3, cluster.width().abreast());
        assertEquals(0, sim.metrics.total(Event.GROUP_FOLD));

        NavGroup ring = ringSim.group(9, GroupSettings.defaults().withFormation(
            new Formation(Formation.Type.ESCORT_RING, 1.5, 1.0, 2)));
        Results ringResults = new Results();
        ring.navigate(feet(90.5, 0.5), ringResults);
        assertTrue(ringSim.runUntil(ringResults::ended, 4000));
        assertTrue(ring.width().ring(), "eight escorts at 1.25 fit a 3-wide road");
        NavGroup big = ringSim.group(13, GroupSettings.defaults().withFormation(
            new Formation(Formation.Type.ESCORT_RING, 1.5, 1.0, 2)));
        Results bigResults = new Results();
        big.navigate(feet(90.5, 0.5), bigResults);
        assertTrue(ringSim.runUntil(bigResults::ended, 4000));
        assertEquals(new Formation.Width(3, false), big.width(), "twelve need a second ring: rows of three");
        SimMover bigLeader = ringSim.movers.get(9);
        int ahead = 0;
        int beside = 0;
        for (int i = 10; i < 22; i++) {
            SimMover escort = ringSim.movers.get(i);
            ahead += escort.position.x() > bigLeader.position.x() + 0.75 ? 1 : 0;
            beside += Math.abs(escort.position.x() - bigLeader.position.x()) < 0.5 ? 1 : 0;
        }
        assertEquals(3, ahead, "a row of three ahead of the leader");
        assertEquals(2, beside, "one escort either side of it");
        assertTrue(Set.of(0L).contains(ringSim.metrics.total(Event.GROUP_FOLD)));
    }
}
