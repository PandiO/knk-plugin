package net.knightsandkings.knk.core.domain.arenas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/** KNG-210: the arena record's helpers; rev. 6: its areas. */
class KnkArenaTest {

    private static final KnkLocation SPAWN = new KnkLocation(1, "s", 0.0, 64.0, 0.0, 0f, 0f, "world");

    @Test
    void usableBattlegroundsAreEnabledWithBothSpawnsAndARegion_InSortOrder() {
        KnkArena arena = ArenaFixtures.arena(1, "arena_2", List.of(
                ArenaFixtures.battleground(4, 1, "bg4", 3, SPAWN, SPAWN, "r4"),
                ArenaFixtures.battleground(1, 1, "bg1", 0, SPAWN, SPAWN, "r1"),
                new KnkArenaArea(2, 1, KnkArenaArea.Kind.BATTLEGROUND, "bg2", null, 1, false, SPAWN, SPAWN, null, null, null,
                        ArenaFixtures.regions(2, "r2")),
                ArenaFixtures.battleground(3, 1, "bg3", 2, SPAWN, null, "r3"),
                ArenaFixtures.battleground(5, 1, "bg5", 4, SPAWN, SPAWN)));

        assertEquals(List.of(1, 4), arena.usableBattlegrounds().stream().map(KnkArenaArea::id).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), arena.battlegrounds().stream().map(KnkArenaArea::id).toList());
        assertEquals("bg2", arena.battlegrounds().get(1).displayName());
    }

    @Test
    void theLobbyAndTheSpectateTargetAreEnabledAreasWithAnArrival() {
        KnkArena arena = ArenaFixtures.arena(1, "arena_2", List.of(
                ArenaFixtures.spectator(6, 1, "North", null, null, null, "n"),
                ArenaFixtures.spectator(7, 1, "South", SPAWN, 3, 10, "s"),
                new KnkArenaArea(8, 1, KnkArenaArea.Kind.LOBBY, "lobby", null, 0, false, null, null, SPAWN, null, null, List.of())));

        assertTrue(arena.lobby().isEmpty());
        assertEquals(null, arena.lobbyLocation());
        assertEquals(7, arena.spectateTarget().orElseThrow().id());
        assertEquals(List.of(6, 7), arena.spectatorAreas().stream().map(KnkArenaArea::id).toList());
        assertEquals(ArenaFixtures.LOBBY, ArenaFixtures.arena(2, "a").lobbyLocation());
    }

    @Test
    void ownRegionsAreTheMainRegionAndTheSubRegions() {
        KnkArena base = ArenaFixtures.arena(1, "arena_2");
        KnkArena arena = new KnkArena(1, "arena_2", "n", "arena_2", "world", 2, null, null, true, 3, 3, 60, true, true,
                10_000, 9, 50, 5, null, null, 3, 10, 25_000, true, false, false, false, null, 30, 10, 300, 180, 5,
                true, true, null, base.areas(), List.of("domain_5_sub_1", " "));

        assertEquals(List.of("arena_2", "domain_5_sub_1"), arena.ownRegionIds());
        assertTrue(arena.isOwnRegion("DOMAIN_5_SUB_1"));
        assertFalse(arena.isOwnRegion("lobby_1"));
        assertTrue(arena.areas().getFirst().hasRegion("WORLD", "Lobby_1"));
        assertFalse(arena.areas().getFirst().hasRegion("nether", "lobby_1"));
    }

    @Test
    void areaKindsParseInAnyCase() {
        assertEquals(Optional.of(KnkArenaArea.Kind.SPECTATOR), KnkArenaArea.Kind.parse(" spectator "));
        assertEquals(Optional.empty(), KnkArenaArea.Kind.parse("ARENA"));
    }

    @Test
    void gearModesFollowTheAllowFlags() {
        KnkArena arena = ArenaFixtures.arena(1, "arena_2");
        assertTrue(arena.allows(KnkArena.DuelGearMode.OWN_GEAR_KEEP));
        assertFalse(arena.allows(KnkArena.DuelGearMode.OWN_GEAR_DROP));
        assertFalse(arena.allows(KnkArena.DuelGearMode.FIXED_KIT));
        assertFalse(arena.allows(null));
    }

    @Test
    void gearModeNamesParseInAnyCase() {
        assertEquals(Optional.of(KnkArena.DuelGearMode.KIT_CHOICE), KnkArena.DuelGearMode.parse(" kit_choice "));
        assertEquals(Optional.empty(), KnkArena.DuelGearMode.parse("SWORDS"));
        assertEquals(Optional.empty(), KnkArena.DuelGearMode.parse(null));
    }

    @Test
    void exitFallsBackToTheDomainLocation_AndListsAreNeverNull() {
        KnkArena arena = new KnkArena(1, "k", "n", "r", "world", 2, SPAWN, null, true, 3, 3, 60, true, true,
                10_000, 9, 50, 5, null, null, 3, 10, 25_000, true, false, false, false, null, 30, 10, 300, 180, 5,
                true, true, null, null, null);
        assertEquals(SPAWN, arena.exitOrDomainLocation());
        assertTrue(arena.battlegrounds().isEmpty());
        assertTrue(arena.subRegionIds().isEmpty());
        assertEquals(KnkArena.DuelGearMode.OWN_GEAR_KEEP, arena.defaultGearMode());
        assertTrue(arena.defaultKit().isEmpty());
    }
}
