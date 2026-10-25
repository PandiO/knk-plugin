package net.knightsandkings.knk.paper.regions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.sk89q.worldguard.protection.managers.RegionManager;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaArea;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaAreaRegion;
import net.knightsandkings.knk.core.regions.ArenaAreaRegionNames;
import net.knightsandkings.knk.core.regions.OrphanedSubRegions;

/**
 * Orphaned arena area regions (arena duels DESIGN.md §4.1, rev. 6), handled like KNG-253's orphaned sub-regions: a
 * region named {@code arena_<a>_<kind>_<n>} that no arena lists any more (its row, area or arena was deleted) loses its
 * parent and gets {@code knk-orphaned-since} after two runs without it; the daily temp-region cleanup deletes it once
 * that is older than the retention period and a fresh {@code GET /Arenas/areas/by-region} answers 404
 * ({@code TempRegionCleanupPolicy.decideOrphanedArenaAreaRegion}). Run with a list read fresh from the API only.
 * Main thread.
 */
public final class ArenaAreaRegionOrphans {

    private static final Logger LOGGER = Logger.getLogger(ArenaAreaRegionOrphans.class.getName());

    private final OrphanedSubRegions orphans = new OrphanedSubRegions();
    private final WorldGuardOrphanedSubRegionStore store;
    /** The area region ids the last run listed (lower case); null before the first. */
    private volatile Set<String> listed;

    public ArenaAreaRegionOrphans(Supplier<List<RegionManager>> managers) {
        this.store = new WorldGuardOrphanedSubRegionStore(managers, ArenaAreaRegionNames::isAreaRegionName);
    }

    /** One pass over the arenas the API just returned. */
    public OrphanedSubRegions.Report run(List<KnkArena> arenas, long now) {
        List<OrphanedSubRegions.Listed> regions = new ArrayList<>();
        for (KnkArena arena : arenas) {
            for (KnkArenaArea area : arena.areas()) {
                for (KnkArenaAreaRegion region : area.regions()) {
                    regions.add(new OrphanedSubRegions.Listed(
                        region.worldName() != null ? region.worldName() : arena.worldName(), region.wgRegionId()));
                }
            }
        }
        listed = regions.stream().map(r -> r.regionId().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        OrphanedSubRegions.Report report = orphans.runListed(regions, store, now);
        if (report.changed() || !report.failures().isEmpty()) {
            LOGGER.info("[KnK Regions] Orphaned arena area regions: " + report.summary()
                + (report.marked().isEmpty() ? "" : " - newly orphaned: " + report.marked()));
        }
        report.failures().stream().limit(10).forEach(failure -> LOGGER.warning("[KnK Regions] " + failure));
        return report;
    }

    /**
     * Whether the last run listed {@code regionId} for an arena area; true for every region until a run has completed,
     * so the cleanup deletes nothing it can't rule out.
     */
    public boolean listsAreaRegion(String regionId) {
        Set<String> last = listed;
        return last == null || (regionId != null && last.contains(regionId.toLowerCase(Locale.ROOT)));
    }
}
