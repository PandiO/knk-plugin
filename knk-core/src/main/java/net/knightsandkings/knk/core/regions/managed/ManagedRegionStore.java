package net.knightsandkings.knk.core.regions.managed;

import java.util.Optional;

/**
 * The WorldGuard side of region reconciliation, so the rules in this package stay Bukkit-free and testable. The Paper
 * adapter is {@code WorldGuardManagedRegionStore}.
 */
public interface ManagedRegionStore {

    /** The region's current state, or empty when no world has a region with that id. */
    Optional<ActualRegionState> read(String regionId) throws RegionStoreException;

    /** Whether WorldGuard knows a flag of that name (a custom flag from a plugin that isn't installed does not exist). */
    boolean supportsFlag(String flagName);

    /**
     * Applies {@code change} to the region: all of it or, on failure, none of it (throws {@link RegionStoreException}
     * after restoring what it had already touched). Must not touch owners, members or flags outside the change.
     */
    void apply(String regionId, RegionChange change) throws RegionStoreException;

    /** Writes the changed regions to WorldGuard's storage. */
    void persist() throws RegionStoreException;
}
