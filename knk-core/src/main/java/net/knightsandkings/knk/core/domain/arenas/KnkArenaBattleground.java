package net.knightsandkings.knk.core.domain.arenas;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/**
 * A fighting area of an arena (DESIGN.md §4.1). {@code allowEntry} is normally false: the duel runtime lets the two
 * duelists in through a domain-access exemption (D16).
 */
public record KnkArenaBattleground(
        int id,
        int arenaId,
        String key,
        String name,
        String wgRegionId,
        String worldName,
        int sortOrder,
        boolean enabled,
        boolean allowEntry,
        KnkLocation spawnA,
        KnkLocation spawnB
) {
    /** Enabled and both spawns set. */
    public boolean isUsable() {
        return enabled && spawnA != null && spawnB != null;
    }
}
