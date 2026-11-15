package net.knightsandkings.knk.core.npc.nav.group;

import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.feet;
import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.gates.target.GateBox;
import net.knightsandkings.knk.core.npc.nav.BlockingElement;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.GateCrossing;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.Results;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.SimMover;

/**
 * Followers that finish their trip (implementation plan 4b.3 items 8 to 11, NAV-23; PandiO/knk-workspace#507):
 * {@code group-followers-finish-trip} off, a follower held at a gate when the leader turns back follows the new
 * order (today's behaviour, now a rule); on, it finishes to its slot at the goal first, reports, then follows.
 */
class NavGroupFinishTripTest {

    private static final GateBox DOOR_7 = new GateBox(60, 64, -1, 61, 69, 2);
    private static final BlockingElement KEEP_GATE = BlockingElement.gate(7, "the Keep Gate");

    private static final boolean DEBUG = Boolean.getBoolean("knk.debug");
    private final GroupSim sim = new GroupSim();
    private final Results results = new Results();
    private final Map<Integer, Integer> arrivals = new HashMap<>();
    private final List<NavPoint> arrivalGoals = new ArrayList<>();

    private static GroupSettings column(boolean finish) {
        return GroupSettings.defaults().withFormation(Formation.column(2.0)).withFollowersFinishTrip(finish);
    }

    private SimMover leader() {
        return sim.movers.get(0);
    }

    private void gatedRoad() {
        sim.road = (subject, from, to) -> {
            List<GateCrossing> gates = List.of(new GateCrossing(7, floor(60.5, 0.5)));
            if (sim.rules.closedGates.contains(7) && from.x() < 60 && to.x() > 60) {
                return RoadPlan.blocked(List.of(from, floor(58.5, 0.5)), "the Keep Gate is closed", KEEP_GATE, List.of(),
                    s -> sim.rules.closedGates.contains(7));
            }
            return RoadPlan.found(List.of(from, to), gates);
        };
        sim.rules.doorBoxes.put(7, DOOR_7);
    }

    private void gate(boolean closed) {
        if (closed) {
            sim.rules.closedGates.add(7);
        } else {
            sim.rules.closedGates.clear();
        }
        sim.service.onGateChanged(7);
        sim.groups.onRulesChanged();
    }

    private NavGroup group(int n, boolean finish) {
        NavGroup group = sim.group(n, column(finish));
        group.onMemberArrived((member, goal) -> {
            arrivals.merge(member.id(), 1, Integer::sum);
            arrivalGoals.add(goal);
        });
        return group;
    }

    /**
     * The gate closes behind the leader with followers still before it; the leader arrives at x 100.5 and the group
     * turns back towards x 0.5. Returns the followers that were held at the gate.
     */
    private List<SimMover> heldAtTheGateWhenTheLeaderTurnsBack(NavGroup group, Results back) {
        gatedRoad();
        group.navigate(feet(100.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > 63.5, 3000));
        gate(true);
        sim.run(40);
        // a follower within about a step of the door's margin when the close starts may still walk on (Phase 4 limit 1)
        List<SimMover> held = sim.movers.stream().skip(1).filter(m -> m.position.x() < 59 && !m.isMoving()).toList();
        assertTrue(held.size() >= 2, "followers still have to pass the gate");
        assertTrue(sim.runUntil(results::ended, 3000), "the group's order ends without the held followers");
        assertInstanceOf(NavigationResult.Arrived.class, results.last());
        for (SimMover m : held) {
            assertTrue(m.position.x() < 59, m.name() + " held short of the door");
        }
        group.navigate(feet(0.5, 0.5), back);
        sim.run(20);
        gate(false);
        return held;
    }

    @Test
    void offAFollowerHeldAtTheGateFollowsTheLeaderBack() {
        NavGroup group = group(8, false);
        Results back = new Results();
        List<SimMover> held = heldAtTheGateWhenTheLeaderTurnsBack(group, back);
        Map<Integer, Double> furthest = new HashMap<>();
        while (!back.ended() && sim.tick < 9000) {
            sim.tick();
            for (SimMover m : held) {
                furthest.merge(m.id(), m.position.x(), Math::max);
                if (DEBUG && sim.tick % 20 == 0) {
                    System.out.println("T " + sim.tick + " " + m.name() + " " + m.position + " " + group.followerState(m.id())
                        + " leader " + leader().position.x());
                }
            }
        }
        if (DEBUG) {
            sim.log.forEach(System.out::println);
        }

        assertInstanceOf(NavigationResult.Arrived.class, back.last());
        for (SimMover m : held) {
            assertTrue(furthest.get(m.id()) < 66, m.name() + " never went on to the goal: " + furthest.get(m.id()));
        }
        assertEquals(0, sim.metrics.total(Event.GROUP_FINISH_TRIP));
        assertEquals(Map.of(), arrivals);
        assertEquals(0, sim.metrics.total(Event.TRESPASS));
    }

