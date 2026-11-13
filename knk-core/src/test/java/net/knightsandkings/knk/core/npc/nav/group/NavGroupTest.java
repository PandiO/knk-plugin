package net.knightsandkings.knk.core.npc.nav.group;

import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.feet;
import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.BlockingElement;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcNavState;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.GateCrossing;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.Results;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.SimMover;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup.FollowerState;

/**
 * Groups plan once (implementation plan Phase 4; BEHAVIOR_MODEL NAV-05): the real navigation service and group
 * service over simulated movers that walk their orders ({@link GroupSim}).
 */
class NavGroupTest {

    private static final GroupSettings COLUMN = GroupSettings.defaults();

    private final GroupSim sim = new GroupSim();
    private final Results results = new Results();

    private static GroupSettings with(Formation formation) {
        return COLUMN.withFormation(formation);
    }

    /** As {@code settings}, with the leader never waiting ({@code group-regroup-blocks: 0}). */
    private static GroupSettings noWaiting(GroupSettings settings) {
        return new GroupSettings(settings.formation(), settings.rejoinBlocks(), 0, settings.catchUpSpeed(),
            settings.hopBlocks(), settings.noProgressTicks(), settings.noRouteWaitTicks(), settings.recheckTicks());
    }

    private static GroupSettings waitingAt(double blocks) {
        return new GroupSettings(COLUMN.formation(), COLUMN.rejoinBlocks(), blocks, COLUMN.catchUpSpeed(),
            COLUMN.hopBlocks(), COLUMN.noProgressTicks(), COLUMN.noRouteWaitTicks(), COLUMN.recheckTicks());
    }

    private SimMover follower(int index) {
        return sim.movers.get(index + 1);
    }

    private SimMover leader() {
        return sim.movers.get(0);
    }

    private void assertArrived() {
        assertTrue(results.ended(), "the order ended; log: " + tail());
        assertInstanceOf(NavigationResult.Arrived.class, results.last(), "log: " + tail());
    }

    private String tail() {
        return sim.log.subList(Math.max(0, sim.log.size() - 8), sim.log.size()).toString();
    }

    // ==================== plans once ====================

    @Test
    void aGroupOfTwentyPlansOnce() {
        NavGroup group = sim.group(20, COLUMN);

        assertTrue(group.navigate(feet(100.5, 0.5), results));
        assertTrue(sim.runUntil(results::ended, 2000));

        assertArrived();
        assertEquals(1, sim.roadPlans, "one road plan for twenty NPCs");
        assertEquals(0, sim.localPlans, "no follower planned a leg of its own");
        assertEquals(0, sim.metrics.total(Event.GROUP_REJOIN));
        assertEquals(100.5, leader().position.x(), 0.01);
        for (int i = 0; i < 19; i++) {
            SimMover follower = follower(i);
            // single file behind the leader, two blocks apart
            assertEquals(100.5 - 2 * (i + 1), follower.position.x(), 0.8, "follower " + i);
            assertEquals(0.5, follower.position.z(), 0.3, "follower " + i);
            assertEquals(0, follower.walks, "a follower walks waypoints, never a path search of its own");
            assertNull(sim.service.followerOf(follower.id()), "a follower has no route in the navigation service");
        }
    }

    @Test
    void followersKeepTheirDistanceWhileTheLeaderWalks() {
        NavGroup group = sim.group(6, COLUMN);
        group.navigate(feet(200.5, 0.5), results);

        assertTrue(sim.runUntil(() -> leader().position.x() > 80, 2000));

        for (int i = 0; i < 5; i++) {
            double gap = leader().position.x() - follower(i).position.x();
            assertEquals(2.0 * (i + 1), gap, 1.5, "follower " + i + " behind the leader");
            assertTrue(follower(i).isMoving(), "followers walk on while the leader walks");
        }
        assertEquals(0, sim.metrics.total(Event.GROUP_REJOIN));
        assertEquals(1, sim.roadPlans);
    }

