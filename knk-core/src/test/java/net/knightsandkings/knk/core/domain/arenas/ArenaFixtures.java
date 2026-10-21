package net.knightsandkings.knk.core.domain.arenas;

import java.util.List;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/** Arenas with the design defaults (DESIGN.md §4.2) for tests. */
public final class ArenaFixtures {
    private ArenaFixtures() {}

    public static KnkArena arena(int id, String key, List<KnkArenaBattleground> battlegrounds,
                                 List<KnkArenaSpectatorArea> spectatorAreas) {
        return new KnkArena(id, key, "Arena " + key, key, "world", 2, null,
                new KnkLocation(100, "lobby", 0.0, 64.0, 0.0, 0f, 0f, "world"), null,
                true, 3, 3, 60, true, true, 10_000, 9, 50, 5, null, null, 3, 10, 25_000,
                true, false, false, false, KnkArena.DuelGearMode.OWN_GEAR_KEEP,
                30, 10, 300, 180, 5, true, true, List.of(), battlegrounds, spectatorAreas);
    }

    public static KnkArena arena(int id, String key) {
        return arena(id, key, List.of(), List.of());
    }
}