    @Test
    void onAFollowerHeldAtTheGateFinishesToItsSlotAtTheGoalFirstThenFollows() {
        NavGroup group = group(8, true);
        Results back = new Results();
        List<SimMover> held = heldAtTheGateWhenTheLeaderTurnsBack(group, back);
        int finishing = group.finishing();
        assertTrue(finishing >= held.size(), "every held follower finishes its trip");
        Map<Integer, Double> furthest = new HashMap<>();
        boolean leaderWaitedForFinishing = false;
        while (!back.ended() && sim.tick < 12000) {
            sim.tick();
            for (SimMover m : held) {
                furthest.merge(m.id(), m.position.x(), Math::max);
            }
            leaderWaitedForFinishing |= group.holding() && group.finishing() > 0;
        }

        assertInstanceOf(NavigationResult.Arrived.class, back.last());
        for (SimMover m : held) {
            int slot = sim.movers.indexOf(m); // follower i (from 1) has its slot 2i behind the leader's goal
            assertEquals(100.5 - 2 * slot, furthest.get(m.id()), 1.2, m.name() + " reached its slot at the goal");
            assertEquals(1, arrivals.get(m.id()), m.name() + " reported once");
            assertTrue(m.position.x() < 20, m.name() + " followed the group back afterwards, at " + m.position);
        }
        assertEquals(finishing, arrivals.size(), "only the followers that finished a trip report");
        assertEquals(finishing, sim.metrics.total(Event.GROUP_FINISH_TRIP));
        for (NavPoint goal : arrivalGoals) {
            assertEquals(feet(100.5, 0.5), goal, "the goal of the order they finished");
        }
        assertFalse(leaderWaitedForFinishing, "the leader does not wait for finishing followers");
        assertEquals(0, group.finishing());
        assertTrue(sim.log.stream().anyMatch(l -> l.contains("finished its trip at ")));
        assertEquals(0, sim.metrics.total(Event.TRESPASS));
    }

    @Test
    void aReplacedOrderBeforeTheLeaderArrivedReleasesTheFollowersAsToday() {
        gatedRoad();
        NavGroup group = group(6, true);
        group.navigate(feet(100.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > 66, 3000));
        gate(true);
        sim.run(40);
        Results back = new Results();
        group.navigate(feet(0.5, 0.5), back); // the leader has not arrived: scope of 4b.3 item 11
        assertInstanceOf(NavigationResult.Cancelled.class, results.last());
        assertEquals(0, group.finishing());
        gate(false);
        assertTrue(sim.runUntil(back::ended, 6000));

        assertEquals(0, sim.metrics.total(Event.GROUP_FINISH_TRIP));
        assertEquals(Map.of(), arrivals);
    }

    @Test
    void onAFollowerHeldAtADistrictEdgeFinishesWhenEntryReturns() {
        sim.world.regions = (x, z) -> x >= 50 ? Set.of("keep") : Set.of();
        NavGroup group = group(6, true);
        group.navigate(feet(100.5, 0.5), results);
        assertTrue(sim.runUntil(() -> leader().position.x() > 53, 2000));
        List<SimMover> held = sim.movers.stream().skip(1).filter(m -> m.position.x() < 50).toList();
        assertTrue(held.size() >= 2);
        sim.rules.deniedRegions.add("keep");
        sim.service.onAvailabilityChanged();
        sim.groups.onRulesChanged();
        assertTrue(sim.runUntil(results::ended, 3000));
        Results back = new Results();
        group.navigate(feet(0.5, 0.5), back);
        sim.run(200);
        for (SimMover m : held) {
            assertTrue(m.position.x() < 50, m.name() + " waits at the edge while entry is denied");
        }
        assertEquals(held.size(), group.finishing());

        sim.rules.deniedRegions.clear();
        sim.service.onAvailabilityChanged();
        sim.groups.onRulesChanged();
        Map<Integer, Double> furthest = new HashMap<>();
        while (!back.ended() && sim.tick < 12000) {
            sim.tick();
            for (SimMover m : held) {
                furthest.merge(m.id(), m.position.x(), Math::max);
            }
        }

        for (SimMover m : held) {
            assertTrue(furthest.get(m.id()) > 85, m.name() + " finished its trip into the keep: " + furthest.get(m.id()));
            assertEquals(1, arrivals.get(m.id()));
        }
        assertEquals(0, sim.metrics.total(Event.TRESPASS));
    }

    @Test
    void theToggleIsResolvedForTheGroupAndTheCommandCanSetIt() {
        assertFalse(GroupSettings.defaults().followersFinishTrip(), "config default false (decided 2026-11-15)");
        assertTrue(GroupSettings.defaults().withFollowersFinishTrip(true).followersFinishTrip());
        assertTrue(column(true).withFormation(Formation.column(1.5)).followersFinishTrip(), "kept with a formation");
    }
}
