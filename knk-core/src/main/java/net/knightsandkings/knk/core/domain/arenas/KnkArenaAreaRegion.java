package net.knightsandkings.knk.core.domain.arenas;

/**
 * One WorldGuard region of an {@link KnkArenaArea} (arena duels DESIGN.md §4.1, rev. 6): {@code arena_<arenaId>_<kind>_<id>}
 * once saved (a temp-named world-task region until then).
 */
public record KnkArenaAreaRegion(int id, String wgRegionId, String worldName) {

    /** Whether this is {@code regionId} in {@code world} (null or blank world matches any; ids and worlds ignore case). */
    public boolean matches(String world, String regionId) {
        if (wgRegionId == null || regionId == null || !wgRegionId.equalsIgnoreCase(regionId.trim())) {
            return false;
        }
        return world == null || world.isBlank() || worldName == null || worldName.isBlank()
                || worldName.equalsIgnoreCase(world.trim());
    }
}
