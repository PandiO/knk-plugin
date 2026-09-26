package net.knightsandkings.knk.core.domain.siege;

/** A district of the scenario area (lockdown, DESIGN §8.5), with its WorldGuard region id. */
public record KnkSiegeDistrict(int id, String name, String wgRegionId) {
}