    @Test
    void aShortTripWithoutARoadPlanStillMovesTheGroup() {
        NavGroup group = sim.group(4, COLUMN);

        group.navigate(feet(10.5, 0.5), results); // within hop-blocks: one local leg for the leader, no road
        assertTrue(sim.runUntil(results::ended, 600));

        assertArrived();
        assertEquals(0, sim.roadPlans);
        assertEquals(1, sim.localPlans, "the leader's local leg only");
        for (int i = 0; i < 3; i++) {
            assertEquals(10.5 - 2 * (i + 1), follower(i).position.x(), 1.0, "follower " + i + " walked the leader's footsteps");
        }
    }

    // ==================== one blocked state, one wake-up, one replan ====================

    private static final BlockingElement KEEP_GATE = BlockingElement.gate(7, "the Keep Gate");

    /** A road with gate door 7 at x = 60: closed, the route ends two blocks before it. */
    private void gatedRoad() {
        sim.road = (subject, from, to) -> {
            List<GateCrossing> gates = List.of(new GateCrossing(7, floor(60.5, 0.5)));
            if (sim.rules.closedGates.contains(7) && from.x() < 60 && to.x() > 60) {
                // the partial route ends before the gate: no gate on it
                return RoadPlan.blocked(List.of(from, floor(58.5, 0.5)), "the Keep Gate is closed", KEEP_GATE, List.of(),
                    s -> sim.rules.closedGates.contains(7));
            }
            return RoadPlan.found(List.of(from, to), gates);
        };
    }

    @Test
    void aGateClosingAheadBlocksAGroupOfFiftyWithOneReplanInOnePlanningSlot() {
        gatedRoad();
        NavGroup group = sim.group(50, COLUMN);
        group.navigate(feet(120.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > 25, 1000));
        assertEquals(1, sim.roadPlans);

        sim.rules.closedGates.add(7);
        sim.service.onGateChanged(7);
        sim.groups.onRulesChanged();
        sim.mostPlansAtOnce = 0;
        sim.run(60);

        assertEquals(2, sim.roadPlans, "the event planned the group's route once more, not fifty times");
        assertTrue(sim.mostPlansAtOnce <= 1, "one planning slot at most, was " + sim.mostPlansAtOnce);
        sim.run(1140);
        // while it waits, only the leader's safety timer asks again (every no-route-wait-ticks)
        assertTrue(sim.roadPlans <= 2 + 1200 / COLUMN.noRouteWaitTicks(), "plans while waiting: " + sim.roadPlans);
        assertEquals(1, sim.metrics.total(Event.REPLAN_EVENT));
        assertTrue(sim.mostPlansAtOnce <= 1, "one planning slot at most, was " + sim.mostPlansAtOnce);
        assertEquals(1, results.blocked(), "one blocked state for the group");
        assertEquals(KEEP_GATE, ((NavigationResult.Blocked) results.last()).blockingElement());
        assertEquals(KEEP_GATE, group.blockedBy());
        assertTrue(sim.service.waitsAtBlock(leader().id()), "the leader waits at the gate");
        assertEquals(58.5, leader().position.x(), 1.0);
        for (int i = 0; i < 49; i++) {
            SimMover follower = follower(i);
            assertTrue(follower.position.x() < 59, "nobody passed the closed gate");
            assertFalse(follower.isMoving(), "the group stands behind its leader");
            if (i < 28) {
                assertEquals(58.5 - 2 * (i + 1), follower.position.x(), 1.2, "follower " + i + " in its slot");
            } else {
                // the column is longer than the way walked so far: its tail still stands where it set out
                assertTrue(follower.position.x() < 2, "follower " + i);
            }
        }
        assertEquals(0, sim.localPlans, "no follower planned anything");

        sim.rules.closedGates.clear();
        sim.service.onGateChanged(7);
        sim.groups.onRulesChanged();
        assertTrue(sim.runUntil(results::ended, 3000));

        assertArrived();
        assertEquals(1, sim.metrics.total(Event.WOKEN), "one wake-up for the group");
        assertEquals(1, results.blocked());
        assertEquals(120.5 - 2 * 49, follower(48).position.x(), 1.2, "the last follower came along");
    }

