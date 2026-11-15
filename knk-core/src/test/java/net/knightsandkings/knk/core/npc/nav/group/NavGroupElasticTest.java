package net.knightsandkings.knk.core.npc.nav.group;

import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.feet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.FoldReason;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.Results;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.SimMover;

/**
 * Elastic formation keeping (implementation plan 4c.3 decision 2, 4c.4 item 3.2, link M3; BEHAVIOR_MODEL NAV-25,
 * PandiO/knk-workspace#624): the width is fitted from the free width across the path, squeezed to
 * {@code group-lateral-spacing-min} before it narrows, decided {@code group-lookahead-blocks} ahead, with the same
 * hysteresis for every change and lanes merging for short tight places; stairs like any road with a step per lane;
 * lanes by the access rules; slots re-assigned by place.
 */
class NavGroupElasticTest {

    private final GroupSim sim = new GroupSim();
    private final Results results = new Results();

    /** Ranks {@code wide} abreast at the decided spacings, elastic, the decided minimums and look-ahead. */
    private static GroupSettings ranks(int wide) {
        return GroupSettings.defaults().withFormation(new Formation(Formation.Type.RANKS, 1.5, 1.0, wide))
            .withKeeping(GroupSettings.Keeping.elasticDefaults());
    }

    /**
     * Walls along a road on x (its line at z 0.5): the walkable columns for an x are z {@code 1 - w + 1 .. 1} for
     * {@code w} 1 to 4 wide, (3: z -1..1; 2: z 0..1; 1: z 0 only); everything from z -4 to 5 beside them is wall.
     */
    private void road(IntUnaryOperator wide) {
        sim.world.walls = (x, z) -> {
            int w = wide.applyAsInt(x);
            boolean open = w == 1 ? z == 0 : w == 2 ? z == 0 || z == 1 : w == 3 ? z >= -1 && z <= 1 : z >= -1 && z <= 2;
            return !open && z >= -4 && z <= 5 && x >= 2;
        };
    }

    private List<String> lines(String what) {
        return sim.log.stream().filter(line -> line.startsWith("group ") && line.contains(what)).toList();
    }

    private void walk(NavGroup group, double toX, IntPredicate everyTick) {
        group.navigate(feet(toX, 0.5), results);
        while (!results.ended() && sim.tick < 8000) {
            sim.tick();
            for (SimMover mover : sim.movers) {
                int x = mover.position.blockX();
                int z = mover.position.blockZ();
                assertFalse(sim.world.walls.test(x, z), mover.name() + " walked into a wall at " + mover.position);
            }
            everyTick.test((int) sim.tick);
        }
        assertInstanceOf(NavigationResult.Arrived.class, results.last());
    }

    private long folds() {
        return sim.metrics.total(Event.GROUP_FOLD);
    }

    private long narrows() {
        return sim.metrics.total(Event.GROUP_NARROW);
    }

    @Test
    void theKeysDefaultToLevelsAndTheDecidedValues() {
        assertFalse(GroupSettings.defaults().keeping().elastic(), "levels by default: behaviour unchanged");
        GroupSettings.Keeping keeping = GroupSettings.defaults().keeping();
        assertEquals(0.6, keeping.lateralSpacingMin());
        assertEquals(1.0, keeping.spacingMin());
        assertEquals(4, keeping.lookaheadBlocks());
        assertEquals("levels", NavigationSettings.GROUP_FORMATION_KEEPING.defaultValue());
        NavigationSettings elastic = NavigationSettings.builder().global(NavigationSettings.Overrides.of(
            NavigationSettings.GROUP_FORMATION_KEEPING, "elastic")).build();
        assertTrue(GroupSettings.of(elastic, NavigationSettings.Scope.of(null)).keeping().elastic());
    }

    @Test
    void aThreeWideRoadWithIndentationsEveryTwoBlocksKeepsThreeAbreast() {
        // one-block indentations every 2 blocks on both sides (a jagged wall), offset so the road never gets narrower
        road(x -> (x / 2) % 2 == 0 ? 4 : 3);
        NavGroup group = sim.group(10, ranks(3));
        SimMover leader = sim.movers.get(0);
        walk(group, 90.5, t -> {
            if (leader.position.x() > 20 && leader.position.x() < 80) {
                assertEquals(3, group.width().abreast(), "3 abreast on the jagged road at x " + leader.position.x());
            }
            return true;
        });
        assertEquals(0, folds(), lines(" folds ").toString());
        assertEquals(0, narrows(), lines(" narrows ").toString());
    }

