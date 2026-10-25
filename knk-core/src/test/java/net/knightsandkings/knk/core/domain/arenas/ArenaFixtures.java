package net.knightsandkings.knk.core.domain.arenas;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/** Arenas with the design defaults (DESIGN.md §4.2) and their areas (rev. 6) for tests. */
public final class ArenaFixtures {
    private ArenaFixtures() {}

    public static final KnkLocation LOBBY = new KnkLocation(100, "lobby", 0.0, 64.0, 0.0, 0f, 0f, "world");

    /** An arena with {@code areas}, plus an enabled lobby (area id 9_000 + id) unless one is among them. */
    public static KnkArena arena(int id, String key, List<KnkArenaArea> areas) {
        List<KnkArenaArea> all = new ArrayList<>(areas);
        if (all.stream().noneMatch(a -> a.kind() == KnkArenaArea.Kind.LOBBY)) {
            all.add(lobby(9_000 + id, id, "lobby_" + id, LOBBY));
        }
        return new KnkArena(id, key, "Arena " + key, key, "world", 2, null, null,
                true, 3, 3, 60, true, true, 10_000, 9, 50, 5, null, null, 3, 10, 25_000,
                true, false, false, false, KnkArena.DuelGearMode.OWN_GEAR_KEEP,
                30, 10, 300, 180, 5, true, true, List.of(), all, List.of());
    }

    public static KnkArena arena(int id, String key) {
        return arena(id, key, List.of());
    }

    /** An enabled battleground with one region per id in {@code regionIds}, ids 10 * area id + n. */
    public static KnkArenaArea battleground(int id, int arenaId, String key, int sortOrder, KnkLocation spawnA,
                                            KnkLocation spawnB, String... regionIds) {
        return new KnkArenaArea(id, arenaId, KnkArenaArea.Kind.BATTLEGROUND, key, "Pit " + key, sortOrder, true,
                spawnA, spawnB, null, null, null, regions(id, regionIds));
    }

    /** An enabled spectator area. */
    public static KnkArenaArea spectator(int id, int arenaId, String name, KnkLocation arrival, Integer titleId,
                                         Integer priceGems, String... regionIds) {
        return new KnkArenaArea(id, arenaId, KnkArenaArea.Kind.SPECTATOR, "stands_" + id, name, 0, true,
                null, null, arrival, titleId, priceGems, regions(id, regionIds));
    }

    /** An enabled lobby. */
    public static KnkArenaArea lobby(int id, int arenaId, String regionId, KnkLocation arrival) {
        return new KnkArenaArea(id, arenaId, KnkArenaArea.Kind.LOBBY, "lobby", "Lobby", 0, true,
                null, null, arrival, null, null, regions(id, regionId));
    }

    public static List<KnkArenaAreaRegion> regions(int areaId, String... regionIds) {
        List<KnkArenaAreaRegion> out = new ArrayList<>();
        for (int i = 0; i < regionIds.length; i++) {
            out.add(new KnkArenaAreaRegion(areaId * 10 + i, regionIds[i], "world"));
        }
        return out;
    }
}
