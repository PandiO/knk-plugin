package net.knightsandkings.knk.core.regions;

import java.util.Set;
import java.util.UUID;

/**
 * Core contract to decide whether a region transition is allowed and what message to show.
 * Remains Paper/WorldGuard agnostic.
 */
public interface RegionTransitionService {
    RegionTransitionDecision handleRegionTransition(
            UUID playerId,
            Set<String> oldRegionIds,
            Set<String> newRegionIds
    );

    /**
     * Side-effect-free version of the entry/exit policy part of
     * {@link #handleRegionTransition}: would moving from {@code oldRegionIds} to
     * {@code newRegionIds} be refused? Used by the teleport engine to refuse a teleport up front
     * (docs/specs/teleport/DESIGN.md §3.1) instead of only via the cancelled teleport event.
     * Reads cached domain data only - an uncached region can't be judged here and counts as
     * allowed (the teleport event listener still enforces it).
     *
     * @return a deny decision, or null when allowed or unknown
     */
    default RegionTransitionDecision previewAccess(Set<String> oldRegionIds, Set<String> newRegionIds) {
        return null;
    }
}
