package net.knightsandkings.knk.core.domain.arenas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/** KNG-210: the arena record's helpers. */
class KnkArenaTest {

    private static final KnkLocation SPAWN = new KnkLocation(1, "s", 0.0, 64.0, 0.0, 0f, 0f, "world");

    @Test
    void usableBattlegroundsAreEnabledWithBothSpawns() {
        KnkArena arena = ArenaFixtures.arena(1, "arena_2", List.of(
                new KnkArenaBattleground(1, 1, "bg1", "a", "r1", "world", 0, true, false, SPAWN, SPAWN),
                new KnkArenaBattleground(2, 1, "bg2", "b", "r2", "world", 1, false, false, SPAWN, SPAWN),
                new KnkArenaBattleground(3, 1, "bg3", "c", "r3", "world", 2, true, false, SPAWN, null)), List.of());

        assertEquals(List.of(1), arena.usableBattlegrounds().stream().map(KnkArenaBattleground::id).toList());
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
        KnkArena arena = new KnkArena(1, "k", "n", "r", "world", 2, SPAWN, null, null, true, 3, 3, 60, true, true,
                10_000, 9, 50, 5, null, null, 3, 10, 25_000, true, false, false, false, null, 30, 10, 300, 180, 5,
                true, true, null, null, null);
        assertEquals(SPAWN, arena.exitOrDomainLocation());
        assertTrue(arena.battlegrounds().isEmpty());
        assertEquals(KnkArena.DuelGearMode.OWN_GEAR_KEEP, arena.defaultGearMode());
        assertTrue(arena.defaultKit().isEmpty());
    }
}
