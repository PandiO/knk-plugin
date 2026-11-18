package net.knightsandkings.knk.core.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;
import net.knightsandkings.knk.core.regions.OrphanedSubRegions.RegionRef;
import net.knightsandkings.knk.core.regions.OrphanedSubRegions.Report;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Decision;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Usage;

/**
 * KNG-258: a domain_&lt;d&gt;_sub_&lt;s&gt; region no domain lists becomes an orphan (on the second sync in a row), and
 * the cleanup deletes it once it has been orphaned for the retention period.
 */
class OrphanedSubRegionsTest {

    private static final class FakeStore implements OrphanedSubRegions.Store {
        final Map<String, String> orphanedSince = new LinkedHashMap<>();
        final List<String> marks = new ArrayList<>();
        int saves;

        FakeStore region(String id, String since) {
            orphanedSince.put(id, since);
            return this;
        }

        @Override
        public List<RegionRef> subRegionNamedRegions() {
            return orphanedSince.entrySet().stream().map(e -> new RegionRef("world", e.getKey(), e.getValue())).toList();
        }

        @Override
        public void markOrphaned(String world, String regionId, long since) {
            orphanedSince.put(regionId, String.valueOf(since));
            marks.add(regionId);
        }

        @Override
        public void clearOrphaned(String world, String regionId) {
            orphanedSince.put(regionId, null);
        }

        @Override
        public void persist() {
            saves++;
        }
    }

    private static DomainAccessRule garrison(String... subs) {
        return new DomainAccessRule(11, "Garrison", "domain_11", true, true, "GateStructure", "world", List.of(subs));
    }

    @Test
    void aSubRegionNoDomainListsIsMarkedOnTheSecondSyncInARow() {
        FakeStore store = new FakeStore().region("domain_11_sub_4", null).region("domain_11_sub_5", null);
        OrphanedSubRegions orphans = new OrphanedSubRegions();

        Report first = orphans.run(List.of(garrison("domain_11_sub_4")), store, 1000L);
        assertEquals(1, first.pending());
        assertTrue(store.marks.isEmpty());
        assertEquals(0, store.saves);

        Report second = orphans.run(List.of(garrison("domain_11_sub_4")), store, 2000L);
        assertEquals(List.of("world/domain_11_sub_5"), second.marked());
        assertEquals("2000", store.orphanedSince.get("domain_11_sub_5"));
        assertNull(store.orphanedSince.get("domain_11_sub_4"));
        assertEquals(1, store.saves);

        Report third = orphans.run(List.of(garrison("domain_11_sub_4")), store, 3000L);
        assertEquals(1, third.waiting());
        assertEquals("2000", store.orphanedSince.get("domain_11_sub_5"), "the first mark stays");
    }

    @Test
    void aNewSubRegionListedByTheSecondSyncIsNeverMarked() {
        // The plugin renamed it; the API stores the final name a moment later.
        FakeStore store = new FakeStore().region("domain_11_sub_6", null);
        OrphanedSubRegions orphans = new OrphanedSubRegions();

        orphans.run(List.of(garrison()), store, 1000L);
        Report second = orphans.run(List.of(garrison("domain_11_sub_6")), store, 2000L);

        assertTrue(second.marked().isEmpty());
        assertTrue(store.marks.isEmpty());
    }

    @Test
    void anOrphanAdoptedAgainLosesItsMark() {
        FakeStore store = new FakeStore().region("domain_11_sub_4", "500");

        Report report = new OrphanedSubRegions().run(List.of(garrison("DOMAIN_11_SUB_4")), store, 1000L);

        assertEquals(List.of("world/domain_11_sub_4"), report.readopted());
        assertNull(store.orphanedSince.get("domain_11_sub_4"));
    }

    @Test
    void theCleanupCountsFromWhenItWasOrphaned() {
        long cutoff = 10_000L;
        assertEquals(Decision.DELETE, TempRegionCleanupPolicy.decideOrphanedSubRegion("domain_11_sub_4", "9999", cutoff,
            id -> false, id -> Usage.UNUSED));
        assertEquals(Decision.KEEP_TOO_RECENT, TempRegionCleanupPolicy.decideOrphanedSubRegion("domain_11_sub_4", "10000",
            cutoff, id -> false, id -> Usage.UNUSED));
        assertEquals(Decision.KEEP_NO_TIMESTAMP, TempRegionCleanupPolicy.decideOrphanedSubRegion("domain_11_sub_4", null,
            cutoff, id -> false, id -> Usage.UNUSED));
        assertEquals(Decision.KEEP_KNOWN_DOMAIN_REGION, TempRegionCleanupPolicy.decideOrphanedSubRegion("domain_11_sub_4",
            "1", cutoff, id -> true, id -> Usage.UNUSED));
        assertEquals(Decision.KEEP_IN_USE, TempRegionCleanupPolicy.decideOrphanedSubRegion("domain_11_sub_4", "1", cutoff,
            id -> false, id -> Usage.IN_USE));
        assertEquals(Decision.KEEP_USAGE_UNKNOWN, TempRegionCleanupPolicy.decideOrphanedSubRegion("domain_11_sub_4", "1",
            cutoff, id -> false, id -> { throw new IllegalStateException("API down"); }));
        assertEquals(Decision.NOT_TEMPORARY, TempRegionCleanupPolicy.decideOrphanedSubRegion("domain_11", "1", cutoff,
            id -> false, id -> Usage.UNUSED));
    }
}
