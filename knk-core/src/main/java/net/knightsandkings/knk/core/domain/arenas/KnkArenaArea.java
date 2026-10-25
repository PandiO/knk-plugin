package net.knightsandkings.knk.core.domain.arenas;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/**
 * A battleground, spectator area or lobby of an arena (arena duels DESIGN.md §4.1, rev. 6, developer 2026-10-24): the
 * arena's own sub-structure, not a domain, so it has no enter/leave messages, discovery, navigation or access flags. The
 * area is the union of its {@link #regions()}; the duel runtime enforces who may cross (§8.2).
 *
 * @param spawnA                    battleground only: the challenger's countdown spot
 * @param spawnB                    battleground only: the opponent's
 * @param arrival                   spectator area and lobby: where a teleport there lands
 * @param teleportMinTitleBracketId spectator area: the spectate teleport's minimum title (null = none)
 * @param teleportPriceGems         spectator area: the spectate teleport's gem price (null or 0 = free)
 */
public record KnkArenaArea(
        int id,
        int arenaId,
        Kind kind,
        String key,
        String name,
        int sortOrder,
        boolean enabled,
        KnkLocation spawnA,
        KnkLocation spawnB,
        KnkLocation arrival,
        Integer teleportMinTitleBracketId,
        Integer teleportPriceGems,
        List<KnkArenaAreaRegion> regions
) {
    /** What an area is for; the API stores the name. */
    public enum Kind {
        BATTLEGROUND, SPECTATOR, LOBBY;

        /** Parses the API's name (any case); empty for null or an unknown name. */
        public static Optional<Kind> parse(String name) {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                return Optional.empty();
            }
        }
    }

    /** Allocation / spectate order: sort order, then id. */
    public static final Comparator<KnkArenaArea> ORDER =
            Comparator.comparingInt(KnkArenaArea::sortOrder).thenComparingInt(KnkArenaArea::id);

    public KnkArenaArea {
        kind = kind == null ? Kind.BATTLEGROUND : kind;
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    /** The name for menus and the boss bar: the name, else the key. */
    public String displayName() {
        return name != null && !name.isBlank() ? name : key;
    }

    /** A battleground a match may use: enabled, both spawns and at least one region. */
    public boolean isUsableBattleground() {
        return kind == Kind.BATTLEGROUND && enabled && spawnA != null && spawnB != null && !regions.isEmpty();
    }

    /** Whether one of this area's regions is {@code regionId} in {@code worldName} (null world: any; ids ignore case). */
    public boolean hasRegion(String worldName, String regionId) {
        if (regionId == null) {
            return false;
        }
        for (KnkArenaAreaRegion region : regions) {
            if (region.matches(worldName, regionId)) {
                return true;
            }
        }
        return false;
    }

    /** The region ids, in the API's order. */
    public List<String> regionIds() {
        return regions.stream().map(KnkArenaAreaRegion::wgRegionId).filter(id -> id != null && !id.isBlank()).toList();
    }
}
