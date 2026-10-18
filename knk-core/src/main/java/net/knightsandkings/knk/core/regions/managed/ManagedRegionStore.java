package net.knightsandkings.knk.core.regions.managed;

import java.util.Optional;

/**
 * The WorldGuard side of region reconciliation, so the rules in this package stay Bukkit-free and testable. The Paper
 * adapter is {@code WorldGuardManagedRegionStore}.
 */
public interface ManagedRegionStore {

    /** The region's current state, or empty when no world has a region with that id. */
    Optional<ActualRegionState> read(String regionId) throws RegionStoreException;

    /**
     * KNG-112: the region with that id in {@code worldName} (a region id is unique per world only); null looks in every
     * world, as {@link #read(String)}. A store without worlds ignores the world.
     */
    default Optional<ActualRegionState> read(String worldName, String regionId) throws RegionStoreException {
        return read(regionId);
    }

    /** Whether WorldGuard knows a flag of that name (a custom flag from a plugin that isn't installed does not exist). */
    boolean supportsFlag(String flagName);

    /**
     * Applies {@code change} to the region: all of it or, on failure, none of it (throws {@link RegionStoreException}
     * after restoring what it had already touched). Must not touch owners, members or flags outside the change.
     */
    void apply(String regionId, RegionChange change) throws RegionStoreException;

    /** KNG-112: {@link #apply(String, RegionChange)} to the region of that id in {@code worldName} (null: any world). */
    default void apply(String worldName, String regionId, RegionChange change) throws RegionStoreException {
        apply(regionId, change);
    }

    /** Writes the changed regions to WorldGuard's storage. */
    void persist() throws RegionStoreException;
}