    @Test
    void aTwoWideRoadTakesThreeAbreastSqueezed() {
        road(x -> 2);
        NavGroup group = sim.group(10, ranks(3));
        SimMover leader = sim.movers.get(0);
        double[] closest = {9};
        walk(group, 90.5, t -> {
            if (leader.position.x() > 30 && leader.position.x() < 80) {
                assertEquals(3, group.width().abreast(), "3 abreast squeezed on the 2-wide road");
                for (int i = 1; i < sim.movers.size(); i++) {
                    SimMover a = sim.movers.get(i);
                    for (int j = i + 1; j < sim.movers.size(); j++) {
                        SimMover b = sim.movers.get(j);
                        if (Math.abs(a.position.x() - b.position.x()) < 0.2 && a.position.x() > 12) {
                            closest[0] = Math.min(closest[0], Math.abs(a.position.z() - b.position.z()));
                        }
                    }
                }
            }
            return true;
        });
        assertEquals(0, folds());
        assertEquals(0, narrows());
        assertTrue(closest[0] >= 0.55, "neighbours in a row at the squeezed 0.6, not closer: " + closest[0]);
        assertTrue(closest[0] <= 0.65, "squeezed to 0.6 on a 2-wide road: " + closest[0]);
    }

    @Test
    void theRowsCloseUpWhileSqueezed() {
        road(x -> 2);
        NavGroup group = sim.group(7, ranks(3));
        SimMover leader = sim.movers.get(0);
        double[] gap = {0};
        walk(group, 90.5, t -> {
            if (leader.position.x() > 60 && leader.position.x() < 62) {
                // the first row's followers stand one row behind the leader
                double maxX = -1e9;
                for (int i = 1; i < sim.movers.size(); i++) {
                    maxX = Math.max(maxX, sim.movers.get(i).position.x());
                }
                gap[0] = leader.position.x() - maxX;
            }
            return true;
        });
        assertTrue(gap[0] > 0.7 && gap[0] < 1.3, "rows at group-spacing-min 1.0 while squeezed, was " + gap[0]);
    }

    @Test
    void threeToTwoToThreeBeginsTheLookAheadBeforeTheNarrowPart() {
        // ranks of 4: 4 abreast fit 3 wide (2.5 at 0.6), 3 abreast fit 2 wide (1.9), 4 abreast do not (2.5 > 2)
        road(x -> x >= 60 && x < 80 ? 2 : 3);
        NavGroup group = sim.group(13, ranks(4));
        SimMover leader = sim.movers.get(0);
        double[] narrowedAt = {Double.NaN};
        double[] widenedAt = {Double.NaN};
        walk(group, 130.5, t -> {
            if (Double.isNaN(narrowedAt[0]) && group.width().abreast() == 3) {
                narrowedAt[0] = leader.position.x();
            }
            if (!Double.isNaN(narrowedAt[0]) && Double.isNaN(widenedAt[0]) && group.width().abreast() == 4) {
                widenedAt[0] = leader.position.x();
            }
            return true;
        });
        // depth: 3 rows of 4 behind the leader, closed up while squeezed (1.25 to 1.5): about 4; the look-ahead is the
        // depth plus 4 past the leader's front, plus a block; the group looks every 5 ticks (about a block)
        assertTrue(narrowedAt[0] < 60 - 6, "narrowed before the narrow part by the look-ahead: at x " + narrowedAt[0]);
        assertTrue(narrowedAt[0] > 60 - 12, "not much earlier than the look-ahead: at x " + narrowedAt[0]);
        assertTrue(widenedAt[0] > 80, "widened once the group's rear is out of it: at x " + widenedAt[0]);
        assertEquals(1, narrows(), lines(" narrows ").toString());
        assertEquals(1, sim.metrics.total(Event.GROUP_WIDEN));
        assertEquals(0, folds());
    }

