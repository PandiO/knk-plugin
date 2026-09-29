package net.knightsandkings.knk.core.regions.managed;

import java.util.Map;

/**
 * What a region looks like in WorldGuard right now. {@code flags} maps a flag name to its value ({@link String},
 * {@link Integer}, {@link FlagState}, or the marshalled text of a flag type this code doesn't manage). Owners and members
 * are deliberately not part of it: they belong to the players, not to the policy.
 */
public record ActualRegionState(String regionId, String parentRegionId, int priority, Map<String, Object> flags) {

    public ActualRegionState {
        flags = flags != null ? Map.copyOf(flags) : Map.of();
    }
}
