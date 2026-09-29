package net.knightsandkings.knk.core.regions.managed;

import java.util.List;

/**
 * A per-region correction to what the domain data implies, from config: the way to record an exact v1 priority, give a
 * Structure a subtype the API doesn't have yet (House, the {@code property_47} resource structure) or add a one-off flag.
 * Every field is optional (null / empty = no override).
 */
public record RegionOverride(ManagedRegionKind kind, String parentRegionId, Integer priority, List<FlagRule> extraFlags) {

    public RegionOverride {
        extraFlags = extraFlags != null ? List.copyOf(extraFlags) : List.of();
    }
}