    @Test
    void aOneBlockGapMergesTheLanesAndFoldsNothing() {
        road(x -> x == 50 ? 1 : 3);
        NavGroup group = sim.group(10, ranks(3));
        walk(group, 100.5, t -> true);
        assertEquals(0, folds(), lines(" folds ").toString());
        assertEquals(0, narrows(), lines(" narrows ").toString());
        assertEquals(1, sim.metrics.total(Event.GROUP_MERGE), lines(" merges ").toString());
        assertEquals(1, sim.metrics.foldReasonTotal(Event.GROUP_MERGE, FoldReason.NARROW));
        assertTrue(lines(" merges its lanes ").get(0).contains("too narrow for 3 abreast (narrow)"), lines(" merges ")
            .toString());
    }

    @Test
    void aOneWideGateFourBlocksLongFoldsOnceAndReformsOnce() {
        road(x -> x >= 50 && x < 54 ? 1 : 3);
        NavGroup group = sim.group(10, ranks(3));
        SimMover leader = sim.movers.get(0);
        walk(group, 110.5, t -> {
            if (leader.position.x() > 50.5 && leader.position.x() < 53.5) {
                assertTrue(group.folded(), "a column in the 4-block gate");
            }
            return true;
        });
        assertEquals(1, folds(), lines(" folds ").toString());
        assertEquals(1, sim.metrics.total(Event.GROUP_REFORM));
        assertEquals(0, narrows(), "the column straight from 3 abreast: 2 abreast do not fit either");
        assertEquals(1, sim.metrics.foldReasonTotal(Event.GROUP_FOLD, FoldReason.NARROW));
    }

    /** A flight up from x 40 to x 47, a block per x, as wide as the road. */
    private void flight() {
        sim.world.floorY = (x, z) -> x < 40 ? 64 : x < 48 ? 64 + (x - 39) : 72;
    }

    @Test
    void aThreeWideFlightIsWalkedThreeAbreastWhenEveryLaneHasItsSteps() {
        road(x -> 3);
        flight();
        NavGroup group = sim.group(10, ranks(3));
        SimMover leader = sim.movers.get(0);
        boolean[] onFlight = {false};
        walk(group, 80.5, t -> {
            double x = leader.position.x();
            if (x > 41 && x < 46) {
                onFlight[0] = true;
                assertEquals(3, group.width().abreast(), "3 abreast squeezed on the flight at x " + x);
            }
            return true;
        });
        assertTrue(onFlight[0]);
        assertEquals(0, folds(), lines(" folds ").toString());
        assertEquals(0, narrows(), lines(" narrows ").toString());
    }

    @Test
    void aFlightWhereALaneHasNoStepOfItsOwnDropsThatWidth() {
        road(x -> 3);
        // the left column (z -1) of the flight is a block lower than the steps beside it all the way up (a ramp of
        // its own, no step at the path's height); the middle and right columns have their steps (2 wide: 3 abreast
        // fit squeezed, 4 abreast don't)
        sim.world.floorY = (x, z) -> {
            int base = x < 40 ? 64 : x < 48 ? 64 + (x - 39) : 72;
            return x >= 41 && x < 48 && z == -1 ? base - 1 : base;
        };
        NavGroup group = sim.group(10, ranks(4));
        walk(group, 80.5, t -> true);
        assertTrue(narrows() >= 1, "4 abreast dropped on the flight: " + lines(" narrows "));
        assertTrue(sim.metrics.foldReasonTotal(Event.GROUP_NARROW, FoldReason.STAIR) >= 1,
            "the reason is the missing step: " + lines(" narrows "));
        assertEquals(0, folds(), lines(" folds ").toString());
    }

    @Test
    void aStructureRegionWithoutARuleKeepsTheLanes() {
        road(x -> 3);
        // the outer columns run through a structure region that nobody is denied
        sim.world.regions = (x, z) -> x >= 40 && x < 60 && z != 0 ? Set.of("market-stalls") : Set.of();
        NavGroup group = sim.group(10, ranks(3));
        walk(group, 100.5, t -> true);
        assertEquals(0, narrows(), lines(" narrows ").toString());
        assertEquals(0, folds(), lines(" folds ").toString());
        assertEquals(0, sim.metrics.total(Event.TRESPASS));
    }

    @Test
    void aRegionTheRulesDenyRemovesTheLanesInIt() {
        road(x -> 3);
        sim.world.regions = (x, z) -> x >= 40 && x < 60 && z != 0 ? Set.of("private-garden") : Set.of();
        sim.rules.deniedRegions.add("private-garden");
        NavGroup group = sim.group(10, ranks(3));
        walk(group, 100.5, t -> true);
        assertEquals(1, folds(), "only the centre column is allowed for 20 blocks: " + lines(" folds "));
        assertEquals(1, sim.metrics.foldReasonTotal(Event.GROUP_FOLD, FoldReason.RULES));
        assertEquals(0, sim.metrics.total(Event.TRESPASS));
    }

