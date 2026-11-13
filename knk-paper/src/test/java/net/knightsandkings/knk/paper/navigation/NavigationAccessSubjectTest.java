package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcAccessSubject;
import net.knightsandkings.knk.core.regions.DomainAccessEvaluator;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;
import net.knightsandkings.knk.core.roads.route.GateAvailability;
import net.knightsandkings.knk.paper.gates.GatePassThroughRules;
import net.knightsandkings.knk.paper.regions.RegionIds;
import net.knightsandkings.knk.paper.siege.SiegeGateController;

/**
 * PandiO/knk-workspace#360: the access code asked through an {@link AccessSubject}. A player behind
 * {@link PlayerAccessSubject} gets the verdicts the player gets today; an NPC gets the same rules with its own
 * profile, and never a player's rights.
 */
class NavigationAccessSubjectTest {

    private static final int OPEN = 1, CLOSED_PASS_THROUGH = 2, CLOSED = 3, SIEGE_LOCKED = 4, SIEGE_CARRIES = 5, JAMMED = 6;
    private static final List<Integer> DOORS = List.of(OPEN, CLOSED_PASS_THROUGH, CLOSED, SIEGE_LOCKED, SIEGE_CARRIES,
        JAMMED, 99);

    // fields, not inline mocks: Location holds its world weakly (see knk-plugin CLAUDE.md)
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final GateManager gates = mock(GateManager.class);
    private final SiegeGateController siege = mock(SiegeGateController.class);
    private final RegionIds regionIds = mock(RegionIds.class);
    private final RegionDomainResolver resolver = mock(RegionDomainResolver.class);
    private final NavigationTestNetwork network = new NavigationTestNetwork();
    private Set<String> nodes = Set.of();
    private boolean bypass;
    private boolean siegeMember;
    private boolean siegeControl;
    private NavigationAccess access;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn(NavigationTestNetwork.WORLD);
        when(player.getName()).thenReturn("Steve");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Location location = new Location(world, 0.5, 65, 0.5);
        when(player.getLocation()).thenReturn(location);
        when(regionIds.at(location)).thenReturn(Set.of("town_1"));
        door(OPEN, 10, AnimationState.OPEN, false, false);
        door(CLOSED_PASS_THROUGH, 20, AnimationState.CLOSED, false, true);
        door(CLOSED, 30, AnimationState.CLOSED, false, false);
        door(SIEGE_LOCKED, 40, AnimationState.CLOSED, false, true);
        CachedGateDoor carried = door(SIEGE_CARRIES, 50, AnimationState.CLOSED, false, false);
        door(JAMMED, 60, AnimationState.CLOSED, true, true);
        door(NavigationTestNetwork.GATE_DOOR, 70, AnimationState.CLOSED, false, true);
        when(siege.isLocked(40)).thenReturn(true);
        when(siege.isLocked(50)).thenReturn(true);
        when(siege.isLockdownMember(eq(player), anyInt())).thenAnswer(call -> siegeMember);
        when(siege.mayControl(eq(player), anyInt())).thenAnswer(call -> siegeControl);
        when(siege.canCarryNonMember(carried)).thenReturn(true);
        // the player's own region is in the domain cache (a lookup that misses would ask Bukkit for the thread)
        when(resolver.getDomainByRegionIdNoRefresh(NavigationTestNetwork.WORLD, "town_1"))
            .thenReturn(Optional.of(new DomainSnapshot(1, "Town", null, "town_1", true, true, "Town", Set.of(), Set.of(),
                Set.of(), Set.of())));
        GatePassThroughRules.setPermissionCheck((p, node) -> nodes.contains(node));
        access = new NavigationAccess(gates, () -> siege, regionIds, resolver, p -> bypass, new DomainAccessEvaluator());
    }

    @AfterEach
    void tearDown() {
        GatePassThroughRules.setPermissionCheck(null);
    }

    private CachedGateDoor door(int id, int structure, AnimationState state, boolean jammed, boolean passThrough) {
        CachedGateDoor door = mock(CachedGateDoor.class);
        when(door.getGateStructureId()).thenReturn(structure);
        when(door.getCurrentState()).thenReturn(state);
        when(door.isJammed()).thenReturn(jammed);
        when(door.isEffectivelyAllowPassThrough()).thenReturn(passThrough);
        when(door.getName()).thenReturn("Gate " + id);
        when(gates.getGate(id)).thenReturn(door);
        return door;
    }

    private void castleDeniesEntry() {
        when(resolver.getDomainByRegionIdNoRefresh(NavigationTestNetwork.WORLD, NavigationTestNetwork.CASTLE_REGION))
            .thenReturn(Optional.of(new DomainSnapshot(NavigationTestNetwork.CASTLE_DOMAIN, "Kardenna Castle", null,
                NavigationTestNetwork.CASTLE_REGION, false, true, "Castle", Set.of(), Set.of(), Set.of(), Set.of())));
    }

    private static NpcAccessSubject npc(NpcAccessProfile profile, String... regions) {
        return new NpcAccessSubject("guard-1", profile, NavigationTestNetwork.WORLD, Set.of(regions));
    }

    @Test
    void aPlayerSubjectAnswersWhatThePlayerAnswers() {
        PlayerAccessSubject subject = access.subjectOf(player);

        assertEquals("Steve", subject.name());
        assertEquals(Optional.of(player.getUniqueId()), subject.playerId());
        assertEquals(NavigationTestNetwork.WORLD, subject.world());
        assertEquals(Set.of("town_1"), subject.currentRegions());
        assertFalse(subject.bypassesDomainRules());
        assertFalse(subject.gateAdmin());
        nodes = Set.of(GatePassThroughRules.ADMIN_NODE, GatePassThroughRules.USE_NODE);
        bypass = true;
        assertTrue(subject.gateAdmin(), "read when asked, not when the subject was made");
        assertTrue(subject.mayUseGates());
        assertTrue(subject.bypassesDomainRules());
        assertEquals(AccessSubject.RegionOverride.RULES, subject.regionOverride("anything"));
    }

    @Test
    void aPlayerSubjectGetsThePlayersGateVerdictsInEveryCase() {
        List<Set<String>> rights = List.of(Set.of(), Set.of(GatePassThroughRules.USE_NODE),
            Set.of(GatePassThroughRules.ADMIN_NODE));
        for (Set<String> held : rights) {
            for (boolean member : new boolean[] {false, true}) {
                for (boolean control : new boolean[] {false, true}) {
                    nodes = held;
                    siegeMember = member;
                    siegeControl = control;
                    GateAvailability asPlayer = access.gateAvailability(player, DOORS);
                    GateAvailability asSubject = access.gateAvailability(access.subjectOf(player), DOORS);
                    for (int door : DOORS) {
                        assertEquals(asPlayer.decide(door), asSubject.decide(door),
                            "door " + door + " with " + held + ", siege member " + member + ", controls " + control);
                    }
                }
            }
        }
    }

    @Test
    void aPlayerSubjectGetsThePlayersRoadPolicy() {
        castleDeniesEntry();
        for (boolean bypassing : new boolean[] {false, true}) {
            bypass = bypassing;
            nodes = bypassing ? Set.of(GatePassThroughRules.USE_NODE) : Set.of();
            AccessPolicy asPlayer = access.policyFor(player, network.snapshot, null);
            AccessPolicy asSubject = access.policyFor(access.subjectOf(player), network.snapshot, null);
            network.snapshot.edges().forEach(edge ->
                assertEquals(asPlayer.check(edge), asSubject.check(edge), "edge " + edge.id() + ", bypass " + bypassing));
        }
        bypass = false;
        assertTrue(access.policyFor(access.subjectOf(player), network.snapshot, null)
            .check(network.snapshot.requireEdge(NavigationTestNetwork.E_C_CASTLE)).isBlocked(), "the rule is really asked");
    }

    @Test
    void anNpcPassesOpenGatesOnlyAndIsNoPartOfASiege() {
        nodes = Set.of(GatePassThroughRules.ADMIN_NODE, GatePassThroughRules.USE_NODE); // a player's rights: not the NPC's
        siegeMember = true;
        siegeControl = true;

        GateAvailability rule = access.gateAvailability(npc(NpcAccessProfile.DEFAULT), DOORS);

        assertTrue(rule.decide(OPEN).isOpen());
        assertTrue(rule.decide(99).isOpen(), "a door the gate cache does not know cannot be checked");
        for (int door : List.of(CLOSED_PASS_THROUGH, CLOSED, SIEGE_LOCKED, SIEGE_CARRIES, JAMMED)) {
            EdgeVerdict verdict = rule.decide(door);
            assertTrue(verdict.isBlocked(), "door " + door + ": " + verdict);
        }
        verify(siege, never()).isLockdownMember(any(), anyInt());
        verify(siege, never()).mayControl(any(), anyInt());
        verify(siege, never()).canCarryNonMember(any());
    }

    @Test
    void anNpcsRoadPolicyFollowsTheDomainRulesFromWhereItStands() {
        castleDeniesEntry();
        var castleEdge = network.snapshot.requireEdge(NavigationTestNetwork.E_C_CASTLE);
        var gateEdge = network.snapshot.requireEdge(NavigationTestNetwork.E_BC);

        AccessPolicy outside = access.policyFor(npc(NpcAccessProfile.DEFAULT), network.snapshot, null);
        assertTrue(outside.check(castleEdge).isBlocked());
        assertTrue(outside.check(gateEdge).isBlocked(), "the closed gate, pass-through or not");
        assertTrue(outside.check(network.snapshot.requireEdge(NavigationTestNetwork.E_AB)).isOpen());

        AccessPolicy inside = access.policyFor(npc(NpcAccessProfile.DEFAULT, NavigationTestNetwork.CASTLE_REGION),
            network.snapshot, null);
        assertTrue(inside.check(castleEdge).isOpen(), "already in the castle: not an entry");
        verify(regionIds, never()).at(any(Location.class)); // no player's position is asked for an NPC
    }

    @Test
    void anNpcsListsComeBeforeTheDomainRules() {
        castleDeniesEntry();
        var castleEdge = network.snapshot.requireEdge(NavigationTestNetwork.E_C_CASTLE);

        NpcAccessProfile allowed = new NpcAccessProfile("castle-guard", false, true,
            Set.of(NavigationTestNetwork.CASTLE_REGION), Set.of());
        assertTrue(access.policyFor(npc(allowed), network.snapshot, null).check(castleEdge).isOpen(),
            "allowed in, whatever the domain says");

        NpcAccessProfile bypassing = new NpcAccessProfile("staff", true, true, Set.of(), Set.of());
        assertTrue(access.policyFor(npc(bypassing), network.snapshot, null).check(castleEdge).isOpen());

        NpcAccessProfile denied = new NpcAccessProfile("outsider", true, true, Set.of(),
            Set.of(NavigationTestNetwork.CASTLE_REGION));
        EdgeVerdict verdict = access.policyFor(npc(denied), network.snapshot, null).check(castleEdge);
        assertTrue(verdict.isBlocked(), "the deny list holds even with the bypass");
        assertEquals(NavigationTestNetwork.CASTLE_REGION, verdict.cause().name());
    }
}
