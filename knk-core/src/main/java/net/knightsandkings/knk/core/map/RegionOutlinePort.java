package net.knightsandkings.knk.core.map;

import java.util.Optional;

/**
 * Reads a region's outline from the platform's region store (WorldGuard on Paper: {@code RegionOutlineAdapter}). Called
 * on the thread where the store may be read (the main thread on Paper).
 */
public interface RegionOutlinePort {

    /**
     * The outline of {@code regionId} in {@code worldName} (null: the first loaded world that has it), or empty when there
     * is no such region or it has no outline (a global region).
     */
    Optional<RegionOutline> outline(String worldName, String regionId);
}