    @Test
    void onAFoldNoFollowerIsGivenASlotAheadOfOneThatWasAheadOfIt() {
        road(x -> x >= 50 && x < 60 ? 1 : 3);
        NavGroup group = sim.group(13, ranks(3));
        SimMover leader = sim.movers.get(0);
        List<double[]> before = new ArrayList<>();
        boolean[] checked = {false};
        walk(group, 110.5, t -> {
            if (!checked[0] && !group.folded()) {
                before.clear();
                for (int i = 1; i < sim.movers.size(); i++) {
                    before.add(new double[] {sim.movers.get(i).position.x(), i});
                }
            }
            if (!checked[0] && group.folded()) {
                checked[0] = true;
                // the slots now: a follower's target x is the leader's x minus its slot's back; compare by where they
                // stood at the fold: whoever was ahead (by more than half a block) is not given a slot behind
                for (double[] a : before) {
                    for (double[] b : before) {
                        if (a[0] > b[0] + 0.5) {
                            double backA = slotBack(group, (int) a[1] - 1);
                            double backB = slotBack(group, (int) b[1] - 1);
                            assertTrue(backA <= backB + 1e-9, "npc-" + (int) (a[1] + 1) + " was ahead of npc-"
                                + (int) (b[1] + 1) + " but got the slot behind it");
                        }
                    }
                }
            }
            return true;
        });
        assertTrue(checked[0], "the group folded");
        assertTrue(leader.position.x() > 100);
    }

    /** A follower's current slot's distance behind the leader (through the group's re-assignment). */
    private static double slotBack(NavGroup group, int follower) {
        return group.slotOf(follower).back();
    }

    @Test
    void theColumnKeepsOffTheWallWhereTheRoadAllows() {
        // a 3-wide road whose route line hugs its right wall (z 1.5 is the last column): the column of a 1-wide
        // formation is placed with the wall clearance, not on the line
        road(x -> 3);
        sim.road = (subject, from, to) -> net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan.found(
            List.of(feet(0.5, 1.5), feet(to.x(), 1.5)));
        NavGroup group = sim.group(4, GroupSettings.defaults().withFormation(Formation.column(1.5))
            .withKeeping(GroupSettings.Keeping.elasticDefaults()));
        SimMover leader = sim.movers.get(0);
        double[] most = {0};
        walk(group, 80.5, t -> {
            if (leader.position.x() > 40) {
                for (int i = 1; i < sim.movers.size(); i++) {
                    NavPoint p = sim.movers.get(i).position;
                    if (p.x() > 20 && !sim.movers.get(i).isMoving()) {
                        most[0] = Math.max(most[0], p.z());
                    }
                }
            }
            return true;
        });
        assertTrue(most[0] <= 2 - 0.8 + 0.05, "followers keep 0.8 from the right wall at z 2: " + most[0]);
    }
    @Test
    void theFitIsLookedUpOncePerPathPointWithBoundedReads() {
        road(x -> (x / 2) % 2 == 0 ? 4 : 3);
        long[] reads = {0};
        java.util.function.BiPredicate<Integer, Integer> walls = sim.world.walls;
        sim.world.walls = (x, z) -> {
            reads[0]++;
            return walls.test(x, z);
        };
        GroupPath path = new GroupPath();
        for (int i = 0; i < 60; i++) {
            path.add(GroupSim.floor(4.5 + 1.5 * i, 0.5));
        }
        double[] units = {1, 0.5, 0};
        path.levels(new double[] {1, 0.5, 0});
        GroupPath.LaneRules open = (world, from, to) -> true;
        for (int i = 0; i < path.size(); i++) {
            path.elasticWidest(sim.world, i, open, units, 0.6);
        }
        long first = reads[0];
        assertTrue(first <= 60L * 40, "at most about 40 reads per path point (both sides, 4 blocks each): " + first);
        for (int i = 0; i < path.size(); i++) {
            path.elasticWidest(sim.world, i, open, units, 0.6);
            path.fit(sim.world, i, open);
        }
        assertEquals(first, reads[0], "a second look reads nothing");
    }
}
