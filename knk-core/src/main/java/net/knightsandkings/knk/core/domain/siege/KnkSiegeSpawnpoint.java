package net.knightsandkings.knk.core.domain.siege;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/** A team spawnpoint (DESIGN §3.5); combat is denied inside {@code safeZoneRadius} (§6.7). */
public record KnkSiegeSpawnpoint(int id, int sortOrder, String name, KnkLocation location, double safeZoneRadius) {
}
