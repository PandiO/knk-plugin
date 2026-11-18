package net.knightsandkings.knk.core.npc.nav.group;

import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.feet;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.Results;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.SimMover;

/**
 * Crowd awareness for groups (implementation plan 4c.4 item 4.2, link M4): a follower held up behind another NPC in its
 * lane counts as waiting, not as a stuck spell; the follower in front that is really stuck still opens one; with the
 * key off both do. The navigation service knows a leader's followers (so a leader keeps its turn at a narrow place).
 */
class NavGroupCrowdTest {

    /** A column a block apart, the leader never waiting for followers. */
    private static final GroupSettings COLUMN = noWaiting(GroupSettings.defaults().withFormation(Formation.column(1.0)));

    private static GroupSettings noWaiting(GroupSettings s) {
        return new GroupSettings(s.formation(), s.rejoinBlocks(), 0, s.catchUpSpeed(), s.hopBlocks(),
            s.noProgressTicks(), s.noRouteWaitTicks(), s.recheckTicks());
    }

    private static NpcNavigationService.Settings crowd(boolean on) {
        NpcNavigationService.Settings d = NpcNavigationService.Settings.defaults();
        return new NpcNavigationService.Settings(d.stretchBlocks(), d.hopBlocks(), d.noProgressTicks(), d.maxRetries(),
            d.noRouteWaitTicks(), d.walkOnlyBlocks(), d.recheckTicks(), d.stopOnTrespass(), d.cornerClearance(),
            d.waypointMargin(), d.recoveryWaitTicks(), d.corridorSteering(), d.corridorHalfWidth(), d.wallClearance(),
            d.localLegsClearance(), d.plazaLines(), d.plazaCorridorBlocks(), on);
    }

    /** Runs a column of 4 east and freezes its first two followers (the second held up behind the first). */
    private GroupSim jam(boolean crowdOn) {
        GroupSim sim = new GroupSim();
        sim.moverSettings = crowd(crowdOn);
        NavGroup group = sim.group(4, COLUMN);
        group.navigate(feet(200.5, 0.5), new Results());
        assertTrue(sim.runUntil(() -> sim.movers.get(0).position.x() > 30, 1000));
        SimMover front = sim.movers.get(1);
        SimMover behind = sim.movers.get(2);
        front.frozen = true;
        assertTrue(sim.runUntil(() -> behind.position.x() >= front.position.x() - 1.1, 200),
            "the second follower closes up on the first");
        behind.frozen = true; // collision on: it can't get past
        sim.run(120);
        return sim;
    }

    @Test
    void aFollowerBehindAWaitingOneOpensNoStuckSpell() {
        GroupSim sim = jam(true);
        assertEquals(1, sim.metrics.total(Event.STUCK_NO_PROGRESS), "only the follower in front is stuck: " + sim.log);
        assertTrue(sim.metrics.total(Event.CROWD_WAIT) >= 1, "the one behind waits");
        assertTrue(sim.log.stream().anyMatch(l -> l.contains("npc-3 waits behind another NPC")), sim.log.toString());
    }

    @Test
    void withTheKeyOffBothOpenASpell() {
        GroupSim sim = jam(false);
        assertEquals(2, sim.metrics.total(Event.STUCK_NO_PROGRESS), sim.log.toString());
        assertEquals(0, sim.metrics.total(Event.CROWD_WAIT));
    }

    @Test
    void theServiceKnowsALeadersFollowers() {
        GroupSim sim = new GroupSim();
        NavGroup group = sim.group(3, COLUMN);
        assertArrayEquals(new int[] {2, 3}, group.followerIds());
    }
}