    @Test
    void aGateClosedBeforeTheGroupSetsOutIsReportedOnceAndTheGroupWalksToIt() {
        gatedRoad();
        sim.rules.closedGates.add(7);
        NavGroup group = sim.group(10, COLUMN);

        group.navigate(feet(120.5, 0.5), results);
        sim.run(800);

        assertEquals(1, results.blocked());
        assertFalse(results.ended());
        assertTrue(group.hasOrder());
        assertTrue(sim.service.waitsAtBlock(leader().id()));
        assertEquals(58.5 - 2 * 9, follower(8).position.x(), 1.2, "the column closed up behind the leader at the gate");
        assertEquals(0, sim.localPlans);
    }

    // ==================== a follower's own leg back ====================

    @Test
    void aStuckFollowerPlansOneLegOfItsOwnAndRejoins() {
        NavGroup group = sim.group(6, noWaiting(COLUMN));
        group.navigate(feet(160.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > 20, 1000));
        SimMover stuck = follower(2);

        stuck.frozen = true;
        assertTrue(sim.runUntil(() -> group.followerState(stuck.id()) == FollowerState.REJOINING, 200),
            "the watchdog saw no progress");
        assertEquals(1, sim.metrics.total(Event.GROUP_REJOIN));
        assertEquals(1, sim.metrics.total(Event.STUCK_NO_PROGRESS));
        stuck.frozen = false;
        assertTrue(sim.runUntil(results::ended, 3000));

        assertArrived();
        assertEquals(1, sim.roadPlans, "its own leg is a local leg, not the whole route");
        assertEquals(1, sim.localPlans);
        assertEquals(1, stuck.walks);
        assertSame(FollowerState.FOLLOWING, group.followerState(stuck.id()));
        assertTrue(stuck.fastestFactor > 1, "it walked faster to catch up with its slot");
        assertEquals(1.0, stuck.speedFactor, 1e-9, "and at normal speed again once there");
        assertEquals(160.5 - 6, stuck.position.x(), 1.0, "back in its slot");
        assertEquals(0, group.lost());
    }

    @Test
    void aFollowerPushedOffItsLaneWalksBackToThePath() {
        NavGroup group = sim.group(5, noWaiting(COLUMN));
        group.navigate(feet(160.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > 30, 1000));
        SimMover pushed = follower(1);

        pushed.position = new NavPoint(GroupSim.WORLD, pushed.position.x(), 65, 9.5); // nine blocks off the road
        sim.run(6);

        assertSame(FollowerState.REJOINING, group.followerState(pushed.id()));
        assertTrue(sim.service.state(pushed.id()) != NpcNavState.IDLE, "an order of its own in the navigation service");
        assertTrue(sim.runUntil(results::ended, 3000));
        assertArrived();
        assertEquals(1, sim.metrics.total(Event.GROUP_REJOIN));
        assertEquals(1, sim.roadPlans);
        assertEquals(160.5 - 4, pushed.position.x(), 1.0);
        assertEquals(0.5, pushed.position.z(), 0.3);
    }

    @Test
    void aFollowerWhoseOwnLegsKeepFailingIsLeftBehindAndTheGroupArrives() {
        NavGroup group = sim.group(4, noWaiting(COLUMN));
        group.navigate(feet(400.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > 20, 1000));
        SimMover lostOne = follower(0);
        sim.failingLegs.add(lostOne.name());
        lostOne.frozen = true;

        assertTrue(sim.runUntil(results::ended, 6000));

        assertArrived();
        assertEquals(1, group.lost());
        assertEquals(1, sim.metrics.total(Event.GROUP_LOST));
        assertTrue(sim.metrics.total(Event.GROUP_REJOIN) >= NavGroup.MAX_REJOINS);
        assertEquals(1, sim.roadPlans, "never the whole route for a follower");
        assertEquals(400.5 - 4, follower(1).position.x(), 1.0, "the others kept their slots");
    }

