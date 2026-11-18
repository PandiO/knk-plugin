package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcAccessSubject;
import net.knightsandkings.knk.core.npc.nav.RegionRule;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.paper.navigation.NavigationAccess;
import net.knightsandkings.knk.paper.regions.RegionIds;
import net.knightsandkings.knk.paper.siege.SiegeGateController;

/**
 * PandiO/knk-workspace#701 on the Paper side: the rule Paper's {@link NavigationAccess} hands the NPC walking checks
 * (through road navigation's {@code accessRules()}, which passes {@code mayEnter} on as it is) knows the exit rule, and
 * agrees with the road router's own exit verdict for every profile: the default, the bypass and the allow list.
 */
class NpcExitRuleAccessTest {

    private static final String WORLD = "world";
    private static final String KEEP = "structure_keep";
    private static final int KEEP_DOMAIN = 77;
    private static final int INSIDE = 10, OUT = 11;

    private final GateManager gates = mock(GateManager.class);
    private final SiegeGateController siege = mock(SiegeGateController.class);
    private final RegionIds regionIds = mock(RegionIds.class);
    private final RegionDomainResolver resolver = mock(RegionDomainResolver.class);
    private NavigationAccess access;
    private RoadNetworkSnapshot snapshot;
    /** Road navigation's {@code accessRules()}: {@code mayEnter} and the rest handed on to the policy factory. */
    private AccessRules rules;

    @BeforeEach
    void setUp() {
        when(resolver.getDomainByRegionIdNoRefresh(WORLD, KEEP)).thenReturn(Optional.of(new DomainSnapshot(KEEP_DOMAIN,
            "Kardenna Keep", null, KEEP, true, false, "Structure", Set.of(), Set.of(), Set.of(), Set.of())));
        access = new NavigationAccess(gates, () -> siege, regionIds, resolver, p -> false, new DomainAccessEvaluator());
        rules = new AccessRules() {
            @Override
            public Predicate<String> mayEnter(AccessSubject subject) {
                return access.mayEnter(subject);
            }

            @Override
            public boolean gateBlocks(AccessSubject subject, int doorId) {
                return access.gateBlocks(subject, doorId);
            }
        };
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder(WORLD);
        b.addProfile(new RoadNetworkSnapshot.Profile(1, "Main", RoadClass.MAIN, 1.0));
        b.addNode(new RoadNode(1, 0, 64, 0, RoadNodeKind.JUNCTION, "Keep yard", 1));
        b.addNode(new RoadNode(2, 50, 64, 0, RoadNodeKind.JUNCTION, "Keep gate", 1));
        b.addNode(new RoadNode(3, 100, 64, 0, RoadNodeKind.JUNCTION, "Market", 1));
        b.addEdge(edge(INSIDE, 1, 2, 0, 50, List.of(KEEP)));
        b.addEdge(edge(OUT, 2, 3, 50, 100, List.of()));
        snapshot = b.build();
    }

    private static RoadEdge edge(int id, int from, int to, int x0, int x1, List<String> regions) {
        return new RoadEdge(id, from, to, List.of(new int[] {x0, 64, 0}, new int[] {x1, 64, 0}), x1 - x0, 3,
            OptionalInt.of(1), OptionalInt.empty(), 1.0, EnumSet.noneOf(RoadEdgeFlag.class), List.of(),
            regions.isEmpty() ? List.of() : List.of(KEEP_DOMAIN), regions, RoadEdgeSource.DETECTED, false);
    }

    private static NpcAccessSubject npc(NpcAccessProfile profile, String... regions) {
        return new NpcAccessSubject("guard-1", profile, WORLD, Set.of(regions));
    }

    @Test
    void theWalkingChecksSeeTheExitRuleOfTheRegionAnNpcStandsIn() {
        RegionRule inside = rules.regionRule(npc(NpcAccessProfile.DEFAULT, KEEP));
        RegionRule outside = rules.regionRule(npc(NpcAccessProfile.DEFAULT));

        assertFalse(inside.mayLeave(KEEP), "the keep denies leaving");
        assertEquals(Set.of(KEEP), inside.exitDenied());
        assertTrue(inside.test(KEEP), "a region it stands in is not entered");
        assertTrue(outside.test(KEEP), "the keep allows entry");
        assertTrue(outside.exitDenied().isEmpty());
    }

    @Test
    void theExitRuleAgreesWithTheRoadRouterForEveryProfile() {
        RoadEdge out = snapshot.requireEdge(OUT);
        RoadEdge in = snapshot.requireEdge(INSIDE);
        for (NpcAccessProfile profile : List.of(NpcAccessProfile.DEFAULT,
            new NpcAccessProfile("staff", true, true, Set.of(), Set.of()),
            new NpcAccessProfile("keeper", false, true, Set.of(KEEP), Set.of()))) {
            NpcAccessSubject subject = npc(profile, KEEP);
            boolean routerLetsItLeave = access.policyFor(subject, snapshot, null).check(out).isOpen();
            assertEquals(routerLetsItLeave, rules.regionRule(subject).mayLeave(KEEP), profile.id());
            assertTrue(access.policyFor(subject, snapshot, null).check(in).isOpen(), "staying inside: " + profile.id());
        }
        assertFalse(rules.regionRule(npc(NpcAccessProfile.DEFAULT, KEEP)).mayLeave(KEEP));
    }
}
