package net.knightsandkings.knk.core.regions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;

/**
 * Finds the domain sub-regions no domain lists any more (KNG-258, knk-workspace
 * {@code docs/specs/domain-subregions/DESIGN.md} §8): a region named {@code domain_<d>_sub_<s>} whose sub-region was
 * removed in the web app or whose domain was deleted. Run with every domain access sync, which holds the complete list.
 *
 * <p>An orphan stops being part of its domain at once - its access flags, {@code knk-domain-id} and parent are removed,
 * so it refuses nobody and counts as no domain - and gets {@link #ORPHANED_SINCE_FLAG}. The daily temp-region cleanup
 * deletes it once that is older than the retention period ({@link TempRegionCleanupPolicy#decideOrphanedSubRegion}).
 * Until then it can be adopted again ({@code select} in a sub-region world task); once listed again the mark is removed.
 *
 * <p>A region is only marked when two runs in a row didn't list it: the API stores a sub-region's final name only after
 * the plugin renamed it, so a run in between would otherwise take a brand-new sub-region for an orphan. The memory of
 * the previous run is kept in this object (not across restarts, which only delays a mark).
 */
public final class OrphanedSubRegions {

    public static final String ORPHANED_SINCE_FLAG = "knk-orphaned-since";

    /** A region named like a sub-region, with its {@link #ORPHANED_SINCE_FLAG} (null when not marked). */
    public record RegionRef(String world, String regionId, String orphanedSince) {
    }

    /** Where the regions live (WorldGuard in production). */
    public interface Store {
        /** Every region named {@code domain_<d>_sub_<s>}, in every loaded world. */
        List<RegionRef> subRegionNamedRegions();

        /** Removes the region's access flags, {@code knk-domain-id} and parent, and stamps {@code orphanedSince}. */
        void markOrphaned(String world, String regionId, long orphanedSince);

        /** Removes {@link #ORPHANED_SINCE_FLAG}: the region is a domain's sub-region again. */
        void clearOrphaned(String world, String regionId);

        void persist();
    }

    /** What one run did. {@code waiting}: marked earlier, still waiting for the cleanup. */
    public record Report(List<String> marked, List<String> readopted, int waiting, int pending, List<String> failures,
                         boolean persistFailed) {
        public boolean changed() {
            return !marked.isEmpty() || !readopted.isEmpty();
        }

        public String summary() {
            return marked.size() + " sub-region(s) newly orphaned, " + readopted.size() + " adopted again, " + waiting
                + " orphaned waiting for the cleanup, " + pending + " unlisted once (marked if unlisted again)"
                + (failures.isEmpty() ? "" : ", " + failures.size() + " failed") + (persistFailed ? ", SAVE FAILED" : "");
        }
    }

    private Set<String> unlistedLastRun = new HashSet<>();

    public Report run(Collection<DomainAccessRule> rules, Store store, long now) {
        Objects.requireNonNull(rules, "rules");
        Set<String> listed = new HashSet<>();
        Set<String> listedInSomeWorld = new HashSet<>();
        for (DomainAccessRule rule : rules) {
            for (String subRegionId : rule.subRegionIds()) {
                listed.add(key(rule.worldName(), subRegionId));
                listedInSomeWorld.add(key(null, subRegionId));
            }
        }

        List<String> marked = new ArrayList<>();
        List<String> readopted = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Set<String> unlisted = new HashSet<>();
        int waiting = 0;
        int pending = 0;
        for (RegionRef region : store.subRegionNamedRegions()) {
            String key = key(region.world(), region.regionId());
            boolean isListed = listed.contains(key) || listed.contains(key(null, region.regionId()))
                || (region.world() == null && listedInSomeWorld.contains(key(null, region.regionId())));
            String label = region.world() == null ? region.regionId() : region.world() + "/" + region.regionId();
            boolean isMarked = region.orphanedSince() != null && !region.orphanedSince().isBlank();
            try {
                if (isListed) {
                    if (isMarked) {
                        store.clearOrphaned(region.world(), region.regionId());
                        readopted.add(label);
                    }
                } else if (isMarked) {
                    waiting++;
                } else if (unlistedLastRun.contains(key)) {
                    store.markOrphaned(region.world(), region.regionId(), now);
                    marked.add(label);
                } else {
                    unlisted.add(key);
                    pending++;
                }
            } catch (RuntimeException e) {
                failures.add(label + ": " + e.getMessage());
            }
        }
        unlistedLastRun = unlisted;

        boolean persistFailed = false;
        if (!marked.isEmpty() || !readopted.isEmpty()) {
            try {
                store.persist();
            } catch (RuntimeException e) {
                persistFailed = true;
                failures.add("save: " + e.getMessage());
            }
        }
        return new Report(List.copyOf(marked), List.copyOf(readopted), waiting, pending, List.copyOf(failures), persistFailed);
    }

    private static String key(String world, String regionId) {
        return (world == null || world.isBlank() ? "" : world.trim().toLowerCase(Locale.ROOT)) + "\u0000"
            + regionId.trim().toLowerCase(Locale.ROOT);
    }
}