    @Test
    void theLeaderWaitsForAFollowerThatFellBehindAndWalksOnWhenItClosedUp() {
        NavGroup group = sim.group(4, waitingAt(10));
        group.navigate(feet(200.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > 20, 1000));
        SimMover slow = follower(2);

        slow.frozen = true;
        assertTrue(sim.runUntil(group::holding, 400), "the leader waits");
        assertEquals(1, sim.metrics.total(Event.GROUP_REGROUP));
        assertSame(NpcNavState.IDLE, sim.service.state(leader().id()));
        double heldAt = leader().position.x();
        sim.run(40);
        assertEquals(heldAt, leader().position.x(), 0.01);
        assertFalse(results.ended(), "waiting is not the end of the order");

        slow.frozen = false;
        assertTrue(sim.runUntil(() -> !group.holding(), 1200), "the group closed up");
        assertTrue(sim.runUntil(results::ended, 3000));
        assertArrived();
        assertEquals(1, sim.metrics.total(Event.GROUP_REGROUP), "it waited once");
        assertEquals(2, sim.roadPlans, "the leader's order was given again after the wait: one more plan for the group");
        assertEquals(200.5 - 6, slow.position.x(), 1.0);
    }

    // ==================== formations ====================

    @Test
    void pairsWalkBesideThePathAndFoldIntoAColumnAtANarrowPlace() {
        // the road is open ground, but from x 40 to 49 only the road's own column has a floor (a gate passage)
        sim.world.noFloor = (x, z) -> x >= 40 && x < 50 && z != 0;
        NavGroup group = sim.group(9, with(new Formation(Formation.Type.RANKS, 2, 2)));
        group.navigate(feet(110.5, 0.5), results);

        boolean sawFolded = false;
        while (!results.ended() && sim.tick < 4000) {
            sim.tick();
            sawFolded |= group.folded();
            for (SimMover mover : sim.movers) {
                assertTrue(sim.world.isStandableFloor(GroupSim.WORLD, mover.position.blockX(), 64, mover.position.blockZ()),
                    mover.name() + " left the floor at " + mover.position);
            }
            if (leader().position.x() > 20 && leader().position.x() < 38) {
                assertFalse(group.folded(), "in formation on the open road");
                assertEquals(1.0, Math.abs(follower(0).position.z() - 0.5), 0.35, "a pair walks either side of the path");
            }
        }

        assertArrived();
        assertTrue(sawFolded);
        assertEquals(1, sim.metrics.total(Event.GROUP_FOLD));
        assertEquals(1, sim.metrics.total(Event.GROUP_REFORM));
        assertFalse(group.folded());
        assertEquals(1, sim.roadPlans);
        assertEquals(0, sim.localPlans);
        for (int i = 0; i < 8; i++) {
            SimMover follower = follower(i);
            int row = i / 2 + 1;
            assertEquals(110.5 - 2 * row, follower.position.x(), 0.8, "follower " + i + " in row " + row);
            assertEquals(1.0, Math.abs(follower.position.z() - 0.5), 0.3, "follower " + i + " a block off the path");
        }
        for (int i = 0; i < 8; i += 2) {
            assertTrue((follower(i).position.z() - 0.5) * (follower(i + 1).position.z() - 0.5) < 0,
                "the two of a pair walk on opposite sides");
        }
    }

