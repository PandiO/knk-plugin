package net.knightsandkings.knk.core.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.npc.nav.AccessSubject.RegionOverride;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;

class NpcAccessSubjectTest {

    private static final NpcAccessProfile GUARD = new NpcAccessProfile("guard", false, false,
        Set.of("Castle_Yard"), Set.of("throne_room", "castle_yard"));

    private static RoadEdge edgeThrough(String... regions) {
        return new RoadEdge(1, 1, 2, List.of(new int[] {0, 64, 0}, new int[] {10, 64, 0}), 10, 3, OptionalInt.of(1),
            OptionalInt.empty(), 1.0, EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(), List.of(regions),
            RoadEdgeSource.DETECTED, false);
    }

    @Test
    void theProfilesListsDecideBeforeTheDomainRulesAndDenyWins() {
        assertEquals(RegionOverride.DENY, GUARD.override("Throne_Room"), "ids compare without case");
        assertEquals(RegionOverride.DENY, GUARD.override("castle_yard"), "on both lists: deny wins");
        assertEquals(RegionOverride.RULES, GUARD.override("market"));
        assertEquals(RegionOverride.ALLOW, new NpcAccessProfile("p", false, true, Set.of("market"), null).override("MARKET"));
        assertEquals(RegionOverride.RULES, NpcAccessProfile.DEFAULT.override(null));
    }

    @Test
    void anNpcIsNoPlayerAndHasNoGateRights() {
        NpcAccessSubject subject = new NpcAccessSubject("guard-1", GUARD, "world", Set.of("town_1"));

        assertTrue(subject.playerId().isEmpty());
        assertFalse(subject.gateAdmin());
        assertFalse(subject.mayUseGates());
        assertFalse(subject.mayOpenDoor("world", 1, 2, 3), "the profile keeps doors shut");
        assertTrue(new NpcAccessSubject("n", NpcAccessProfile.DEFAULT, "world", null).mayOpenDoor("world", 1, 2, 3));
        assertEquals(Set.of("town_1"), subject.currentRegions());
        assertEquals("world", subject.world());
        assertFalse(subject.bypassesDomainRules());
    }

    @Test
    void anEdgeThroughADeniedRegionIsBlockedUnlessTheSubjectStandsInIt() {
        RoadEdge throne = edgeThrough("throne_room");

        EdgeVerdict outside = new SubjectRegionAvailability(
            new NpcAccessSubject("guard-1", GUARD, "world", Set.of())).check(throne);
        assertTrue(outside.isBlocked());
        assertEquals("throne_room", outside.cause().name());
        assertTrue(outside.message().contains("guard-1"));

        assertTrue(new SubjectRegionAvailability(
            new NpcAccessSubject("guard-1", GUARD, "world", Set.of("throne_room"))).check(throne).isOpen(),
            "already inside: not an entry");
        assertTrue(new SubjectRegionAvailability(
            new NpcAccessSubject("guard-1", GUARD, "world", Set.of())).check(edgeThrough("market")).isOpen());
        assertTrue(new SubjectRegionAvailability(
            new NpcAccessSubject("n", NpcAccessProfile.DEFAULT, "world", Set.of())).check(throne).isOpen());
    }
}
