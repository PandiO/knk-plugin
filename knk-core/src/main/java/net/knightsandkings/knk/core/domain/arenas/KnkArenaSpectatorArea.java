package net.knightsandkings.knk.core.domain.arenas;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/**
 * Where players watch an arena's duels (DESIGN.md §9.3): an ordinary domain; a /warp destination when
 * {@code teleportEnabled}, with its location as the arrival point.
 */
public record KnkArenaSpectatorArea(
        int id,
        int arenaId,
        String name,
        String wgRegionId,
        String worldName,
        boolean teleportEnabled,
        KnkLocation location
) {
}
