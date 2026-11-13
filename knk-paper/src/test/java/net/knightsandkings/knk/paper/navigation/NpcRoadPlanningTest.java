package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcAccessSubject;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RouteResult;
import net.knightsandkings.knk.paper.config.NavigationConfig;

/** PandiO/knk-workspace#360: the road route of a mover with its own access subject ({@code planRoadFor}). */
class NpcRoadPlanningTest {

    private final NavigationTestNetwork network = new NavigationTestNetwork();
    private final List<AccessSubject> asked = new ArrayList<>();
    private final Deque<Runnable> routing = new ArrayDeque<>();
    private final Deque<Runnable> mainThread = new ArrayDeque<>();
    private AccessPolicy policy = AccessPolicy.ALL_OPEN;
    private final NpcAccessSubject npc = new NpcAccessSubject("guard-1", NpcAccessProfile.DEFAULT,
        NavigationTestNetwork.WORLD, Set.of("town_1"));

    private NavigationService service() {
        NavigationService.PolicyFactory policies = new NavigationService.PolicyFactory() {
            @Override
            public AccessPolicy policyFor(Player player, RoadNetworkSnapshot snapshot) {
                throw new AssertionError("an NPC never borrows a player's policy");
            }

            @Override
            public AccessPolicy policyFor(AccessSubject subject, RoadNetworkSnapshot snapshot, Set<String> destination) {
                asked.add(subject);
                return policy;
            }
        };
        return new NavigationService(new NavigationService.Deps(null, NavigationConfig.defaults(),
            world -> NavigationTestNetwork.WORLD.equals(world) ? network.snapshot : null, policies,
            (world, id) -> java.util.Optional.empty(),
            new NavigationEligibility(uuid -> false, uuid -> false, uuid -> false), mock(NavigationHud.class),
            mock(TrailRenderer.class), mainThread::add, routing::add, () -> 0L, event -> { }, Logger.getLogger("test")));
    }

    private static NavPoint floor(double x, double z) {
        return new NavPoint(NavigationTestNetwork.WORLD, x, 64, z);
    }

    private RoadPlan plan(NavPoint from, NavPoint to) {
        var future = service().planRoadFor(npc, from, to);
        while (!future.isDone() && !(routing.isEmpty() && mainThread.isEmpty())) {
            if (!routing.isEmpty()) {
                routing.poll().run();
            } else {
                mainThread.poll().run();
            }
        }
        assertTrue(future.isDone(), "the plan is delivered");
        return future.join();
    }

    @Test
    void theRouteIsPlannedOffTheMainThreadWithTheSubjectsPolicyAndDeliveredOnIt() {
        var future = service().planRoadFor(npc, floor(0, 0), floor(200, 0));

        assertSame(npc, asked.get(0), "the policy is built for the NPC, on the calling (main) thread");
        assertEquals(1, routing.size());
        assertTrue(mainThread.isEmpty());
        routing.poll().run();
        assertTrue(!future.isDone() && mainThread.size() == 1, "the result waits for the main thread");
        mainThread.poll().run();

        RoadPlan plan = future.join();
        assertEquals(RoadPlanner.Status.FOUND, plan.status());
        assertEquals(floor(0, 0), plan.line().get(0));
        assertEquals(floor(200, 0), plan.line().get(plan.line().size() - 1));
        assertNull(plan.blockingElement());
    }

    @Test
    void aBlockedRouteNamesWhatBlocksAndKeepsTheWayUpToIt() {
        policy = edge -> edge.id() == NavigationTestNetwork.E_C_CASTLE
            ? EdgeVerdict.blocked("you may not enter Kardenna Castle",
                EdgeVerdict.Cause.domain(NavigationTestNetwork.CASTLE_DOMAIN, "Kardenna Castle"))
            : EdgeVerdict.open();

        RoadPlan plan = plan(floor(0, 0), floor(200, 200));

        assertEquals(RoadPlanner.Status.BLOCKED, plan.status());
        assertEquals("Kardenna Castle", plan.blockingElement());
        assertEquals("you may not enter Kardenna Castle", plan.reason());
        NavPoint reached = plan.line().get(plan.line().size() - 1);
        assertTrue(reached.z() < 200 && !plan.line().isEmpty(), "as close as the open roads go, short of the castle: " + reached);
    }

    @Test
    void noNetworkAndAnEndOffTheRoadAreTheirOwnAnswers() {
        RoadPlan noNetwork = service().planRoadFor(npc, new NavPoint("nether", 0, 64, 0), new NavPoint("nether", 9, 64, 0))
            .join();
        assertEquals(RoadPlanner.Status.NO_NETWORK, noNetwork.status());

        RoadPlan offRoad = service().planRoadFor(npc, floor(0, 0), floor(200, -500)).join();
        assertEquals(RoadPlanner.Status.OFF_ROAD, offRoad.status());
        assertTrue(offRoad.reason().startsWith("end too far from a road"), offRoad.reason());
        assertTrue(routing.isEmpty(), "nothing to route");
    }

    @Test
    void aPolicyThatCannotBeBuiltIsAnErrorNotACrash() {
        policy = null;
        RoadPlan plan = plan(floor(0, 0), floor(200, 0));
        assertEquals(RoadPlanner.Status.ERROR, plan.status());
    }

    @Test
    void theRoutersOtherAnswersAreNotRuleBlocks() {
        RoadPlan apart = NavigationService.roadPlanOf("world", RouteResult.differentComponents());
        assertEquals(RoadPlanner.Status.DIFFERENT_COMPONENTS, apart.status());
        assertTrue(apart.line().isEmpty());
        assertEquals(RoadPlanner.Status.NO_ROUTE, NavigationService.roadPlanOf("world", RouteResult.noRoute()).status());
    }
}
