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
import net.knightsandkings.knk.core.npc.nav.BlockingElement;
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

            @Override
            public boolean edgeBlocked(AccessSubject subject, RoadNetworkSnapshot snapshot,
                                       net.knightsandkings.knk.core.domain.roads.RoadEdge edge) {
                return policy.check(edge).isBlocked();
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
        assertNull(plan.blocking());
        assertEquals(List.of(new RoadPlanner.GateCrossing(NavigationTestNetwork.GATE_DOOR, floor(200, 0))), plan.gates(),
            "the gate on the way, placed where its road stretch ends");
    }

    @Test
    void aRouteWithoutGatesNamesNone() {
        assertTrue(plan(floor(0, 0), floor(90, 0)).gates().isEmpty());
    }

    @Test
    void aBlockedRouteNamesWhatBlocksAndKeepsTheWayUpToIt() {
        policy = edge -> edge.id() == NavigationTestNetwork.E_C_CASTLE
            ? EdgeVerdict.blocked("you may not enter Kardenna Castle",
                EdgeVerdict.Cause.domain(NavigationTestNetwork.CASTLE_DOMAIN, "Kardenna Castle"))
            : EdgeVerdict.open();

        RoadPlan plan = plan(floor(0, 0), floor(200, 200));

        assertEquals(RoadPlanner.Status.BLOCKED, plan.status());
        BlockingElement blocking = plan.blocking();
        assertEquals(BlockingElement.Kind.DOMAIN, blocking.kind());
        assertEquals("Kardenna Castle", blocking.name());
        assertEquals(String.valueOf(NavigationTestNetwork.CASTLE_DOMAIN), blocking.id());
        assertEquals(Set.of(NavigationTestNetwork.CASTLE_REGION), blocking.regionIds());
        assertTrue(plan.stillBlocked().test(npc), "asked again later: the castle edge is still blocked");
        policy = AccessPolicy.ALL_OPEN;
        assertTrue(!plan.stillBlocked().test(npc), "and open once the rule is gone");
        assertEquals("you may not enter Kardenna Castle", plan.reason());
        NavPoint reached = plan.line().get(plan.line().size() - 1);
        assertTrue(reached.z() < 200 && !plan.line().isEmpty(), "as close as the open roads go, short of the castle: " + reached);
    }

    @Test
    void aClosedGateIsTheBlockingElementWithItsDoor() {
        policy = edge -> edge.gateDoorIds().contains(NavigationTestNetwork.GATE_DOOR)
            ? EdgeVerdict.blocked("the West Gate is closed", EdgeVerdict.Cause.gate(NavigationTestNetwork.GATE_DOOR, "the West Gate"))
            : edge.id() == NavigationTestNetwork.E_BD ? EdgeVerdict.blocked("closed road", EdgeVerdict.Cause.flag("closed", "closed road"))
            : EdgeVerdict.open();

        RoadPlan plan = plan(floor(0, 0), floor(200, 0));

        assertEquals(RoadPlanner.Status.BLOCKED, plan.status());
        assertEquals(BlockingElement.Kind.GATE, plan.blocking().kind());
        assertTrue(plan.blocking().hasGate(NavigationTestNetwork.GATE_DOOR));
        assertEquals("the West Gate", plan.blocking().name());
        NavPoint reached = plan.line().get(plan.line().size() - 1);
        assertEquals(100, reached.x(), 1e-6, "the partial route leads to the gate's side of the road");
        assertTrue(plan.gates().isEmpty(), "the partial route itself passes no gate");
    }

    @Test
    void ruleChangeListenersHearGateAndAvailabilityChangesUntilTheyUnsubscribe() {
        NavigationService service = service();
        List<String> heard = new ArrayList<>();
        Runnable unsubscribe = service.addRuleChangeListener(new net.knightsandkings.knk.core.npc.nav.RuleChangeListener() {
            @Override
            public void gateChanged(int doorId) {
                heard.add("gate " + doorId);
            }

            @Override
            public void availabilityChanged() {
                heard.add("availability");
            }
        });
        service.addRuleChangeListener(new net.knightsandkings.knk.core.npc.nav.RuleChangeListener() {
            @Override
            public void gateChanged(int doorId) {
                throw new IllegalStateException("a broken listener");
            }

            @Override
            public void availabilityChanged() {
                throw new IllegalStateException("a broken listener");
            }
        });

        service.onGateChanged(7);
        service.onAvailabilityChanged();
        assertEquals(List.of("gate 7", "availability"), heard, "and a listener that throws does not stop road navigation");

        unsubscribe.run();
        service.onGateChanged(7);
        service.onAvailabilityChanged();
        assertEquals(2, heard.size());
    }

    @Test
    void theAccessRulesPortAsksThePolicyFactory() {
        NavigationService open = service();
        assertTrue(open.accessRules().mayEnter(npc).test("anything"), "a factory without subject rules allows everything");
        assertTrue(!open.accessRules().gateBlocks(npc, 7));
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
