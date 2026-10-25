package net.knightsandkings.knk.core.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.arenas.ArenaFixtures;
import net.knightsandkings.knk.core.domain.arenas.KnkArena;

/** Arena duels rev. 6 (DESIGN §8.2): who may cross into or out of an arena's areas, over the union of their regions. */
class ArenaAreaAccessRulesTest {

    private static final int ARENA = 5, PIT = 6, STANDS = 7, LOBBY = 8;
    private static final UUID DUELIST = UUID.randomUUID(), OUTSIDER = UUID.randomUUID();

    private final KnkArena arena = ArenaFixtures.arena(ARENA, "arena_2", List.of(
            ArenaFixtures.battleground(PIT, ARENA, "bg1", 0, null, null, "pit_north", "pit_south"),
            ArenaFixtures.spectator(STANDS, ARENA, "Stands", null, null, null, "stands"),
            ArenaFixtures.lobby(LOBBY, ARENA, "hall", null)));

    /** A fake runtime. */
    private static final class Runtime implements ArenaAreaAccessRules.State {
        final Map<UUID, Integer> allocated = new HashMap<>();
        final Set<UUID> confined = new HashSet<>();
        final Set<Integer> reservedLobbies = new HashSet<>();
        final Set<UUID> duelists = new HashSet<>();

        @Override public boolean allocatedTo(UUID player, int areaId) { return Integer.valueOf(areaId).equals(allocated.get(player)); }
        @Override public boolean confined(UUID player, int areaId) { return allocatedTo(player, areaId) && confined.contains(player); }
        @Override public boolean lobbyReserved(int arenaId) { return reservedLobbies.contains(arenaId); }
        @Override public boolean duelistOf(UUID player, int arenaId) { return duelists.contains(player); }
    }

    private final Runtime state = new Runtime();

    private Optional<ArenaAreaAccessRules.Refusal> move(UUID player, Set<String> from, Set<String> to) {
        return ArenaAreaAccessRules.evaluate(player, from, to, "world", List.of(arena), state);
    }

    @Test
    void aFreeBattlegroundRefusesEveryoneAtEitherPart() {
        var north = move(OUTSIDER, Set.of("arena_2"), Set.of("arena_2", "pit_north"));
        var south = move(OUTSIDER, Set.of("arena_2"), Set.of("ARENA_2", "PIT_SOUTH"));

        assertEquals(ArenaAreaAccessRules.BATTLEGROUND_RESERVED, north.orElseThrow().message());
        assertTrue(north.get().entry());
        assertEquals(PIT, south.orElseThrow().area().id());
    }

    @Test
    void theAllocatedDuelistEntersAndCrossesBetweenThePartsButCantLeaveWhileConfined() {
        state.allocated.put(DUELIST, PIT);
        state.confined.add(DUELIST);

        assertTrue(move(DUELIST, Set.of("arena_2"), Set.of("arena_2", "pit_north")).isEmpty());
        // two parts of one battleground are one area: no exit, no entry
        assertTrue(move(DUELIST, Set.of("arena_2", "pit_north"), Set.of("arena_2", "pit_south")).isEmpty());
        assertTrue(move(DUELIST, Set.of("arena_2", "pit_north"), Set.of("arena_2", "pit_north", "pit_south")).isEmpty());
        var out = move(DUELIST, Set.of("arena_2", "pit_south"), Set.of("arena_2"));
        assertEquals(ArenaAreaAccessRules.CONFINED, out.orElseThrow().message());
        assertFalse(out.get().entry());

        // the exit pass (or the end of the fight) lets them out
        state.confined.remove(DUELIST);
        assertTrue(move(DUELIST, Set.of("arena_2", "pit_south"), Set.of("arena_2")).isEmpty());
        // still refused for someone else, and for a duelist of another battleground
        state.allocated.put(OUTSIDER, 99);
        assertTrue(move(OUTSIDER, Set.of("arena_2"), Set.of("pit_south")).isPresent());
    }

    @Test
    void theLobbyIsDuelistsOnlyDuringAWarmup_OpenOtherwise() {
        assertTrue(move(OUTSIDER, Set.of("arena_2"), Set.of("arena_2", "hall")).isEmpty());

        state.reservedLobbies.add(ARENA);
        state.duelists.add(DUELIST);
        assertEquals(ArenaAreaAccessRules.LOBBY_RESERVED,
                move(OUTSIDER, Set.of("arena_2"), Set.of("arena_2", "hall")).orElseThrow().message());
        assertTrue(move(DUELIST, Set.of(), Set.of("arena_2", "hall")).isEmpty(), "the lobby teleport of a duelist");
        // leaving the lobby and standing in it are free
        assertTrue(move(OUTSIDER, Set.of("arena_2", "hall"), Set.of("arena_2")).isEmpty());
    }

    @Test
    void theStandsAreFree_AndOtherWorldsAndRegionsDontCount() {
        assertTrue(move(OUTSIDER, Set.of("arena_2"), Set.of("arena_2", "stands")).isEmpty());
        assertTrue(move(OUTSIDER, Set.of("arena_2", "stands"), Set.of()).isEmpty());
        assertTrue(ArenaAreaAccessRules.evaluate(OUTSIDER, Set.of(), Set.of("pit_north"), "world_nether", List.of(arena), state).isEmpty());
        assertTrue(move(OUTSIDER, Set.of("town_1"), Set.of("town_1", "district_2")).isEmpty());
        assertTrue(move(OUTSIDER, Set.of("pit_north"), Set.of("pit_north")).isEmpty());
        assertTrue(ArenaAreaAccessRules.evaluate(null, Set.of(), Set.of("pit_north"), "world", List.of(arena), state).isEmpty());
    }
}