    @Test
    void anEscortRingKeepsTheLeaderInItsMiddle() {
        NavGroup group = sim.group(9, with(new Formation(Formation.Type.ESCORT_RING, 2, 2)));
        group.navigate(feet(90.5, 0.5), results);

        assertTrue(sim.runUntil(() -> leader().position.x() > 50, 2000));
        int ahead = 0;
        for (int i = 0; i < 8; i++) {
            ahead += follower(i).position.x() > leader().position.x() + 0.5 ? 1 : 0;
        }
        assertTrue(ahead >= 2, "escorts walk ahead of the leader too, saw " + ahead);
        assertTrue(sim.runUntil(results::ended, 3000));

        assertArrived();
        double radius = Formation.RING_RADIUS * 2;
        int front = 0;
        int back = 0;
        for (int i = 0; i < 8; i++) {
            SimMover escort = follower(i);
            assertEquals(radius, escort.position.horizontalDistance(leader().position), 0.9, "escort " + i);
            front += escort.position.x() > leader().position.x() + 0.5 ? 1 : 0;
            back += escort.position.x() < leader().position.x() - 0.5 ? 1 : 0;
        }
        assertEquals(3, front, "three escorts in front of the leader");
        assertEquals(3, back, "three behind");
        assertEquals(1, sim.roadPlans);
    }

    @Test
    void aLooseClusterStaysBehindTheLeaderWithinItsWidth() {
        Formation cluster = new Formation(Formation.Type.CLUSTER, 2, 2);
        NavGroup group = sim.group(10, with(cluster));
        group.navigate(feet(90.5, 0.5), results);
        assertTrue(sim.runUntil(results::ended, 3000));

        assertArrived();
        for (int i = 0; i < 9; i++) {
            SimMover member = follower(i);
            assertTrue(member.position.x() < leader().position.x(), "behind the leader");
            assertTrue(Math.abs(member.position.z() - 0.5) <= cluster.halfWidth(9) + 0.3);
        }
        assertFalse(group.folded());
    }

    // ==================== rules stay binding for followers ====================

    @Test
    void followersStopBeforeARegionThatIsDeniedAfterTheLeaderWentIn() {
        sim.world.regions = (x, z) -> x >= 50 ? Set.of("keep") : Set.of();
        NavGroup group = sim.group(6, COLUMN);
        group.navigate(feet(140.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > 52, 2000));
        assertTrue(follower(1).position.x() < 50, "the second follower is still outside");

        sim.rules.deniedRegions.add("keep");
        sim.service.onAvailabilityChanged();
        sim.groups.onRulesChanged();
        sim.run(300);

        assertTrue(leader().position.x() > 100, "the leader was inside and walks on");
        for (int i = 1; i < 5; i++) {
            assertTrue(follower(i).position.x() < 50, "follower " + i + " stopped outside, at " + follower(i).position.x());
            assertFalse(follower(i).isMoving());
        }
        assertEquals(0, sim.metrics.total(Event.GROUP_REJOIN), "waiting on a rule is not being stuck");
        assertEquals(0, sim.metrics.total(Event.GROUP_REGROUP), "the leader does not wait for them");

        sim.rules.deniedRegions.clear();
        sim.service.onAvailabilityChanged();
        sim.groups.onRulesChanged();
        assertTrue(sim.runUntil(results::ended, 3000));

        assertArrived();
        assertEquals(140.5 - 10, follower(4).position.x(), 1.2, "everybody came along once entry was allowed again");
        // the leader waited for them once they could follow again: one more plan for the group at most
        assertEquals(1 + sim.metrics.total(Event.GROUP_REGROUP), sim.roadPlans);
        assertTrue(sim.metrics.total(Event.GROUP_REGROUP) <= 1);
    }

    @Test
    void aLaneNeverLeavesTheRegionsOfThePath() {
        // a denied district lies along the road's right side from x 30 to 60: lanes there fold onto the path
        sim.world.regions = (x, z) -> x >= 30 && x < 60 && z >= 1 ? Set.of("yard") : Set.of();
        sim.rules.deniedRegions.add("yard");
        NavGroup group = sim.group(7, with(new Formation(Formation.Type.RANKS, 2, 2)));
        group.navigate(feet(100.5, 0.5), results);

        while (!results.ended() && sim.tick < 4000) {
            sim.tick();
            for (SimMover mover : sim.movers) {
                assertTrue(sim.world.regions.apply(mover.position.blockX(), mover.position.blockZ()).isEmpty(),
                    mover.name() + " entered the yard at " + mover.position);
            }
        }

        assertArrived();
        assertEquals(1, sim.metrics.total(Event.GROUP_FOLD));
        assertEquals(1, sim.metrics.total(Event.GROUP_REFORM));
    }

