package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;

/**
 * Read-side port for duel arenas (arena duels KNG-210, DESIGN.md §4.1-4.2). Go through {@code ArenasDataAccess}
 * (cache-first) rather than calling this directly.
 */
public interface ArenasApi {
    /** {@code GET /api/Arenas}: every arena with its settings, areas (with their regions), sub-regions and eligible kits. */
    CompletableFuture<List<KnkArena>> getAll();

    /**
     * Arena duels rev. 6: {@code GET /api/Arenas/areas/by-region/{regionId}?world=}: whether an arena area uses the
     * region (the temp-region cleanup's fresh check before deleting an {@code arena_<a>_<kind>_<n>} or temp region).
     * Completes true when one does; fails with an {@code ApiException} of status 404 when none does.
     */
    default CompletableFuture<Boolean> areaRegionExists(String regionId, String worldName) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("areaRegionExists"));
    }
}