    // ==================== orders ====================

    @Test
    void aNewOrderReplacesTheRunningOneAndTurnsTheColumnRound() {
        NavGroup group = sim.group(5, COLUMN);
        group.navigate(feet(60.5, 0.5), results);
        assertTrue(sim.runUntil(results::ended, 2000));
        assertArrived();

        Results back = new Results();
        group.navigate(feet(0.5, 0.5), back);
        assertTrue(sim.runUntil(back::ended, 3000));

        assertInstanceOf(NavigationResult.Arrived.class, back.last());
        assertEquals(0.5, leader().position.x(), 0.01);
        for (int i = 0; i < 4; i++) {
            assertEquals(0.5 + 2 * (i + 1), follower(i).position.x(), 1.0, "follower " + i + " behind the leader again");
        }
        assertEquals(2, sim.roadPlans, "one plan per order");
        assertEquals(0, sim.localPlans);
    }

    @Test
    void cancelEndsTheOrderOnceAndStopsEverybody() {
        NavGroup group = sim.group(5, COLUMN);
        group.navigate(feet(160.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > 30, 1000));

        assertTrue(group.cancel("test"));
        sim.run(20);

        assertEquals(1, results.all.size());
        assertEquals("test", ((NavigationResult.Cancelled) results.last()).reason());
        assertFalse(group.hasOrder());
        assertSame(NpcNavState.IDLE, sim.service.state(leader().id()));
        for (SimMover mover : sim.movers) {
            assertFalse(mover.isMoving(), mover.name());
        }
        assertFalse(group.cancel("again"));
    }

    @Test
    void theLeadersFailureIsTheGroupsFailure() {
        sim.road = (subject, from, to) -> RoadPlan.none(net.knightsandkings.knk.core.npc.nav.RoadPlanner.Status.DIFFERENT_COMPONENTS, null);
        NavGroup group = sim.group(5, COLUMN);

        group.navigate(feet(160.5, 0.5), results);
        sim.run(20);

        assertEquals(1, results.all.size());
        assertInstanceOf(NavigationResult.Failed.class, results.last());
        assertFalse(group.hasOrder());
        assertEquals(1, sim.roadPlans);
    }

    @Test
    void theNextFollowerLeadsWhenTheLeaderLeaves() {
        NavGroup group = sim.group(5, COLUMN);
        group.navigate(feet(120.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > 30, 1000));
        SimMover oldLeader = leader();
        SimMover newLeader = follower(0);

        group.remove(oldLeader.id());
        assertSame(newLeader, group.leader());
        assertEquals(4, group.size());
        assertTrue(sim.runUntil(results::ended, 3000));

        assertArrived();
        assertEquals(120.5, newLeader.position.x(), 0.01);
        assertTrue(oldLeader.position.x() < 40, "the old leader stayed where it left the group");
        assertEquals(2, sim.roadPlans);
        assertNotNull(sim.service.state(oldLeader.id()), "it is still under navigation");
    }

    @Test
    void aDisbandedGroupTakesNoOrders() {
        NavGroup group = sim.group(3, COLUMN);
        group.navigate(feet(120.5, 0.5), results);
        sim.run(50);

        sim.groups.disband(group);

        assertInstanceOf(NavigationResult.Cancelled.class, results.last());
        assertEquals(0, sim.groups.count());
        assertFalse(group.navigate(feet(10.5, 0.5), results));
    }

    @Test
    void aGroupWithoutAnOrderDoesNothing() {
        sim.group(5, COLUMN);

        sim.run(200);

        assertEquals(0, sim.roadPlans);
        for (SimMover mover : sim.movers) {
            assertEquals(0, mover.follows + mover.walks);
        }
    }
}
